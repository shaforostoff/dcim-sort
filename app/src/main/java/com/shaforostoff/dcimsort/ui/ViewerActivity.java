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
import android.widget.CheckBox;
import android.widget.TextView;

import com.shaforostoff.dcimsort.R;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Formatter;
import com.shaforostoff.dcimsort.util.Sdk;
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
        if (data == null || data.image == null) {
            finish();
            return;
        }
        images = data.images;
        currentIndex = data.index;
        image = (images != null && currentIndex >= 0 && currentIndex < images.size())
                ? images.get(currentIndex) : data.image;
        mode = data.mode != null ? data.mode : CompressMode.NONE;
        quality = data.quality;
        skipFav = data.skipFav;

        // Swipe navigation between photos (only when there are multiple images).
        if (images != null && images.size() > 1) {
            imageView.setNavigationListener(new ZoomableImageView.NavigationListener() {
                @Override public void onSwipePrev() { navigateTo(currentIndex - 1); }
                @Override public void onSwipeNext() { navigateTo(currentIndex + 1); }
            });
        }

        // Include checkbox.
        updateExcludeButton();
        btnExclude.setOnClickListener(v -> SelectionStore.toggle(image.key()));

        if (mode.recompresses()) {
            // Set listener once; it captures fields by reference so navigation updates it implicitly.
            imageView.setCompareListener(new ZoomableImageView.CompareListener() {
                @Override public void onCompareStart() { startCompare(); }
                @Override public void onCompareEnd() { endCompare(); }
            });
            hint.setVisibility(View.VISIBLE);
            if (skipFav && image.favorite) {
                hint.setText(R.string.favorite_no_compress);
            } else {
                imageView.setCompareEnabled(true);
                // Compression is deferred until the user actually holds (see startCompare).
            }
        }

        applyBottomInsets();
        loadOriginal();
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
        final MediaRepository repo = new MediaRepository(this);
        final Recompressor rc = new Recompressor(this, repo);
        final MediaImage target = image;
        final int gen = generation;
        final int maxDim = screenMaxDim();
        executor.execute(() -> {
            File temp = rc.compressToTemp(target.readUri(), mode, quality);
            if (temp == null) return;
            long compSize = temp.length();
            // Never decode the temp at full resolution: it is only shown in the compare
            // overlay, which is bounded by screenMaxDim() just like the original.
            Bitmap bmp = decodeSampled(temp.getAbsolutePath(), maxDim);
            temp.delete();
            if (bmp == null) return;
            final long fcompSize = compSize;
            final Bitmap fbmp = bmp;
            main.post(() -> {
                if (gen != generation) {
                    fbmp.recycle();
                    return;
                }
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
                        Formatter.humanReadableBytes(target.size),
                        Formatter.humanReadableBytes(fcompSize));
                if (holding) showCompressed(); // finger still down → reveal as soon as it's ready
            });
        });
    }

    /** Decode a local file downsampled so its long side stays close to maxLongSide. */
    private static Bitmap decodeSampled(String path, int maxLongSide) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        // Halve only while the result still covers maxLongSide, so the compare bitmap is
        // never upscaled back to the original's pixel size.
        while (maxLongSide > 0 && longest / (sample * 2) >= maxLongSide) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(path, opts);
    }

    private void navigateTo(int index) {
        if (images == null || index < 0 || index >= images.size()) return;
        generation++;
        currentIndex = index;
        image = images.get(currentIndex);

        // Reset compare state for the new image.
        holding = false;
        compressionStarted = false;
        if (compressedBitmap != null) {
            compressedBitmap.recycle();
            compressedBitmap = null;
        }
        overlayText = null;
        overlay.setVisibility(View.GONE);

        if (mode.recompresses()) {
            hint.setVisibility(View.VISIBLE);
            if (skipFav && image.favorite) {
                hint.setText(R.string.favorite_no_compress);
                imageView.setCompareEnabled(false);
            } else {
                hint.setText(R.string.hold_to_compare);
                imageView.setCompareEnabled(true);
            }
        }

        updateExcludeButton();
        loadOriginal();
    }

    private void updateExcludeButton() {
        btnExclude.setChecked(SelectionStore.isSelected(image.key()));
    }

    /** Lift the bottom hint/overlay/button above the system navigation bar (edge-to-edge on API 35+). */
    private void applyBottomInsets() {
        final int base16 = Math.round(16 * getResources().getDisplayMetrics().density);
        View root = findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom = bottomInset(insets);
            setBottomMargin(hint, base16 + bottom);
            setBottomMargin(overlay, bottom);
            setTopMargin(btnExclude, base16 + topInset(insets));
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

    private static int topInset(WindowInsets insets) {
        if (Sdk.atLeastR()) {
            return insets.getInsets(WindowInsets.Type.systemBars()).top;
        }
        return insets.getSystemWindowInsetTop();
    }

    private static void setBottomMargin(View v, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp.bottomMargin != margin) {
            lp.bottomMargin = margin;
            v.setLayoutParams(lp);
        }
    }

    private static void setTopMargin(View v, int margin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp.topMargin != margin) {
            lp.topMargin = margin;
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
