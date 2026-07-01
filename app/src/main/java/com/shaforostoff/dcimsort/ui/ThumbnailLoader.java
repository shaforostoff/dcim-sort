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
    private final LruCache<Long, Bitmap> cache;
    private final int size;

    public ThumbnailLoader(Context ctx, int sizePx) {
        this.ctx = ctx.getApplicationContext();
        this.size = sizePx;
        this.pool = Executors.newFixedThreadPool(3, ThreadPlanner.backgroundFactory("thumb"));
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        this.cache = new LruCache<Long, Bitmap>(Math.max(4096, maxKb)) {
            @Override protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
    }

    public void load(final MediaImage img, final ImageView iv) {
        iv.setTag(img.id);
        Bitmap cached = cache.get(img.id);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageBitmap(null);
        pool.execute(() -> {
            final Bitmap b = decode(img);
            if (b != null) cache.put(img.id, b);
            main.post(() -> {
                Object tag = iv.getTag();
                if (tag != null && ((Long) tag) == img.id && b != null) {
                    iv.setImageBitmap(b);
                }
            });
        });
    }

    private Bitmap decode(MediaImage img) {
        if (Sdk.atLeastQ()) {
            try {
                return ctx.getContentResolver().loadThumbnail(
                        img.contentUri(), new Size(size, size), null);
            } catch (Exception ignore) {
                // fall through to manual decode
            }
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = ctx.getContentResolver().openInputStream(img.contentUri())) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            int sample = 1;
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > size * 2) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            try (InputStream in = ctx.getContentResolver().openInputStream(img.contentUri())) {
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
