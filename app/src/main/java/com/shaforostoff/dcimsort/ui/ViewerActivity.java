package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.work.Recompressor;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fullscreen zoomable photo viewer with hold-to-compare against the compressed version. */
public class ViewerActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ZoomableImageView imageView;
    private TextView overlay, hint;

    private MediaImage image;
    private CompressMode mode;
    private int quality;

    private Bitmap originalBitmap;
    private Bitmap compressedBitmap;
    private String overlayText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        imageView = findViewById(R.id.image);
        overlay = findViewById(R.id.overlay);
        hint = findViewById(R.id.hint);

        ViewerData data = ViewerData.take();
        if (data == null || data.image == null) {
            finish();
            return;
        }
        image = data.image;
        mode = data.mode != null ? data.mode : CompressMode.NONE;
        quality = data.quality;

        loadOriginal();

        if (mode.recompresses()) {
            hint.setVisibility(View.VISIBLE);
            imageView.setCompareEnabled(true);
            imageView.setCompareListener(new ZoomableImageView.CompareListener() {
                @Override public void onCompareStart() {
                    if (compressedBitmap != null) {
                        imageView.setImageBitmapKeepMatrix(compressedBitmap);
                        if (overlayText != null) overlay.setText(overlayText);
                        overlay.setVisibility(View.VISIBLE);
                        hint.setVisibility(View.GONE);
                    }
                }
                @Override public void onCompareEnd() {
                    if (originalBitmap != null) {
                        imageView.setImageBitmapKeepMatrix(originalBitmap);
                    }
                    overlay.setVisibility(View.GONE);
                }
            });
            buildCompressed();
        }
    }

    private int screenMaxDim() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screen = Math.max(dm.widthPixels, dm.heightPixels);
        // Allow some extra detail for zoom-in, but bounded to avoid OOM.
        return Math.max(2048, Math.min(screen * 2, 4096));
    }

    private void loadOriginal() {
        final MediaRepository repo = new MediaRepository(this);
        final Recompressor rc = new Recompressor(this, repo);
        final int maxDim = screenMaxDim();
        executor.execute(() -> {
            final Bitmap bmp = rc.decodeOriented(image.contentUri(), maxDim);
            main.post(() -> {
                if (bmp == null) {
                    finish();
                    return;
                }
                originalBitmap = bmp;
                imageView.setImageFitted(bmp);
            });
        });
    }

    private void buildCompressed() {
        final MediaRepository repo = new MediaRepository(this);
        final Recompressor rc = new Recompressor(this, repo);
        executor.execute(() -> {
            File temp = rc.compressToTemp(image.contentUri(), mode, quality);
            if (temp == null) return;
            long compSize = temp.length();
            Bitmap bmp = BitmapFactory.decodeFile(temp.getAbsolutePath());
            temp.delete();
            if (bmp == null) return;
            final long fcompSize = compSize;
            final Bitmap fbmp = bmp;
            main.post(() -> {
                // Match the displayed original's pixel dimensions so the matrix maps identically.
                Bitmap toUse = fbmp;
                if (originalBitmap != null
                        && (fbmp.getWidth() != originalBitmap.getWidth()
                        || fbmp.getHeight() != originalBitmap.getHeight())) {
                    toUse = Bitmap.createScaledBitmap(
                            fbmp, originalBitmap.getWidth(), originalBitmap.getHeight(), true);
                    if (toUse != fbmp) fbmp.recycle();
                }
                compressedBitmap = toUse;
                overlayText = getString(R.string.compare_overlay,
                        Formatter.humanReadableBytes(image.size),
                        Formatter.humanReadableBytes(fcompSize));
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (originalBitmap != null) originalBitmap.recycle();
        if (compressedBitmap != null) compressedBitmap.recycle();
    }
}
