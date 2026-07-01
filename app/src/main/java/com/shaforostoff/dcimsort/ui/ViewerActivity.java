package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.util.Sdk;
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
    private boolean skipFav;

    private Bitmap originalBitmap;
    private Bitmap compressedBitmap;
    private String overlayText;
    private boolean holding;            // finger currently held for compare
    private boolean compressionStarted; // build kicked off once, lazily, on first hold

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
        skipFav = data.skipFav;

        applyBottomInsets();
        loadOriginal();

        if (mode.recompresses()) {
            hint.setVisibility(View.VISIBLE);
            if (skipFav && image.favorite) {
                hint.setText(R.string.favorite_no_compress);
            } else {
                imageView.setCompareEnabled(true);
                imageView.setCompareListener(new ZoomableImageView.CompareListener() {
                    @Override public void onCompareStart() { startCompare(); }
                    @Override public void onCompareEnd() { endCompare(); }
                });
                // Compression is deferred until the user actually holds (see startCompare).
            }
        }
    }

    /** Hold began: show the compressed bitmap, building it on first hold only. */
    private void startCompare() {
        holding = true;
        hint.setVisibility(View.GONE);
        if (compressedBitmap != null) {
            showCompressed();
            return;
        }
        overlay.setText(R.string.compressing);
        overlay.setVisibility(View.VISIBLE);
        if (!compressionStarted) {
            compressionStarted = true;
            buildCompressed();
        }
    }

    /** Hold released: revert to the original. */
    private void endCompare() {
        holding = false;
        if (originalBitmap != null) {
            imageView.setImageBitmapKeepMatrix(originalBitmap);
        }
        overlay.setVisibility(View.GONE);
        hint.setVisibility(View.VISIBLE);
    }

    private void showCompressed() {
        imageView.setImageBitmapKeepMatrix(compressedBitmap);
        if (overlayText != null) overlay.setText(overlayText);
        overlay.setVisibility(View.VISIBLE);
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
                if (holding) showCompressed(); // finger still down → reveal as soon as it's ready
            });
        });
    }

    /** Lift the bottom hint/overlay above the system navigation bar (edge-to-edge on API 35+). */
    private void applyBottomInsets() {
        final int hintBase = Math.round(16 * getResources().getDisplayMetrics().density);
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = bottomInset(insets);
            setBottomMargin(hint, hintBase + bottom);
            setBottomMargin(overlay, bottom);
            return insets;
        });
        root.requestApplyInsets();
    }

    private static int bottomInset(WindowInsets insets) {
        if (Sdk.atLeastR()) {
            return insets.getInsets(WindowInsets.Type.systemBars()).bottom;
        }
        return insets.getSystemWindowInsetBottom();
    }

    private static void setBottomMargin(View v, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            v.setLayoutParams(lp);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (originalBitmap != null) originalBitmap.recycle();
        if (compressedBitmap != null) compressedBitmap.recycle();
    }
}
