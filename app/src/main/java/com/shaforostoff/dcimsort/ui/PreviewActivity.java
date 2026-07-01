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
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.DateRange;
import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
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

    private ListView folderList;
    private GridView photoGrid;
    private TextView status;
    private Button btnSelectToggle;
    private ThumbnailLoader thumbLoader;

    private String relPath, dataDir, volumeName;
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
        status = findViewById(R.id.status);
        btnSelectToggle = findViewById(R.id.btn_select_toggle);

        relPath = getIntent().getStringExtra(Extras.REL_PATH);
        dataDir = getIntent().getStringExtra(Extras.DATA_DIR);
        volumeName = getIntent().getStringExtra(Extras.VOLUME_NAME);
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
            SelectionStore.toggle(openFolderRef.images.get(pos).id);
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
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.estimating);
        folderList.setVisibility(View.GONE);
        photoGrid.setVisibility(View.GONE);

        final MediaRepository repo = new MediaRepository(this);
        final TargetResolver targets = new TargetResolver(
                new GeoExtractor(repo), new PlaceResolver(this, new GeoCache(this)), groupMode);

        executor.execute(() -> {
            final Map<String, PlanFolder> map = new LinkedHashMap<>();
            repo.forEachNewestFirst(relPath, dataDir, volumeName, img -> {
                if (cancelled) return false;
                if (!range.contains(img.dateTakenMillis)) return true;
                String name = targets.folderFor(img);
                PlanFolder pf = map.get(name);
                if (pf == null) {
                    pf = new PlanFolder(name);
                    map.put(name, pf);
                }
                pf.images.add(img);
                pf.currentBytes += img.size;
                return true;
            });
            final List<PlanFolder> list = new ArrayList<>(map.values());
            estimate(list);
            if (cancelled) return;
            main.post(() -> showFolders(list));
        });
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
        if (list.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.preview_empty);
            return;
        }
        if (groupMode == GroupMode.NONE) {
            foldersSkipped = true;
            openFolder(list.get(0));
            return;
        }
        status.setVisibility(View.GONE);
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
        status.setVisibility(View.GONE);
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
        if (thumbLoader != null) thumbLoader.shutdown();
    }
}
