package com.shaforostoff.dcimsort.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.util.SystemBars;
import com.shaforostoff.dcimsort.work.Recompressor;

import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fullscreen zoomable photo viewer with hold-to-compare against the compressed version. */
public class ViewerActivity extends Activity {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private ZoomableImageView imageView;
    private TextView overlay, hint;
    private CheckBox btnExclude;

    private List<MediaImage> images;
    private int currentIndex;
    private MediaImage image;
    private CompressMode mode;
    private int quality;
    private boolean skipFav;

    private Recompressor rc;

    private Bitmap originalBitmap;
    private Bitmap compressedBitmap;
    private String overlayText;
    private boolean holding;            // finger currently held for compare
    private boolean compressionStarted; // build kicked off once, lazily, on first hold
    private int generation;             // incremented on navigation to cancel stale async tasks

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);
        imageView = findViewById(R.id.image);
        overlay = findViewById(R.id.overlay);
        hint = findViewById(R.id.hint);
        btnExclude = findViewById(R.id.btn_exclude);

        ViewerData data = ViewerData.take();
        if (data == null || data.images == null || data.index < 0 || data.index >= data.images.size()) {
            finish();
            return;
        }
        images = data.images;
        currentIndex = data.index;
        image = images.get(currentIndex);
        mode = data.mode != null ? data.mode : CompressMode.NONE;
        quality = data.quality;
        skipFav = data.skipFav;
        rc = new Recompressor(this, new MediaRepository(this));

        // Swipe navigation between photos (only when there are multiple images).
        if (images.size() > 1) {
            imageView.setNavigationListener(new ZoomableImageView.NavigationListener() {
                @Override public void onSwipePrev() { navigateTo(currentIndex - 1); }
                @Override public void onSwipeNext() { navigateTo(currentIndex + 1); }
            });
        }

        // Include checkbox.
        btnExclude.setOnClickListener(v -> SelectionStore.toggle(image.key()));

        if (mode.recompresses()) {
            // Set listener once; it captures fields by reference so navigation updates it implicitly.
            imageView.setCompareListener(new ZoomableImageView.CompareListener() {
                @Override public void onCompareStart() { startCompare(); }
                @Override public void onCompareEnd() { endCompare(); }
            });
        }

        applyBottomInsets();
        showImage();
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
        final int maxDim = screenMaxDim();
        final MediaImage target = image;
        final int gen = generation;
        executor.execute(() -> {
            final Bitmap bmp = rc.decodeOriented(target.readUri(), maxDim);
            main.post(() -> {
                if (gen != generation) {
                    if (bmp != null) bmp.recycle();
                    return;
                }
                if (bmp == null) {
                    finish();
                    return;
                }
                if (originalBitmap != null) originalBitmap.recycle();
                originalBitmap = bmp;
                imageView.setImageFitted(bmp);
            });
        });
    }

    private void buildCompressed() {
        final MediaImage target = image;
        final int gen = generation;
        final int maxDim = screenMaxDim();
        // buildCompressed() is called from the main thread, so the displayed original (if any) is
        // settled here. Decoding the temp straight to the original's long side means the compare
        // bitmap is allocated about the size we actually show, instead of being decoded large and
        // then rescaled — which held a third full-resolution bitmap alongside the other two.
        final Bitmap shown = originalBitmap;
        final int want = shown != null ? Math.max(shown.getWidth(), shown.getHeight()) : maxDim;
        executor.execute(() -> {
            File temp = rc.compressToTemp(target.readUri(), mode, quality);
            if (temp == null) return;
            long compSize = temp.length();
            // Never decode the temp at full resolution: it is only shown in the compare
            // overlay, which is bounded by screenMaxDim() just like the original.
            Bitmap bmp = rc.decodeOriented(Uri.fromFile(temp), want);
            temp.delete();
            if (bmp == null) return;
            final long fcompSize = compSize;
            final Bitmap fbmp = bmp;
            main.post(() -> {
                if (gen != generation) {
                    fbmp.recycle();
                    return;
                }
                // The matrix maps identically only at the original's pixel size; the decode normally
                // lands there, so this is a backstop for rounding, even-cropped HEIF output and the
                // legacy (pre-API 28) sampled decode.
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
                        Formatter.humanReadableBytes(target.size),
                        Formatter.humanReadableBytes(fcompSize));
                if (holding) showCompressed(); // finger still down → reveal as soon as it's ready
            });
        });
    }

    private void navigateTo(int index) {
        if (index < 0 || index >= images.size()) return;
        currentIndex = index;
        image = images.get(currentIndex);
        showImage();
    }

    /** Resets per-image state (compare, hint, include checkbox) for {@link #image} and loads it. */
    private void showImage() {
        generation++; // cancels async work still running for the previous image
        holding = false;
        compressionStarted = false;
        if (compressedBitmap != null) {
            compressedBitmap.recycle();
            compressedBitmap = null;
        }
        overlayText = null;
        overlay.setVisibility(View.GONE);

        if (mode.recompresses()) {
            // Compression itself is deferred until the user actually holds (see startCompare).
            boolean fav = skipFav && image.favorite;
            hint.setVisibility(View.VISIBLE);
            hint.setText(fav ? R.string.favorite_no_compress : R.string.hold_to_compare);
            imageView.setCompareEnabled(!fav);
        }

        btnExclude.setChecked(SelectionStore.isSelected(image.key()));
        loadOriginal();
    }

    /** Lift the bottom hint/overlay/button above the system navigation bar (edge-to-edge on API 35+). */
    private void applyBottomInsets() {
        final int base16 = Math.round(16 * getResources().getDisplayMetrics().density);
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = SystemBars.bottom(insets);
            setMargin(hint, false, base16 + bottom);
            setMargin(overlay, false, bottom);
            setMargin(btnExclude, true, base16 + SystemBars.top(insets));
            return insets;
        });
        root.requestApplyInsets();
    }

    private static void setMargin(View v, boolean top, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if ((top ? lp.topMargin : lp.bottomMargin) == margin) return;
        if (top) lp.topMargin = margin;
        else lp.bottomMargin = margin;
        v.setLayoutParams(lp);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        if (originalBitmap != null) originalBitmap.recycle();
        if (compressedBitmap != null) compressedBitmap.recycle();
    }
}
