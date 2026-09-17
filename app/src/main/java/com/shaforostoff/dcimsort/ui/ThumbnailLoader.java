package com.shaforostoff.dcimsort.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.util.Sdk;
import com.shaforostoff.dcimsort.util.ThreadPlanner;

import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads and caches square-ish thumbnails for the preview grid on a small background pool. */
public class ThumbnailLoader {
    private final Context ctx;
    private final ExecutorService pool;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final int size;
    /**
     * The photo each grid view is currently bound to. A fling recycles a view through many photos
     * while its earlier decode is still queued; checking this before decoding means the stale job
     * costs a map lookup instead of a full image decode. Bounded by the grid's recycled view pool.
     */
    private final Map<ImageView, String> bound = new ConcurrentHashMap<>();

    public ThumbnailLoader(Context ctx, int sizePx) {
        this.ctx = ctx.getApplicationContext();
        this.size = sizePx;
        this.pool = Executors.newFixedThreadPool(3, ThreadPlanner.backgroundFactory("thumb"));
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        this.cache = new LruCache<String, Bitmap>(Math.max(4096, maxKb)) {
            @Override protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(final MediaImage img, final ImageView iv) {
        // Key by img.key(), not id: cloud picks all share id = -1 and would otherwise collide.
        final String key = img.key();
        bound.put(iv, key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        pool.execute(() -> {
            if (!key.equals(bound.get(iv))) return; // view was recycled onto another photo
            final Bitmap b = decode(img);
            if (b == null) return;
            cache.put(key, b);
            main.post(() -> {
                if (key.equals(bound.get(iv))) iv.setImageBitmap(b);
            });
        });
    }

    private Bitmap decode(MediaImage img) {
        // readUri(): the MediaStore row for normal images, or the picker source URI for cloud picks.
        if (Sdk.atLeastQ()) {
            try {
                return ctx.getContentResolver().loadThumbnail(
                        img.readUri(), new Size(size, size), null);
            } catch (Exception ignore) {
                // fall through to manual decode
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(img.readUri())) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(Math.max(bounds.outWidth, bounds.outHeight));
            try (InputStream in = ctx.getContentResolver().openInputStream(img.readUri())) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Smallest power-of-two subsample whose result still covers the thumbnail slot. Halving only
     * while the next step would still be big enough keeps every thumbnail in [size, 2 * size) —
     * the previous bound allowed up to twice that edge, i.e. four times the bytes per cache entry.
     */
    private int sampleSize(int longestSide) {
        int sample = 1;
        while (longestSide / (sample * 2) >= size) sample *= 2;
        return sample;
    }

    public void shutdown() {
        pool.shutdownNow();
        bound.clear();
        cache.evictAll();
    }
}
