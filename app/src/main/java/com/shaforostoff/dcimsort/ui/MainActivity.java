package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ScrollView;
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
import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.data.SettingsStore;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.util.PermissionManager;
import com.shaforostoff.dcimsort.util.Sdk;
import com.shaforostoff.dcimsort.util.SystemBars;
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
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity implements OrganizeService.Listener {

    private static final int REQ_PERMISSIONS = 10;
    private static final int REQ_PICK_FOLDER = 11;
    private static final int REQ_CONSENT = 12;
    private static final int REQ_PICK_FILES = 13;
    private static final int CONSENT_CHUNK = 480;

    private SettingsStore settings;
    private MediaRepository repo;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    // Views. Radio buttons carry their CompressMode/GroupMode name as android:tag.
    private TextView txtFolder, txtQuality, txtProgress, txtPlan;
    private RadioGroup radioMode, radioGroupMode;
    private LinearLayout qualityGroup, progressGroup, dateRangeGroup;
    private SeekBar seekQuality;
    private CheckBox checkSkipFav, checkSkipLowGain, checkKeepOriginal;
    private Button btnPreview, btnOrganize, btnStop, btnBrowse, btnFiles, btnDateFrom, btnDateTo, btnInfo;
    private ProgressBar progressBar;

    // Current source folder; null when none is selected or in files mode.
    private Bucket folder;

    // Files mode: images picked individually via the system photo picker (no source folder).
    private boolean filesMode;
    private ArrayList<String> pickedUris; // raw picked URIs (resolved lazily by repo)

    // Keep-original: copy into the organized folder instead of moving. Forced on (and locked)
    // when any selected photo has no on-device MediaStore row (e.g. Google Photos cloud pick).
    private boolean userKeepOriginal;
    private boolean keepOriginalForced;

    // Cached folder contents (newest-first) + date-range scoping
    private List<MediaImage> allImages;
    private long folderMinDate, folderMaxDate; // span of dated photos; 0 if none
    private long rangeFrom = Long.MIN_VALUE, rangeTo = Long.MAX_VALUE;
    private int summaryGen;
    private volatile double lastEstimateRatio; // last calibrated bytes/MP; handed to Preview so it needn't re-encode
    // Lazily-calibrated bytes/MP keyed by "mode@quality". Valid only for the current image set, so
    // clearEstimateCache() drops it whenever the folder, picked files, or date range changes.
    private final Map<String, Double> ratioCache = new ConcurrentHashMap<>();

    // Organize job waiting on MediaStore write consent, requested one chunk of URIs at a time.
    private OrganizeRequest pendingRequest;
    private List<List<Uri>> consentQueue;
    private int consentIndex;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SystemBars.padContent(this);

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

    private void bindViews() {
        btnBrowse = findViewById(R.id.btn_browse);
        btnFiles = findViewById(R.id.btn_files);
        txtFolder = findViewById(R.id.txt_folder);
        radioMode = findViewById(R.id.radio_compressmode);
        qualityGroup = findViewById(R.id.quality_group);
        txtQuality = findViewById(R.id.txt_quality);
        seekQuality = findViewById(R.id.seek_quality);
        checkSkipFav = findViewById(R.id.check_skip_fav);
        checkSkipLowGain = findViewById(R.id.check_skip_low_gain);
        checkKeepOriginal = findViewById(R.id.check_keep_original);
        radioGroupMode = findViewById(R.id.radio_groupmode);
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
        btnInfo = findViewById(R.id.btn_info);
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

        // Hide formats this device/flavor can't encode (HEIC needs an HEVC encoder, AVIF libavif or
        // Android 16+, JPEG the full flavor's jpegli).
        for (CompressMode m : CompressMode.values()) {
            if (!Recompressor.canEncode(m)) radioMode.findViewWithTag(m.name()).setVisibility(View.GONE);
        }

        radioMode.setOnCheckedChangeListener((group, checkedId) -> {
            CompressMode mode = currentMode();
            settings.setMode(mode);
            applyModeUi(mode);
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

        // Long-press exposes the threshold behind the checkbox (how much must be saved to be worth it).
        checkSkipLowGain.setOnLongClickListener(v -> {
            showMinGainDialog();
            return true;
        });

        checkKeepOriginal.setOnCheckedChangeListener((b, checked) -> {
            if (!keepOriginalForced) userKeepOriginal = checked;
        });

        radioGroupMode.setOnCheckedChangeListener((group, checkedId) -> {
            settings.setGroupMode(currentGroupMode());
            updateOrganizeButtonLabel();
        });

        btnDateFrom.setOnClickListener(v -> pickDate(true));
        btnDateTo.setOnClickListener(v -> pickDate(false));

        btnPreview.setOnClickListener(v -> openPreview());
        btnOrganize.setOnClickListener(v -> startOrganize());
        btnStop.setOnClickListener(v -> OrganizeService.stop(this));
        btnInfo.setOnClickListener(v -> showInfoDialog());

        setRangeControlsEnabled(false);
        updateDateLabels();
    }

    /** Shows the controls that only matter when recompressing, and loads that format's quality. */
    private void applyModeUi(CompressMode mode) {
        int visibility = mode.recompresses() ? View.VISIBLE : View.GONE;
        qualityGroup.setVisibility(visibility);
        checkSkipLowGain.setVisibility(visibility);
        // Favorites skip only on Android 11+.
        checkSkipFav.setVisibility(Sdk.atLeastR() ? visibility : View.GONE);
        int q = settings.getQuality(mode);
        seekQuality.setProgress(q);
        txtQuality.setText(getString(R.string.quality_label, q));
    }

    /**
     * Overlay behind the skip-low-gain checkbox (long-press): picks the minimum saving, 1–99%, below
     * which compressing isn't worth it. Applied on OK so dragging the bar doesn't re-run estimates.
     */
    private void showMinGainDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_min_gain, null);
        TextView label = content.findViewById(R.id.txt_min_gain);
        SeekBar seek = content.findViewById(R.id.seek_min_gain);
        seek.setProgress(settings.getMinGainPercent() - SizeEstimator.MIN_GAIN_PERCENT_MIN);
        label.setText(getString(R.string.min_gain_label, minGainOf(seek)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                label.setText(getString(R.string.min_gain_label, minGainOf(s)));
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        new AlertDialog.Builder(this)
                .setTitle(R.string.min_gain_title)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    settings.setMinGainPercent(minGainOf(seek));
                    updateSkipLowGainLabel();
                    if (checkSkipLowGain.isChecked()) recomputeSummary();
                })
                .show();
    }

    /** The bar has no min below API 26, so it carries value − MIN_GAIN_PERCENT_MIN. */
    private static int minGainOf(SeekBar seek) {
        return seek.getProgress() + SizeEstimator.MIN_GAIN_PERCENT_MIN;
    }

    /** Shows the configured threshold on the checkbox, since the overlay that sets it is hidden. */
    private void updateSkipLowGainLabel() {
        checkSkipLowGain.setText(getString(R.string.skip_low_gain_with_percent,
                getString(R.string.skip_low_gain), settings.getMinGainPercent()));
    }

    private void showInfoDialog() {
        String text;
        try (Scanner s = new Scanner(getResources().openRawResource(R.raw.help_text), "UTF-8")) {
            text = s.useDelimiter("\\A").hasNext() ? s.next() : "";
        }

        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(padding, padding, padding, padding);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);

        new AlertDialog.Builder(this)
                .setTitle(R.string.info_dialog_title)
                .setView(scroll)
                .setPositiveButton(R.string.close, null)
                .show();
    }

    private void applySavedSettings() {
        CompressMode mode = settings.getMode();
        if (!Recompressor.canEncode(mode)) mode = CompressMode.NONE;
        ((RadioButton) radioMode.findViewWithTag(mode.name())).setChecked(true);
        applyModeUi(mode);
        checkSkipFav.setChecked(settings.getSkipFavorites());
        checkSkipLowGain.setChecked(settings.getSkipLowGain());
        updateSkipLowGainLabel();
        ((RadioButton) radioGroupMode.findViewWithTag(settings.getGroupMode().name())).setChecked(true);
        updateOrganizeButtonLabel();
    }

    /** Without folder grouping the run only recompresses, so label the action "Compress". */
    private void updateOrganizeButtonLabel() {
        btnOrganize.setText(currentGroupMode() == GroupMode.NONE
                ? R.string.compress : R.string.organize);
    }

    private GroupMode currentGroupMode() {
        return GroupMode.fromName(checkedTag(radioGroupMode), GroupMode.PLACE_MONTH);
    }

    private CompressMode currentMode() {
        return CompressMode.fromName(checkedTag(radioMode), CompressMode.NONE);
    }

    private static String checkedTag(RadioGroup group) {
        View checked = group.findViewById(group.getCheckedRadioButtonId());
        return checked != null ? (String) checked.getTag() : null;
    }

    private boolean skipFav() {
        return checkSkipFav.isChecked() && Sdk.atLeastR();
    }

    // ---- Folder + stats -----------------------------------------------------

    private void initFolder() {
        Bucket saved = settings.getSourceFolder();
        if (saved != null) {
            folder = saved;
            applyFolder();
        } else {
            txtPlan.setText(R.string.counting);
            executor.execute(() -> {
                final Bucket b = repo.findDefaultCameraBucket();
                main.post(() -> {
                    if (b != null) {
                        setFolder(b);
                    } else {
                        txtFolder.setText(R.string.no_folder_selected);
                        txtPlan.setText(R.string.no_photos);
                    }
                });
            });
        }
    }

    private void setFolder(Bucket b) {
        folder = b;
        settings.setSourceFolder(b);
        applyFolder();
    }

    private void applyFolder() {
        filesMode = false;
        pickedUris = null;
        keepOriginalForced = false; // folder images are always movable in place
        applyKeepOriginalState();
        dateRangeGroup.setVisibility(View.VISIBLE);
        txtFolder.setText(folder.displayName != null ? folder.displayName
                : (folder.relativePath != null ? folder.relativePath : getString(R.string.no_folder_selected)));
        loadFolder();
    }

    /** Reflects keep-original state: locked-on when forced, else the user's own choice. */
    private void applyKeepOriginalState() {
        checkKeepOriginal.setEnabled(!keepOriginalForced);
        checkKeepOriginal.setChecked(keepOriginalForced || userKeepOriginal);
    }

    /** Gathers the folder's images once, then derives stats, the date span, and the plan summary. */
    private void loadFolder() {
        if (folder == null) {
            txtPlan.setText("");
            return;
        }
        allImages = null;
        clearEstimateCache();
        txtPlan.setText(R.string.counting);
        setRangeControlsEnabled(false);
        final Bucket f = folder;
        executor.execute(() -> {
            final List<MediaImage> imgs = new ArrayList<>();
            repo.forEachNewestFirst(f.relativePath, f.dataDir, f.volumeName, img -> {
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
                if (f != folder) return; // folder changed meanwhile
                allImages = imgs;
                folderMinDate = fMin;
                folderMaxDate = fMax;
                rangeFrom = fMin > 0 ? dayBound(fMin, false) : Long.MIN_VALUE;
                rangeTo = fMax > 0 ? dayBound(fMax, true) : Long.MAX_VALUE;
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
        folder = null;
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
        executor.execute(() -> {
            final List<MediaImage> imgs = repo.fetchByUris(uris);
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

    // ---- Date range ---------------------------------------------------------

    private void setRangeControlsEnabled(boolean enabled) {
        btnDateFrom.setEnabled(enabled);
        btnDateTo.setEnabled(enabled);
    }

    private void updateDateLabels() {
        boolean dated = folderMinDate > 0;
        btnDateFrom.setText(getString(R.string.date_from_label, dated ? formatDay(rangeFrom) : "—"));
        btnDateTo.setText(getString(R.string.date_to_label, dated ? formatDay(rangeTo) : "—"));
    }

    private static String formatDay(long millis) {
        return DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date(millis));
    }

    /** First (or, with {@code end}, last) millisecond of the local day containing {@code millis}. */
    private static long dayBound(long millis, boolean end) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        c.set(Calendar.HOUR_OF_DAY, end ? 23 : 0);
        c.set(Calendar.MINUTE, end ? 59 : 0);
        c.set(Calendar.SECOND, end ? 59 : 0);
        c.set(Calendar.MILLISECOND, end ? 999 : 0);
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
            setRangeDay(isFrom, picked.getTimeInMillis());
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        dlg.setButton(android.content.DialogInterface.BUTTON_NEUTRAL, getString(R.string.today),
                (dialog, which) -> setRangeDay(isFrom, System.currentTimeMillis()));
        dlg.show();
    }

    /** Moves one end of the date range to the day containing {@code millis}, keeping from ≤ to. */
    private void setRangeDay(boolean isFrom, long millis) {
        if (isFrom) {
            rangeFrom = dayBound(millis, false);
            if (rangeTo < rangeFrom) rangeTo = dayBound(millis, true);
        } else {
            rangeTo = dayBound(millis, true);
            if (rangeFrom > rangeTo) rangeFrom = dayBound(millis, false);
        }
        clearEstimateCache(); // date range changed → previously sampled set no longer applies
        updateDateLabels();
        recomputeSummary();
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
        final boolean skipFav = skipFav();
        final boolean skipLowGain = checkSkipLowGain.isChecked();
        final int minGain = settings.getMinGainPercent();
        final int gen = ++summaryGen;

        long originalTotal = 0;
        for (MediaImage m : inRange) originalTotal += m.size;
        if (count == 0 || !mode.recompresses()) {
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
            long est = SizeEstimator.estimateWithRatio(
                    inRange, ratio, mode, skipFav, skipLowGain, minGain);
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
        if (folder == null && !filesMode) {
            toast(R.string.select_folder_first);
            return;
        }
        Intent i = new Intent(this, PreviewActivity.class);
        if (folder != null) {
            i.putExtra(Extras.REL_PATH, folder.relativePath);
            i.putExtra(Extras.DATA_DIR, folder.dataDir);
            i.putExtra(Extras.VOLUME_NAME, folder.volumeName);
        }
        if (filesMode) i.putStringArrayListExtra(Extras.FILE_URIS, pickedUris);
        i.putExtra(Extras.MODE, currentMode().name());
        i.putExtra(Extras.GROUP_MODE, currentGroupMode().name());
        i.putExtra(Extras.QUALITY, seekQuality.getProgress());
        i.putExtra(Extras.SKIP_FAV, skipFav());
        i.putExtra(Extras.SKIP_LOW_GAIN, checkSkipLowGain.isChecked());
        i.putExtra(Extras.MIN_GAIN_PERCENT, settings.getMinGainPercent());
        i.putExtra(Extras.DATE_FROM, rangeFrom);
        i.putExtra(Extras.DATE_TO, rangeTo);
        i.putExtra(Extras.RATIO, lastEstimateRatio); // 0 if not yet calibrated → Preview uses its default
        startActivity(i);
    }

    // ---- Organize -----------------------------------------------------------

    private void startOrganize() {
        if (OrganizeService.RUNNING) return;
        if (allImages == null) {
            toast(folder == null && !filesMode ? R.string.select_folder_first : R.string.counting);
            return;
        }
        // Reuse the cached folder listing, scoped to the selected date range and preview selection.
        final List<MediaImage> imgs = SelectionStore.filter(imagesInRange());
        if (imgs.isEmpty()) {
            toast(R.string.no_photos);
            return;
        }
        setBusy(true);
        OrganizeRequest req = new OrganizeRequest();
        req.images = imgs;
        req.mode = currentMode();
        req.groupMode = currentGroupMode();
        req.quality = seekQuality.getProgress();
        req.skipFavorites = skipFav();
        req.skipLowGain = checkSkipLowGain.isChecked();
        req.minGainPercent = settings.getMinGainPercent();
        req.keepOriginal = checkKeepOriginal.isChecked();
        if (folder != null) {
            req.sourceRelativePath = folder.relativePath;
            req.volumeName = folder.volumeName;
        }
        pendingRequest = req;

        consentQueue = new ArrayList<>();
        consentIndex = 0;
        // Copy mode (keep original) only inserts new files we own, so no consent on originals.
        if (Sdk.atLeastR() && !req.keepOriginal) {
            // Both moving (RELATIVE_PATH update) and recompressing (write a replacement, then delete
            // the original) need WRITE access to each original. We must NOT use createDeleteRequest
            // here: it deletes the originals the instant the user approves — before any replacement
            // is written — so any later failure loses the photo with nothing to show for it. Mover
            // deletes each original itself, only after its replacement has been written and verified.
            List<Uri> uris = new ArrayList<>();
            for (MediaImage m : imgs) if (m.isMovable()) uris.add(m.contentUri());
            for (int i = 0; i < uris.size(); i += CONSENT_CHUNK) {
                consentQueue.add(new ArrayList<>(uris.subList(i, Math.min(i + CONSENT_CHUNK, uris.size()))));
            }
        }
        processNextConsent();
    }

    private void processNextConsent() {
        if (consentIndex >= consentQueue.size()) {
            launchService();
            return;
        }
        try {
            // Always a write grant — never createDeleteRequest, which would delete up front.
            PendingIntent pi = MediaStore.createWriteRequest(
                    getContentResolver(), consentQueue.get(consentIndex));
            startIntentSenderForResult(pi.getIntentSender(), REQ_CONSENT, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            setBusy(false);
            toast(R.string.organize_stopped);
        }
    }

    private void launchService() {
        OrganizeRequest.set(pendingRequest);
        progressGroup.setVisibility(View.VISIBLE);
        btnStop.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        txtProgress.setText(getString(R.string.organizing_progress, 0, pendingRequest.images.size(), ""));
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
    public void onDone(int moved, int skipped, int failed, boolean stopped, List<String> folders) {
        setBusy(false);
        StringBuilder summary = new StringBuilder(stopped ? getString(R.string.organize_stopped)
                : getString(R.string.organize_done, moved, skipped, failed));
        if (folders != null && !folders.isEmpty()) {
            summary.append('\n').append(getString(R.string.organize_saved_to));
            for (String folder : folders) summary.append("\n• ").append(folder);
        }
        txtProgress.setText(summary);
        progressBar.setProgress(100);
        btnStop.setVisibility(View.GONE);
        SelectionStore.clear();
        pendingRequest = null;
        consentQueue = null;
        loadFolder();
    }

    // ---- Activity results ---------------------------------------------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER) {
            if (resultCode == RESULT_OK && data != null) {
                setFolder(new Bucket(
                        data.getLongExtra(Extras.BUCKET_ID, -1),
                        data.getStringExtra(Extras.DISPLAY),
                        data.getStringExtra(Extras.REL_PATH),
                        data.getStringExtra(Extras.DATA_DIR),
                        0,
                        data.getStringExtra(Extras.VOLUME_NAME)));
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
                loadPickedFiles(uris);
            }
        } else if (requestCode == REQ_CONSENT) {
            // pendingRequest is gone if the activity was recreated while the consent dialog showed.
            if (resultCode == RESULT_OK && pendingRequest != null) {
                consentIndex++;
                processNextConsent();
            } else {
                setBusy(false);
                pendingRequest = null;
                consentQueue = null;
                toast(R.string.organize_stopped);
            }
        }
    }

    // ---- Permissions --------------------------------------------------------

    private void requestNeededPermissions() {
        String[] missing = PermissionManager.missing(this);
        if (missing.length > 0) requestPermissions(missing, REQ_PERMISSIONS);
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
