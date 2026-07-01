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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads and caches square-ish thumbnails for the preview grid on a small background pool. */
public class ThumbnailLoader {
    private final Context ctx;
    private final ExecutorService pool;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> cache;
    private final int size;

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
        iv.setTag(key);
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        pool.execute(() -> {
            final Bitmap b = decode(img);
            if (b != null) cache.put(key, b);
            main.post(() -> {
                Object tag = iv.getTag();
                if (key.equals(tag) && b != null) {
                    iv.setImageBitmap(b);
                }
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
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > size * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = ctx.getContentResolver().openInputStream(img.readUri())) {
                return BitmapFactory.decodeStream(in, null, opts);
            }
        } catch (Exception e) {
            return null;
        }
    }

    public void shutdown() {
        pool.shutdownNow();
        cache.evictAll();
    }
}
