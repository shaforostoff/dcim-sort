package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.DateRange;
import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.geo.CoordCache;
import com.shaforostoff.dcimsort.geo.GeoCache;
import com.shaforostoff.dcimsort.geo.GeoExtractor;
import com.shaforostoff.dcimsort.geo.PlaceResolver;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.work.SizeEstimator;
import com.shaforostoff.dcimsort.work.TargetResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dry-run of the organize plan: groups photos into Place-YYYY-MM folders with counts and an
 * estimated post-compression size. The size uses the bytes-per-megapixel ratio the main screen
 * already calibrated (passed in via Intent) — Preview never re-encodes to estimate. Actual
 * re-encoding only happens in the viewer's hold-to-compare gesture. Tap a folder to see its
 * photos; tap a photo to view fullscreen.
 */
public class PreviewActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;
    private volatile java.util.concurrent.ExecutorService geoPool;

    private ListView folderList;
    private GridView photoGrid;
    private View statusGroup;
    private TextView status;
    private ProgressBar progress;
    private Button btnSelectToggle;
    private ThumbnailLoader thumbLoader;

    private String relPath, dataDir, volumeName;
    private java.util.ArrayList<String> fileUris; // files mode: picked URIs instead of a folder query
    private CompressMode mode;
    private GroupMode groupMode;
    private int quality;
    private boolean skipFav;
    private DateRange range;
    private double estimateRatio; // bytes/MP from the plan summary; 0 → fall back to the default

    private List<PlanFolder> folders;
    private PlanFolder openFolderRef;
    private boolean foldersSkipped;

    private static final class PlanFolder {
        final String name;
        final List<MediaImage> images = new ArrayList<>();
        long currentBytes = 0;
        long estBytes = -1;
        PlanFolder(String name) { this.name = name; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);
        folderList = findViewById(R.id.folder_list);
        photoGrid = findViewById(R.id.photo_grid);
        statusGroup = findViewById(R.id.status_group);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        btnSelectToggle = findViewById(R.id.btn_select_toggle);

        relPath = getIntent().getStringExtra(Extras.REL_PATH);
        dataDir = getIntent().getStringExtra(Extras.DATA_DIR);
        volumeName = getIntent().getStringExtra(Extras.VOLUME_NAME);
        fileUris = getIntent().getStringArrayListExtra(Extras.FILE_URIS);
        mode = CompressMode.fromName(getIntent().getStringExtra(Extras.MODE), CompressMode.NONE);
        groupMode = GroupMode.fromName(getIntent().getStringExtra(Extras.GROUP_MODE), GroupMode.PLACE_MONTH);
        quality = getIntent().getIntExtra(Extras.QUALITY, 80);
        skipFav = getIntent().getBooleanExtra(Extras.SKIP_FAV, false);
        long from = getIntent().getLongExtra(Extras.DATE_FROM, Long.MIN_VALUE);
        long to = getIntent().getLongExtra(Extras.DATE_TO, Long.MAX_VALUE);
        range = new DateRange(from, to);
        estimateRatio = getIntent().getDoubleExtra(Extras.RATIO, 0);

        float density = getResources().getDisplayMetrics().density;
        thumbLoader = new ThumbnailLoader(this, (int) (110 * density));

        folderList.setOnItemClickListener((p, v, pos, id) -> openFolder(folders.get(pos)));
        photoGrid.setOnItemClickListener((p, v, pos, id) -> openViewer(openFolderRef.images.get(pos)));
        photoGrid.setOnItemLongClickListener((p, v, pos, id) -> {
            SelectionStore.toggle(openFolderRef.images.get(pos).key());
            refreshGrid();
            return true;
        });
        btnSelectToggle.setOnClickListener(v -> {
            if (SelectionStore.allSelected(openFolderRef.images)) {
                SelectionStore.deselectAll(openFolderRef.images);
            } else {
                SelectionStore.selectAll(openFolderRef.images);
            }
            refreshGrid();
        });

        computePlan();
    }

    private void refreshGrid() {
        ((BaseAdapter) photoGrid.getAdapter()).notifyDataSetChanged();
        boolean allSel = SelectionStore.allSelected(openFolderRef.images);
        btnSelectToggle.setText(allSel ? R.string.select_none : R.string.select_all);
    }

    private void computePlan() {
        statusGroup.setVisibility(View.VISIBLE);
        status.setText(R.string.estimating);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);
        folderList.setVisibility(View.GONE);
        photoGrid.setVisibility(View.GONE);

        final MediaRepository repo = new MediaRepository(this);
        final GeoCache cache = new GeoCache(this);
        final CoordCache coordCache = new CoordCache(this);
        final TargetResolver targets = new TargetResolver(
                new GeoExtractor(repo), new PlaceResolver(this, cache), groupMode, coordCache);

        executor.execute(() -> {
            try {
                // Phase 1: collect rows that pass the date filter (fast — just MediaStore reads, no
                // geocoding) so we have a total to drive the progress bar.
                final List<MediaImage> images = new ArrayList<>();
                if (fileUris != null && !fileUris.isEmpty()) {
                    List<android.net.Uri> uris = new ArrayList<>(fileUris.size());
                    for (String s : fileUris) uris.add(android.net.Uri.parse(s));
                    for (MediaImage img : repo.fetchByUris(uris)) {
                        if (range.contains(img.dateTakenMillis)) images.add(img);
                    }
                } else {
                    repo.forEachNewestFirst(relPath, dataDir, volumeName, img -> {
                        if (cancelled) return false;
                        if (range.contains(img.dateTakenMillis)) images.add(img);
                        return true;
                    });
                }
                if (cancelled) return;

                final int total = images.size();
                main.post(() -> startProgress(total));

                // Phase 2: resolve each image's folder name in parallel. EXIF reads and geocoder
                // lookups are I/O/IPC-bound and independent, so a small pool overlaps them; results
                // go into an indexed array so grouping order stays exactly newest-first.
                final String[] names = new String[total];
                final java.util.concurrent.atomic.AtomicInteger doneCount =
                        new java.util.concurrent.atomic.AtomicInteger();
                final int step = Math.max(1, total / 100); // cap UI updates at ~100
                int workers = Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() * 2));
                geoPool = new java.util.concurrent.ThreadPoolExecutor(
                        workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                        new java.util.concurrent.LinkedBlockingQueue<>(),
                        com.shaforostoff.dcimsort.util.ThreadPlanner.backgroundFactory("preview-geo"));
                for (int i = 0; i < total; i++) {
                    final int idx = i;
                    final MediaImage img = images.get(i);
                    geoPool.execute(() -> {
                        if (cancelled) return;
                        names[idx] = targets.folderFor(img);
                        int d = doneCount.incrementAndGet();
                        if (d % step == 0 || d == total) {
                            main.post(() -> updateProgress(d, total));
                        }
                    });
                }
                geoPool.shutdown();
                try {
                    geoPool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    geoPool.shutdownNow();
                }
                if (cancelled) return;

                // Phase 3: group serially in original order using the resolved names.
                final Map<String, PlanFolder> map = new LinkedHashMap<>();
                for (int i = 0; i < total; i++) {
                    MediaImage img = images.get(i);
                    PlanFolder pf = map.get(names[i]);
                    if (pf == null) {
                        pf = new PlanFolder(names[i]);
                        map.put(names[i], pf);
                    }
                    pf.images.add(img);
                    pf.currentBytes += img.size;
                }

                final List<PlanFolder> list = new ArrayList<>(map.values());
                estimate(list);
                if (cancelled) return;
                main.post(() -> showFolders(list));
            } finally {
                // Persist whatever was resolved on EVERY exit path — including Back mid-flight —
                // so partial cold-cache work (resolved places + per-image GPS) survives and the
                // next Preview reuses it instead of starting cold again.
                if (geoPool != null) geoPool.shutdownNow();
                cache.flush();
                coordCache.flush();
            }
        });
    }

    private void startProgress(int total) {
        if (total <= 0) return;
        progress.setIndeterminate(false);
        progress.setMax(total);
        progress.setProgress(0);
        status.setText(getString(R.string.estimating_progress, 0, total));
    }

    private void updateProgress(int done, int total) {
        progress.setProgress(done);
        status.setText(getString(R.string.estimating_progress, done, total));
    }

    private void estimate(List<PlanFolder> list) {
        if (!mode.recompresses()) {
            for (PlanFolder pf : list) pf.estBytes = pf.currentBytes;
            return;
        }
        // Reuse the ratio the main screen already calibrated; no re-encoding here.
        double ratio = estimateRatio > 0 ? estimateRatio : SizeEstimator.defaultRatio(mode);
        for (PlanFolder pf : list) {
            pf.estBytes = SizeEstimator.estimateWithRatio(pf.images, ratio, mode, skipFav);
        }
    }

    private void showFolders(List<PlanFolder> list) {
        folders = list;
        progress.setVisibility(View.GONE);
        if (list.isEmpty()) {
            statusGroup.setVisibility(View.VISIBLE);
            status.setText(R.string.preview_empty);
            return;
        }
        if (groupMode == GroupMode.NONE) {
            foldersSkipped = true;
            openFolder(list.get(0));
            return;
        }
        statusGroup.setVisibility(View.GONE);
        photoGrid.setVisibility(View.GONE);
        folderList.setVisibility(View.VISIBLE);

        List<String> rows = new ArrayList<>();
        for (PlanFolder pf : list) {
            rows.add(getString(R.string.folder_summary, pf.name, pf.images.size(),
                    Formatter.humanReadableBytes(pf.estBytes)));
        }
        folderList.setAdapter(new ArrayAdapter<>(
                this, android.R.layout.simple_list_item_1, rows));
        setTitle(R.string.title_preview);
    }

    private void openFolder(PlanFolder pf) {
        openFolderRef = pf;
        statusGroup.setVisibility(View.GONE);
        folderList.setVisibility(View.GONE);
        photoGrid.setVisibility(View.VISIBLE);
        photoGrid.setAdapter(new PhotoAdapter(this, pf.images, thumbLoader));
        btnSelectToggle.setVisibility(View.VISIBLE);
        refreshGrid();
        setTitle(pf.name != null ? pf.name : getString(R.string.title_preview));
    }

    private void openViewer(MediaImage img) {
        ViewerData d = new ViewerData();
        d.image = img;
        d.mode = mode;
        d.quality = quality;
        d.skipFav = skipFav;
        ViewerData.set(d);
        startActivity(new Intent(this, ViewerActivity.class));
    }

    @Override
    public void onBackPressed() {
        if (photoGrid.getVisibility() == View.VISIBLE && !foldersSkipped) {
            photoGrid.setVisibility(View.GONE);
            btnSelectToggle.setVisibility(View.GONE);
            folderList.setVisibility(View.VISIBLE);
            openFolderRef = null;
            setTitle(R.string.title_preview);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cancelled = true;
        executor.shutdownNow();
        if (geoPool != null) geoPool.shutdownNow();
        if (thumbLoader != null) thumbLoader.shutdown();
    }
}
