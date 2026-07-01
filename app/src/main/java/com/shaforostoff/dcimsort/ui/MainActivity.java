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
import android.view.WindowInsets;
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
import com.shaforostoff.dcimsort.data.GroupMode;
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
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements OrganizeService.Listener {

    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_PICK_FOLDER = 11;
    private static final int REQ_CONSENT = 12;
    private static final int REQ_PICK_FILES = 13;
    private static final int CONSENT_CHUNK = 480;

    private static final int CONSENT_WRITE = 0;

    private SettingsStore settings;
    private MediaRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Views
    private TextView txtFolder, txtQuality, txtProgress, txtPlan;
    private RadioGroup radioMode;
    private RadioButton radioNone, radioWebp, radioHeic, radioAvif, radioJpeg;
    private RadioGroup radioGroupMode;
    private RadioButton radioGroupNone, radioGroupPlaceMonth, radioGroupPlaceDay;
    private LinearLayout qualityGroup, progressGroup, dateRangeGroup;
    private SeekBar seekQuality;
    private CheckBox checkSkipFav, checkSkipLowGain, checkKeepOriginal;
    private Button btnPreview, btnOrganize, btnStop, btnBrowse, btnFiles, btnDateFrom, btnDateTo;
    private ProgressBar progressBar;

    // Current folder
    private String relPath, dataDir, displayName, volumeName;
    private long bucketId = -1;

    // Files mode: images picked individually via the system photo picker (no source folder).
    private boolean filesMode;
    private ArrayList<String> pickedUris; // raw picked URIs (resolved lazily by repo)

    // Keep-original: copy into the organized folder instead of moving. Forced on (and locked)
    // when any selected photo has no on-device MediaStore row (e.g. Google Photos cloud pick).
    private boolean userKeepOriginal;
    private boolean keepOriginalForced;
    private boolean pendingKeepOriginal;

    // Cached folder contents (newest-first) + date-range scoping
    private List<MediaImage> allImages;
    private long folderMinDate, folderMaxDate; // span of dated photos; 0 if none
    private long rangeFrom = Long.MIN_VALUE, rangeTo = Long.MAX_VALUE;
    private int summaryGen;
    private volatile double lastEstimateRatio; // last calibrated bytes/MP; handed to Preview so it needn't re-encode
    // Lazily-calibrated bytes/MP keyed by "mode@quality". Valid only for the current image set, so
    // clearEstimateCache() drops it whenever the folder, picked files, or date range changes.
    private final Map<String, Double> ratioCache = new ConcurrentHashMap<>();

    // Pending organize job + consent queue
    private List<MediaImage> pendingImages;
    private CompressMode pendingMode;
    private GroupMode pendingGroupMode;
    private int pendingQuality;
    private boolean pendingSkipFav;
    private boolean pendingSkipLowGain;
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
        applySystemBarsInsets();

        settings = new SettingsStore(this);
        repo = new MediaRepository(this);

        bindViews();
        setupControls();
        applySavedSettings();

        if (PermissionManager.hasReadMedia(this)) {
            initFolder();
        } else {
            requestNeededPermissions();
            txtPlan.setText(R.string.need_media_permission);
        }
    }

    private void applySystemBarsInsets() {
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Sdk.atLeastR()) {
                v.setPadding(0, insets.getInsets(WindowInsets.Type.systemBars()).top,
                        0, insets.getInsets(WindowInsets.Type.systemBars()).bottom);
            } else {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0, insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    private void bindViews() {
        btnBrowse = findViewById(R.id.btn_browse);
        btnFiles = findViewById(R.id.btn_files);
        txtFolder = findViewById(R.id.txt_folder);
        radioMode = findViewById(R.id.radio_compressmode);
        radioNone = findViewById(R.id.radio_compressnone);
        radioWebp = findViewById(R.id.radio_compresswebp);
        radioHeic = findViewById(R.id.radio_compressheic);
        radioAvif = findViewById(R.id.radio_compressavif);
        radioJpeg = findViewById(R.id.radio_compressjpeg);
        qualityGroup = findViewById(R.id.quality_group);
        txtQuality = findViewById(R.id.txt_quality);
        seekQuality = findViewById(R.id.seek_quality);
        checkSkipFav = findViewById(R.id.check_skip_fav);
        checkSkipLowGain = findViewById(R.id.check_skip_low_gain);
        checkKeepOriginal = findViewById(R.id.check_keep_original);
        radioGroupMode = findViewById(R.id.radio_groupmode);
        radioGroupNone = findViewById(R.id.radio_groupnone);
        radioGroupPlaceMonth = findViewById(R.id.radio_groupplacemonth);
        radioGroupPlaceDay = findViewById(R.id.radio_groupplaceday);
        dateRangeGroup = findViewById(R.id.date_range_group);
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

        btnFiles.setOnClickListener(v -> {
            if (!PermissionManager.hasReadMedia(this)) {
                requestNeededPermissions();
                return;
            }
            startPickFiles();
        });

        // HEIC only when the device can encode it.
        if (!Recompressor.hasHeicEncoder()) {
            radioHeic.setVisibility(View.GONE);
        }
        // AVIF: Android 16+ platform encoder, or the full flavor's bundled libavif on any version.
        if (!Recompressor.hasAvifEncoder()) {
            radioAvif.setVisibility(View.GONE);
        }
        // JPEG only in the full flavor (jpegli is bundled there).
        if (!Recompressor.hasJpegliEncoder()) {
            radioJpeg.setVisibility(View.GONE);
        }
        // Favorites skip only on Android 11+.
        if (!Sdk.atLeastR()) {
            checkSkipFav.setVisibility(View.GONE);
        }

        radioMode.setOnCheckedChangeListener((group, checkedId) -> {
            CompressMode mode = currentMode();
            settings.setMode(mode);
            qualityGroup.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
            if (Sdk.atLeastR()) {
                checkSkipFav.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
            }
            checkSkipLowGain.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
            if (mode.recompresses()) {
                int q = settings.getQuality(mode);
                seekQuality.setProgress(q);
                txtQuality.setText(getString(R.string.quality_label, q));
            }
            recomputeSummary();
        });

        seekQuality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                txtQuality.setText(getString(R.string.quality_label, progress));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                settings.setQuality(currentMode(), s.getProgress());
                recomputeSummary();
            }
        });

        checkSkipFav.setOnCheckedChangeListener((b, checked) -> {
            settings.setSkipFavorites(checked);
            recomputeSummary();
        });

        checkSkipLowGain.setOnCheckedChangeListener((b, checked) -> {
            settings.setSkipLowGain(checked);
            recomputeSummary();
        });

        checkKeepOriginal.setOnCheckedChangeListener((b, checked) -> {
            if (!keepOriginalForced) userKeepOriginal = checked;
        });

        radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            settings.setGroupMode(currentGroupMode());
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
        if (mode == CompressMode.AVIF && !Recompressor.hasAvifEncoder()) {
            mode = CompressMode.NONE;
        }
        if (mode == CompressMode.JPEG && !Recompressor.hasJpegliEncoder()) {
            mode = CompressMode.NONE;
        }
        switch (mode) {
            case WEBP: radioWebp.setChecked(true); break;
            case HEIC: radioHeic.setChecked(true); break;
            case AVIF: radioAvif.setChecked(true); break;
            case JPEG: radioJpeg.setChecked(true); break;
            default: radioNone.setChecked(true); break;
        }
        qualityGroup.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
        if (Sdk.atLeastR()) {
            checkSkipFav.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
        }
        checkSkipLowGain.setVisibility(mode.recompresses() ? View.VISIBLE : View.GONE);
        int q = settings.getQuality(mode);
        seekQuality.setProgress(q);
        txtQuality.setText(getString(R.string.quality_label, q));
        checkSkipFav.setChecked(settings.getSkipFavorites());
        checkSkipLowGain.setChecked(settings.getSkipLowGain());
        switch (settings.getGroupMode()) {
            case NONE: radioGroupNone.setChecked(true); break;
            case PLACE_DAY: radioGroupPlaceDay.setChecked(true); break;
            default: radioGroupPlaceMonth.setChecked(true); break;
        }
    }

    private GroupMode currentGroupMode() {
        int id = radioGroupMode.getCheckedRadioButtonId();
        if (id == R.id.radio_groupnone) return GroupMode.NONE;
        if (id == R.id.radio_groupplaceday) return GroupMode.PLACE_DAY;
        return GroupMode.PLACE_MONTH;
    }

    private CompressMode currentMode() {
        int id = radioMode.getCheckedRadioButtonId();
        if (id == R.id.radio_compresswebp) return CompressMode.WEBP;
        if (id == R.id.radio_compressheic) return CompressMode.HEIC;
        if (id == R.id.radio_compressavif) return CompressMode.AVIF;
        if (id == R.id.radio_compressjpeg) return CompressMode.JPEG;
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
            txtPlan.setText(R.string.counting);
            executor.execute(() -> {
                final Bucket b = repo.findDefaultCameraBucket();
                main.post(() -> {
                    if (b != null) {
                        setFolder(b.relativePath, b.dataDir, b.displayName, b.id, b.volumeName);
                    } else {
                        txtFolder.setText(R.string.no_folder_selected);
                        txtPlan.setText(R.string.no_photos);
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
        filesMode = false;
        pickedUris = null;
        keepOriginalForced = false; // folder images are always movable in place
        applyKeepOriginalState();
        dateRangeGroup.setVisibility(View.VISIBLE);
        txtFolder.setText(displayName != null ? displayName
                : (relPath != null ? relPath : getString(R.string.no_folder_selected)));
        loadFolder();
    }

    /** Reflects keep-original state: locked-on when forced, else the user's own choice. */
    private void applyKeepOriginalState() {
        if (keepOriginalForced) {
            checkKeepOriginal.setChecked(true);
            checkKeepOriginal.setEnabled(false);
        } else {
            checkKeepOriginal.setEnabled(true);
            checkKeepOriginal.setChecked(userKeepOriginal);
        }
    }

    /** Gathers the folder's images once, then derives stats, the date span, and the plan summary. */
    private void loadFolder() {
        if (relPath == null && dataDir == null) {
            txtPlan.setText("");
            return;
        }
        allImages = null;
        clearEstimateCache();
        txtPlan.setText(R.string.counting);
        setRangeControlsEnabled(false);
        final String rel = relPath, dir = dataDir, vol = volumeName;
        executor.execute(() -> {
            final List<MediaImage> imgs = new ArrayList<>();
            repo.forEachNewestFirst(rel, dir, vol, img -> {
                imgs.add(img);
                return true;
            });
            long min = Long.MAX_VALUE, max = Long.MIN_VALUE;
            for (MediaImage m : imgs) {
                if (m.dateTakenMillis > 0) {
                    min = Math.min(min, m.dateTakenMillis);
                    max = Math.max(max, m.dateTakenMillis);
                }
            }
            final long fMin = (min == Long.MAX_VALUE) ? 0 : min;
            final long fMax = (max == Long.MIN_VALUE) ? 0 : max;
            main.post(() -> {
                if (!sameFolder(rel, dir)) return;
                allImages = imgs;
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

    // ---- File picker (individual files) ------------------------------------

    private void startPickFiles() {
        // Modern photo picker. Local picks resolve to a MediaStore row (movable in place); picks
        // from a cloud provider (e.g. Google Photos) come back as opaque URIs with no MediaStore
        // row — those are handled as copy-only imports, forcing "Keep original" on.
        Intent intent;
        if (Sdk.atLeastT()) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, MediaStore.getPickImagesMaxLimit());
        } else {
            intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("image/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, REQ_PICK_FILES);
    }

    private void loadPickedFiles(List<Uri> uris) {
        if (uris.isEmpty()) return;
        relPath = null; dataDir = null; displayName = null; volumeName = null; bucketId = -1;
        allImages = null;
        clearEstimateCache();
        filesMode = true;
        pickedUris = new ArrayList<>();
        for (Uri u : uris) pickedUris.add(u.toString());
        // No source folder in files mode; date range is implied by the picked set.
        txtFolder.setText("");
        dateRangeGroup.setVisibility(View.GONE);
        // Whole picked set is always in range; no date filtering in files mode.
        rangeFrom = Long.MIN_VALUE;
        rangeTo = Long.MAX_VALUE;
        txtPlan.setText(R.string.counting);
        final List<Uri> fUris = uris;
        executor.execute(() -> {
            final List<MediaImage> imgs = repo.fetchByUris(fUris);
            boolean anyCloud = false;
            for (MediaImage m : imgs) if (!m.isMovable()) { anyCloud = true; break; }
            final boolean forced = anyCloud;
            main.post(() -> {
                allImages = imgs;
                // A non-movable pick (no on-device MediaStore row) can only be copied, never moved.
                keepOriginalForced = forced;
                applyKeepOriginalState();
                if (forced) toast(R.string.keep_original_forced);
                if (imgs.isEmpty()) toast(R.string.no_photos);
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
            clearEstimateCache(); // date range changed → previously sampled set no longer applies
            updateDateLabels();
            recomputeSummary();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, getString(R.string.today),
                (dialog, which) -> {
                    long now = System.currentTimeMillis();
                    if (isFrom) {
                        rangeFrom = startOfDay(now);
                        if (rangeTo < rangeFrom) rangeTo = endOfDay(now);
                    } else {
                        rangeTo = endOfDay(now);
                        if (rangeFrom > rangeTo) rangeFrom = startOfDay(now);
                    }
                    clearEstimateCache(); // date range changed → previously sampled set no longer applies
                    updateDateLabels();
                    recomputeSummary();
                });
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

    /** Drops cached calibration ratios; call whenever the image set changes (folder/files/range). */
    private void clearEstimateCache() {
        ratioCache.clear();
    }

    private void recomputeSummary() {
        if (allImages == null) {
            txtPlan.setText("");
            return;
        }
        final List<MediaImage> inRange = SelectionStore.filter(imagesInRange());
        final int count = inRange.size();
        final CompressMode mode = currentMode();
        final int quality = seekQuality.getProgress();
        final boolean skipFav = checkSkipFav.isChecked() && Sdk.atLeastR();
        final boolean skipLowGain = checkSkipLowGain.isChecked();
        final int gen = ++summaryGen;

        if (count == 0) {
            txtPlan.setText(getString(R.string.plan_summary_exact, 0,
                    Formatter.humanReadableBytes(0)));
            return;
        }
        long originalTotal = 0;
        for (MediaImage m : inRange) originalTotal += m.size;

        if (!mode.recompresses()) {
            txtPlan.setText(getString(R.string.plan_summary_exact, count,
                    Formatter.humanReadableBytes(originalTotal)));
            return;
        }
        final long fOriginalTotal = originalTotal;
        final String cacheKey = mode.name() + "@" + quality;
        final Double cachedRatio = ratioCache.get(cacheKey);
        if (cachedRatio == null) {
            // Only show the "estimating…" placeholder when we actually have to re-encode samples.
            txtPlan.setText(getString(R.string.plan_estimating_from, count,
                    Formatter.humanReadableBytes(fOriginalTotal)));
        }
        final Recompressor rc = new Recompressor(this, repo);
        executor.execute(() -> {
            double ratio;
            if (cachedRatio != null) {
                ratio = cachedRatio;
            } else {
                ratio = SizeEstimator.calibrateRatio(
                        inRange, mode, quality, rc, () -> gen != summaryGen);
                if (gen != summaryGen) return; // stale: don't cache a ratio for a superseded image set
                ratioCache.put(cacheKey, ratio);
            }
            long est = SizeEstimator.estimateWithRatio(inRange, ratio, mode, skipFav, skipLowGain);
            if (gen != summaryGen) return;
            lastEstimateRatio = ratio; // reused by Preview instead of re-encoding samples
            main.post(() -> {
                if (gen == summaryGen) {
                    txtPlan.setText(getString(R.string.plan_summary_from, count,
                            Formatter.humanReadableBytes(fOriginalTotal),
                            Formatter.humanReadableBytes(est)));
                }
            });
        });
    }

    // ---- Preview ------------------------------------------------------------

    private void openPreview() {
        if (relPath == null && dataDir == null && !filesMode) {
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
        if (filesMode) i.putStringArrayListExtra(Extras.FILE_URIS, pickedUris);
        i.putExtra(Extras.DISPLAY, displayName);
        i.putExtra(Extras.MODE, currentMode().name());
        i.putExtra(Extras.GROUP_MODE, currentGroupMode().name());
        i.putExtra(Extras.QUALITY, seekQuality.getProgress());
        i.putExtra(Extras.SKIP_FAV, checkSkipFav.isChecked() && Sdk.atLeastR());
        i.putExtra(Extras.SKIP_LOW_GAIN, checkSkipLowGain.isChecked());
        i.putExtra(Extras.DATE_FROM, rangeFrom);
        i.putExtra(Extras.DATE_TO, rangeTo);
    }

    // ---- Organize -----------------------------------------------------------

    private void startOrganize() {
        if (relPath == null && dataDir == null && allImages == null) {
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
        // Reuse the cached folder listing, scoped to the selected date range and preview selection.
        final List<MediaImage> imgs = SelectionStore.filter(imagesInRange());
        if (imgs.isEmpty()) {
            toast(R.string.no_photos);
            return;
        }
        setBusy(true);
        onImagesGathered(imgs, currentMode(), currentGroupMode(), seekQuality.getProgress(),
                checkSkipFav.isChecked() && Sdk.atLeastR(), checkSkipLowGain.isChecked(),
                relPath, dataDir);
    }

    private void onImagesGathered(List<MediaImage> imgs, CompressMode mode, GroupMode groupMode,
                                  int quality, boolean skipFav, boolean skipLowGain,
                                  String rel, String dir) {
        if (imgs.isEmpty()) {
            setBusy(false);
            toast(R.string.no_photos);
            return;
        }
        pendingImages = imgs;
        pendingMode = mode;
        pendingGroupMode = groupMode;
        pendingQuality = quality;
        pendingSkipFav = skipFav;
        pendingSkipLowGain = skipLowGain;
        pendingRel = rel;
        pendingDir = dir;
        pendingKeepOriginal = checkKeepOriginal.isChecked();

        // Copy mode (keep original) only inserts new files we own, so no consent on originals.
        if (Sdk.atLeastR() && !pendingKeepOriginal) {
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
        for (MediaImage m : imgs) if (m.isMovable()) writeUris.add(m.contentUri());
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
        req.groupMode = pendingGroupMode;
        req.quality = pendingQuality;
        req.skipFavorites = pendingSkipFav;
        req.skipLowGain = pendingSkipLowGain;
        req.keepOriginal = pendingKeepOriginal;
        req.sourceRelativePath = pendingRel;
        req.sourceDataDir = pendingDir;
        req.volumeName = volumeName;
        OrganizeRequest.set(req);

        progressGroup.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        txtProgress.setText(getString(R.string.organizing_progress, 0, pendingImages.size(), ""));
        OrganizeService.start(this);
    }

    private void setBusy(boolean busy) {
        btnOrganize.setEnabled(!busy);
        btnPreview.setEnabled(!busy);
        btnBrowse.setEnabled(!busy);
        btnFiles.setEnabled(!busy);
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
        } else if (allImages != null) {
            recomputeSummary();
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
        btnStop.setVisibility(View.GONE);
        SelectionStore.clear();
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
        } else if (requestCode == REQ_PICK_FILES) {
            if (resultCode == RESULT_OK && data != null) {
                List<Uri> uris = new ArrayList<>();
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++)
                        uris.add(data.getClipData().getItemAt(i).getUri());
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
                if (!uris.isEmpty()) loadPickedFiles(uris);
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
                txtPlan.setText(R.string.need_media_permission);
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
