package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.Bucket;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.DateRange;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.data.SettingsStore;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.util.PermissionManager;
import com.shaforostoff.dcimsort.util.Sdk;
import com.shaforostoff.dcimsort.work.OrganizeRequest;
import com.shaforostoff.dcimsort.work.OrganizeService;
import com.shaforostoff.dcimsort.work.Recompressor;
import com.shaforostoff.dcimsort.work.SizeEstimator;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements OrganizeService.Listener {

    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_PICK_FOLDER = 11;
    private static final int REQ_CONSENT = 12;
    private static final int CONSENT_CHUNK = 480;

    private static final int CONSENT_WRITE = 0;

    private SettingsStore settings;
    private MediaRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Views
    private TextView txtFolder, txtStats, txtQuality, txtProgress, txtPlan;
    private RadioGroup radioMode;
    private RadioButton radioNone, radioWebp, radioHeic;
    private LinearLayout qualityGroup, progressGroup;
    private SeekBar seekQuality;
    private CheckBox checkSkipFav;
    private Button btnPreview, btnOrganize, btnStop, btnBrowse, btnDateFrom, btnDateTo;
    private ProgressBar progressBar;

    // Current folder
    private String relPath, dataDir, displayName, volumeName;
    private long bucketId = -1;

    // Cached folder contents (newest-first) + date-range scoping
    private List<MediaImage> allImages;
    private long folderMinDate, folderMaxDate; // span of dated photos; 0 if none
    private long rangeFrom = Long.MIN_VALUE, rangeTo = Long.MAX_VALUE;
    private int summaryGen;
    private volatile double lastEstimateRatio; // last calibrated bytes/MP; handed to Preview so it needn't re-encode

    // Pending organize job + consent queue
    private List<MediaImage> pendingImages;
    private CompressMode pendingMode;
    private int pendingQuality;
    private boolean pendingSkipFav;
    private String pendingRel, pendingDir;
    private List<ConsentStep> consentQueue;
    private int consentIndex;

    private static final class ConsentStep {
        final int type;
        final List<Uri> uris;
        ConsentStep(int type, List<Uri> uris) { this.type = type; this.uris = uris; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        settings = new SettingsStore(this);
        repo = new MediaRepository(this);

        bindViews();
        setupControls();
        applySavedSettings();

        if (PermissionManager.hasReadMedia(this)) {
            initFolder();
        } else {
            requestNeededPermissions();
            txtStats.setText(R.string.need_media_permission);
        }
    }

    private void bindViews() {
        btnBrowse = findViewById(R.id.btn_browse);
        txtFolder = findViewById(R.id.txt_folder);
        txtStats = findViewById(R.id.txt_stats);
        radioMode = findViewById(R.id.radio_mode);
        radioNone = findViewById(R.id.radio_none);
        radioWebp = findViewById(R.id.radio_webp);
        radioHeic = findViewById(R.id.radio_heic);
        qualityGroup = findViewById(R.id.quality_group);
        txtQuality = findViewById(R.id.txt_quality);
        seekQuality = findViewById(R.id.seek_quality);
        checkSkipFav = findViewById(R.id.check_skip_fav);
        btnDateFrom = findViewById(R.id.btn_date_from);
        btnDateTo = findViewById(R.id.btn_date_to);
        txtPlan = findViewById(R.id.txt_plan);
        btnPreview = findViewById(R.id.btn_preview);
        btnOrganize = findViewById(R.id.btn_organize);
        progressGroup = findViewById(R.id.progress_group);
        progressBar = findViewById(R.id.progress_bar);
        txtProgress = findViewById(R.id.txt_progress);
        btnStop = findViewById(R.id.btn_stop);
    }

    private void setupControls() {
        btnBrowse.setOnClickListener(v -> {
            if (!PermissionManager.hasReadMedia(this)) {
                requestNeededPermissions();
                return;
            }
            startActivityForResult(new Intent(this, FolderPickerActivity.class), REQ_PICK_FOLDER);
        });

        // HEIC only when the device can encode it.
        if (!Recompressor.hasHeicEncoder()) {
            radioHeic.setVisibility(View.GONE);
        }
        // Favorites skip only on Android 11+.
        if (!Sdk.atLeastR()) {
            checkSkipFav.setVisibility(View.GONE);
        }

        radioMode.setOnCheckedChangeListener((group, checkedId) -> {
            CompressMode mode = currentMode();
            settings.setMode(mode);
            qualityGroup.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
            recomputeSummary();
        });

        seekQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                txtQuality.setText(getString(R.string.quality_label, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                settings.setQuality(s.getProgress());
                recomputeSummary();
            }
        });

        checkSkipFav.setOnCheckedChangeListener((b, checked) -> {
            settings.setSkipFavorites(checked);
            recomputeSummary();
        });

        btnDateFrom.setOnClickListener(v -> pickDate(true));
        btnDateTo.setOnClickListener(v -> pickDate(false));

        btnPreview.setOnClickListener(v -> openPreview());
        btnOrganize.setOnClickListener(v -> startOrganize());
        btnStop.setOnClickListener(v -> OrganizeService.stop(this));

        setRangeControlsEnabled(false);
        updateDateLabels();
    }

    private void applySavedSettings() {
        CompressMode mode = settings.getMode();
        if (mode == CompressMode.HEIC && !Recompressor.hasHeicEncoder()) {
            mode = CompressMode.NONE;
        }
        switch (mode) {
            case WEBP: radioWebp.setChecked(true); break;
            case HEIC: radioHeic.setChecked(true); break;
            default: radioNone.setChecked(true); break;
        }
        qualityGroup.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
        int q = settings.getQuality();
        seekQuality.setProgress(q);
        txtQuality.setText(getString(R.string.quality_label, q));
        checkSkipFav.setChecked(settings.getSkipFavorites());
    }

    private CompressMode currentMode() {
        int id = radioMode.getCheckedRadioButtonId();
        if (id == R.id.radio_webp) return CompressMode.WEBP;
        if (id == R.id.radio_heic) return CompressMode.HEIC;
        return CompressMode.NONE;
    }

    // ---- Folder + stats -----------------------------------------------------

    private void initFolder() {
        if (settings.hasSourceFolder()) {
            relPath = settings.getRelativePath();
            dataDir = settings.getDataPath();
            displayName = settings.getDisplayName();
            bucketId = settings.getBucketId();
            volumeName = settings.getVolumeName();
            applyFolder();
        } else {
            txtStats.setText(R.string.counting);
            executor.execute(() -> {
                final Bucket b = repo.findDefaultCameraBucket();
                main.post(() -> {
                    if (b != null) {
                        setFolder(b.relativePath, b.dataDir, b.displayName, b.id, b.volumeName);
                    } else {
                        txtFolder.setText(R.string.no_folder_selected);
                        txtStats.setText(R.string.no_photos);
                    }
                });
            });
        }
    }

    private void setFolder(String rel, String dir, String display, long id, String vol) {
        relPath = rel;
        dataDir = dir;
        displayName = display;
        bucketId = id;
        volumeName = vol;
        settings.setSourceFolder(rel, id, display, dir, vol);
        applyFolder();
    }

    private void applyFolder() {
        txtFolder.setText(displayName != null ? displayName
                : (relPath != null ? relPath : getString(R.string.no_folder_selected)));
        loadFolder();
    }

    /** Gathers the folder's images once, then derives stats, the date span, and the plan summary. */
    private void loadFolder() {
        if (relPath == null && dataDir == null) {
            txtStats.setText(R.string.no_folder_selected);
            txtPlan.setText("");
            return;
        }
        allImages = null;
        txtStats.setText(R.string.counting);
        txtPlan.setText("");
        setRangeControlsEnabled(false);
        final String rel = relPath, dir = dataDir, vol = volumeName;
        executor.execute(() -> {
            final List<MediaImage> imgs = new ArrayList<>();
            repo.forEachNewestFirst(rel, dir, vol, img -> {
                imgs.add(img);
                return true;
            });
            long sum = 0, min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (MediaImage m : imgs) {
                sum += m.size;
                if (m.dateTakenMillis > 0) {
                    min = Math.min(min, m.dateTakenMillis);
                    max = Math.max(max, m.dateTakenMillis);
                }
            }
            final long fSum = sum;
            final long fMin = (min == Long.MAX_VALUE) ? 0 : min;
            final long fMax = (max == Long.MIN_VALUE) ? 0 : max;
            main.post(() -> {
                if (!sameFolder(rel, dir)) return;
                allImages = imgs;
                txtStats.setText(getString(R.string.photos_count_size,
                        imgs.size(), Formatter.humanReadableBytes(fSum)));
                folderMinDate = fMin;
                folderMaxDate = fMax;
                rangeFrom = fMin > 0 ? startOfDay(fMin) : Long.MIN_VALUE;
                rangeTo = fMax > 0 ? endOfDay(fMax) : Long.MAX_VALUE;
                updateDateLabels();
                setRangeControlsEnabled(fMin > 0);
                recomputeSummary();
            });
        });
    }

    private boolean sameFolder(String rel, String dir) {
        return eq(rel, relPath) && eq(dir, dataDir);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    // ---- Date range ---------------------------------------------------------

    private void setRangeControlsEnabled(boolean enabled) {
        btnDateFrom.setEnabled(enabled);
        btnDateTo.setEnabled(enabled);
    }

    private void updateDateLabels() {
        if (folderMinDate <= 0) {
            btnDateFrom.setText(getString(R.string.date_from_label, "—"));
            btnDateTo.setText(getString(R.string.date_to_label, "—"));
            return;
        }
        btnDateFrom.setText(getString(R.string.date_from_label, formatDay(rangeFrom)));
        btnDateTo.setText(getString(R.string.date_to_label, formatDay(rangeTo)));
    }

    private static String formatDay(long millis) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(millis));
    }

    private static long startOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    private static long endOfDay(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, 23);
        c.set(Calendar.MINUTE, 59);
        c.set(Calendar.SECOND, 59);
        c.set(Calendar.MILLISECOND, 999);
        return c.getTimeInMillis();
    }

    private void pickDate(boolean isFrom) {
        long base = isFrom ? rangeFrom : rangeTo;
        if (base == Long.MIN_VALUE || base == Long.MAX_VALUE || base <= 0) {
            base = (isFrom ? folderMinDate : folderMaxDate);
        }
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(base > 0 ? base : System.currentTimeMillis());
        DatePickerDialog dlg = new DatePickerDialog(this, (view, year, month, day) -> {
            Calendar picked = Calendar.getInstance();
            picked.set(year, month, day, 12, 0, 0);
            long val = picked.getTimeInMillis();
            if (isFrom) {
                rangeFrom = startOfDay(val);
                if (rangeTo < rangeFrom) rangeTo = endOfDay(val);
            } else {
                rangeTo = endOfDay(val);
                if (rangeFrom > rangeTo) rangeFrom = startOfDay(val);
            }
            updateDateLabels();
            recomputeSummary();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.show();
    }

    private List<MediaImage> imagesInRange() {
        DateRange r = new DateRange(rangeFrom, rangeTo);
        List<MediaImage> inRange = new ArrayList<>();
        if (allImages != null) {
            for (MediaImage m : allImages) {
                if (r.contains(m.dateTakenMillis)) inRange.add(m);
            }
        }
        return inRange;
    }

    // ---- Plan summary -------------------------------------------------------

    private void recomputeSummary() {
        if (allImages == null) {
            txtPlan.setText("");
            return;
        }
        final List<MediaImage> inRange = imagesInRange();
        final int count = inRange.size();
        final CompressMode mode = currentMode();
        final int quality = seekQuality.getProgress();
        final boolean skipFav = checkSkipFav.isChecked() && Sdk.atLeastR();
        final int gen = ++summaryGen;

        if (count == 0) {
            txtPlan.setText(getString(R.string.plan_summary_exact, 0,
                    Formatter.humanReadableBytes(0)));
            return;
        }
        if (!mode.recompresses()) {
            long total = 0;
            for (MediaImage m : inRange) total += m.size;
            txtPlan.setText(getString(R.string.plan_summary_exact, count,
                    Formatter.humanReadableBytes(total)));
            return;
        }
        txtPlan.setText(getString(R.string.plan_estimating, count));
        final Recompressor rc = new Recompressor(this, repo);
        executor.execute(() -> {
            double ratio = SizeEstimator.calibrateRatio(
                    inRange, mode, quality, rc, () -> gen != summaryGen);
            long est = SizeEstimator.estimateWithRatio(inRange, ratio, mode, skipFav);
            if (gen != summaryGen) return;
            lastEstimateRatio = ratio; // reused by Preview instead of re-encoding samples
            main.post(() -> {
                if (gen == summaryGen) {
                    txtPlan.setText(getString(R.string.plan_summary_estimated, count,
                            Formatter.humanReadableBytes(est)));
                }
            });
        });
    }

    // ---- Preview ------------------------------------------------------------

    private void openPreview() {
        if (relPath == null && dataDir == null) {
            toast(R.string.select_folder_first);
            return;
        }
        Intent i = new Intent(this, PreviewActivity.class);
        putFolderExtras(i);
        i.putExtra(Extras.RATIO, lastEstimateRatio); // 0 if not yet calibrated → Preview uses its default
        startActivity(i);
    }

    private void putFolderExtras(Intent i) {
        i.putExtra(Extras.REL_PATH, relPath);
        i.putExtra(Extras.DATA_DIR, dataDir);
        i.putExtra(Extras.VOLUME_NAME, volumeName);
        i.putExtra(Extras.DISPLAY, displayName);
        i.putExtra(Extras.MODE, currentMode().name());
        i.putExtra(Extras.QUALITY, seekQuality.getProgress());
        i.putExtra(Extras.SKIP_FAV, checkSkipFav.isChecked() && Sdk.atLeastR());
        i.putExtra(Extras.DATE_FROM, rangeFrom);
        i.putExtra(Extras.DATE_TO, rangeTo);
    }

    // ---- Organize -----------------------------------------------------------

    private void startOrganize() {
        if (relPath == null && dataDir == null) {
            toast(R.string.select_folder_first);
            return;
        }
        if (OrganizeService.RUNNING) {
            return;
        }
        if (allImages == null) {
            toast(R.string.counting);
            return;
        }
        // Reuse the cached folder listing, scoped to the selected date range.
        final List<MediaImage> imgs = imagesInRange();
        if (imgs.isEmpty()) {
            toast(R.string.no_photos);
            return;
        }
        setBusy(true);
        onImagesGathered(imgs, currentMode(), seekQuality.getProgress(),
                checkSkipFav.isChecked() && Sdk.atLeastR(), relPath, dataDir);
    }

    private void onImagesGathered(List<MediaImage> imgs, CompressMode mode, int quality,
                                  boolean skipFav, String rel, String dir) {
        if (imgs.isEmpty()) {
            setBusy(false);
            toast(R.string.no_photos);
            return;
        }
        pendingImages = imgs;
        pendingMode = mode;
        pendingQuality = quality;
        pendingSkipFav = skipFav;
        pendingRel = rel;
        pendingDir = dir;

        if (Sdk.atLeastR()) {
            buildConsentQueue(imgs);
            consentIndex = 0;
            processNextConsent();
        } else {
            launchService();
        }
    }

    private void buildConsentQueue(List<MediaImage> imgs) {
        consentQueue = new ArrayList<>();
        // Both moving (RELATIVE_PATH update) and recompressing (write a replacement, then delete the
        // original) need WRITE access to each original. We must NOT use createDeleteRequest here: it
        // deletes the originals the instant the user approves — before any replacement is written —
        // so any later failure loses the photo with nothing to show for it. Mover deletes each
        // original itself, only after its recompressed replacement has been written and verified.
        List<Uri> writeUris = new ArrayList<>();
        for (MediaImage m : imgs) writeUris.add(m.contentUri());
        addChunks(CONSENT_WRITE, writeUris);
    }

    private void addChunks(int type, List<Uri> uris) {
        for (int i = 0; i < uris.size(); i += CONSENT_CHUNK) {
            consentQueue.add(new ConsentStep(type,
                    new ArrayList<>(uris.subList(i, Math.min(i + CONSENT_CHUNK, uris.size())))));
        }
    }

    private void processNextConsent() {
        if (consentQueue == null || consentIndex >= consentQueue.size()) {
            launchService();
            return;
        }
        ConsentStep step = consentQueue.get(consentIndex);
        ContentResolver resolver = getContentResolver();
        PendingIntent pi;
        try {
            // Always a write grant — never createDeleteRequest, which would delete up front.
            pi = MediaStore.createWriteRequest(resolver, step.uris);
            startIntentSenderForResult(pi.getIntentSender(), REQ_CONSENT, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            setBusy(false);
            toast(R.string.organize_stopped);
        }
    }

    private void launchService() {
        OrganizeRequest req = new OrganizeRequest();
        req.images = pendingImages;
        req.mode = pendingMode;
        req.quality = pendingQuality;
        req.skipFavorites = pendingSkipFav;
        req.sourceRelativePath = pendingRel;
        req.sourceDataDir = pendingDir;
        req.volumeName = volumeName;
        OrganizeRequest.set(req);

        progressGroup.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        txtProgress.setText(getString(R.string.organizing_progress, 0, pendingImages.size(), ""));
        OrganizeService.start(this);
    }

    private void setBusy(boolean busy) {
        btnOrganize.setEnabled(!busy);
        btnPreview.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
    }

    // ---- Progress listener --------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        OrganizeService.setListener(this);
        if (OrganizeService.RUNNING) {
            setBusy(true);
            progressGroup.setVisibility(View.VISIBLE);
            onProgress(OrganizeService.P_DONE, OrganizeService.P_TOTAL, OrganizeService.P_FOLDER);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        OrganizeService.setListener(null);
    }

    @Override
    public void onProgress(int done, int total, String folder) {
        progressGroup.setVisibility(View.VISIBLE);
        progressBar.setProgress(total > 0 ? (int) (done * 100L / total) : 0);
        txtProgress.setText(getString(R.string.organizing_progress, done, total,
                folder == null ? "" : folder));
    }

    @Override
    public void onDone(int moved, int skipped, int failed, boolean stopped) {
        setBusy(false);
        txtProgress.setText(stopped ? getString(R.string.organize_stopped)
                : getString(R.string.organize_done, moved, skipped, failed));
        progressBar.setProgress(100);
        pendingImages = null;
        consentQueue = null;
        loadFolder();
    }

    // ---- Activity results ---------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER) {
            if (resultCode == RESULT_OK && data != null) {
                setFolder(
                        data.getStringExtra(Extras.RESULT_REL_PATH),
                        data.getStringExtra(Extras.RESULT_DATA_DIR),
                        data.getStringExtra(Extras.RESULT_DISPLAY),
                        data.getLongExtra(Extras.RESULT_BUCKET_ID, -1),
                        data.getStringExtra(Extras.VOLUME_NAME));
            }
        } else if (requestCode == REQ_CONSENT) {
            if (resultCode == RESULT_OK) {
                consentIndex++;
                processNextConsent();
            } else {
                setBusy(false);
                pendingImages = null;
                consentQueue = null;
                toast(R.string.organize_stopped);
            }
        }
    }

    // ---- Permissions --------------------------------------------------------

    private void requestNeededPermissions() {
        List<String> req = new ArrayList<>();
        for (String p : PermissionManager.missing(this)) req.add(p);
        if (PermissionManager.needsNotificationPermission(this)) {
            req.add(android.Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!req.isEmpty()) {
            requestPermissions(req.toArray(new String[0]), REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (PermissionManager.hasReadMedia(this)) {
                initFolder();
            } else {
                txtStats.setText(R.string.need_media_permission);
            }
        }
    }

    private void toast(int resId) {
        Toast.makeText(this, resId, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
