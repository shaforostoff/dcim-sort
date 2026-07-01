package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.GridView;
import android.widget.ListView;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.geo.GeoCache;
import com.shaforostoff.dcimsort.geo.GeoExtractor;
import com.shaforostoff.dcimsort.geo.PlaceResolver;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.work.Recompressor;
import com.shaforostoff.dcimsort.work.TargetResolver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dry-run of the organize plan: groups photos into Place-YYYY-MM folders with counts and an
 * estimated post-compression size. Tap a folder to see its photos; tap a photo to view fullscreen.
 */
public class PreviewActivity extends Activity {

    /** Default bytes-per-megapixel fallbacks when sample calibration is unavailable. */
    private static final double WEBP_BYTES_PER_MP = 0.22 * 1024 * 1024;
    private static final double HEIC_BYTES_PER_MP = 0.12 * 1024 * 1024;
    private static final int SAMPLE_COUNT = 6;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    private ListView folderList;
    private GridView photoGrid;
    private TextView status;
    private ThumbnailLoader thumbLoader;

    private String relPath, dataDir;
    private CompressMode mode;
    private int quality;
    private boolean skipFav;

    private List<PlanFolder> folders;
    private PlanFolder openFolderRef;

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

        relPath = getIntent().getStringExtra(Extras.REL_PATH);
        dataDir = getIntent().getStringExtra(Extras.DATA_DIR);
        mode = CompressMode.fromName(getIntent().getStringExtra(Extras.MODE), CompressMode.NONE);
        quality = getIntent().getIntExtra(Extras.QUALITY, 80);
        skipFav = getIntent().getBooleanExtra(Extras.SKIP_FAV, false);

        float density = getResources().getDisplayMetrics().density;
        thumbLoader = new ThumbnailLoader(this, (int) (110 * density));

        folderList.setOnItemClickListener((p, v, pos, id) -> openFolder(folders.get(pos)));
        photoGrid.setOnItemClickListener((p, v, pos, id) -> openViewer(openFolderRef.images.get(pos)));

        computePlan();
    }

    private void computePlan() {
        status.setVisibility(View.VISIBLE);
        status.setText(R.string.estimating);
        folderList.setVisibility(View.GONE);
        photoGrid.setVisibility(View.GONE);

        final MediaRepository repo = new MediaRepository(this);
        final TargetResolver targets = new TargetResolver(
                new GeoExtractor(repo), new PlaceResolver(this, new GeoCache(this)));
        final Recompressor rc = new Recompressor(this, repo);

        executor.execute(() -> {
            final Map<String, PlanFolder> map = new LinkedHashMap<>();
            repo.forEachNewestFirst(relPath, dataDir, img -> {
                if (cancelled) return false;
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
            estimate(list, rc);
            if (cancelled) return;
            main.post(() -> showFolders(list));
        });
    }

    private void estimate(List<PlanFolder> list, Recompressor rc) {
        if (!mode.recompresses()) {
            for (PlanFolder pf : list) pf.estBytes = pf.currentBytes;
            return;
        }
        double ratio = calibrateRatio(list, rc);
        for (PlanFolder pf : list) {
            long est = 0;
            for (MediaImage img : pf.images) {
                double mp = img.megapixels();
                long e = mp > 0 ? (long) (ratio * mp) : img.size;
                if (e > img.size && img.size > 0) e = img.size;
                est += e;
            }
            pf.estBytes = est;
        }
    }

    /** Sample-encodes a spread of images to derive bytes-per-megapixel for the chosen format. */
    private double calibrateRatio(List<PlanFolder> list, Recompressor rc) {
        List<MediaImage> all = new ArrayList<>();
        for (PlanFolder pf : list) all.addAll(pf.images);
        if (all.isEmpty()) return defaultRatio();

        long sumBytes = 0;
        double sumMp = 0;
        int n = Math.min(SAMPLE_COUNT, all.size());
        int stride = Math.max(1, all.size() / n);
        for (int i = 0; i < all.size() && sumMp < 100; i += stride) {
            if (cancelled) break;
            MediaImage img = all.get(i);
            double mp = img.megapixels();
            if (mp <= 0) continue;
            long bytes = rc.encodedSize(img.contentUri(), mode, quality);
            if (bytes > 0) {
                sumBytes += bytes;
                sumMp += mp;
            }
        }
        return sumMp > 0 ? sumBytes / sumMp : defaultRatio();
    }

    private double defaultRatio() {
        return mode == CompressMode.HEIC ? HEIC_BYTES_PER_MP : WEBP_BYTES_PER_MP;
    }

    private void showFolders(List<PlanFolder> list) {
        folders = list;
        if (list.isEmpty()) {
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.preview_empty);
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
        folderList.setVisibility(View.GONE);
        photoGrid.setVisibility(View.VISIBLE);
        photoGrid.setAdapter(new PhotoAdapter(this, pf.images, thumbLoader));
        setTitle(pf.name);
    }

    private void openViewer(MediaImage img) {
        ViewerData d = new ViewerData();
        d.image = img;
        d.mode = mode;
        d.quality = quality;
        ViewerData.set(d);
        startActivity(new Intent(this, ViewerActivity.class));
    }

    @Override
    public void onBackPressed() {
        if (photoGrid.getVisibility() == View.VISIBLE) {
            photoGrid.setVisibility(View.GONE);
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
