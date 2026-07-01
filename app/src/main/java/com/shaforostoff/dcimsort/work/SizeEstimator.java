package com.shaforostoff.dcimsort.work;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.List;

/**
 * Estimates post-compression sizes without re-encoding everything: sample-encodes a spread of
 * images to derive a bytes-per-megapixel ratio, then scales each image by its megapixels (clamped
 * to its current size). Shared by the main-screen plan summary and the Preview breakdown.
 */
public final class SizeEstimator {
    private SizeEstimator() {}

    /** Coarse fallbacks (bytes per megapixel) used when on-device calibration is unavailable. */
    public static final double WEBP_BYTES_PER_MP = 0.22 * 1024 * 1024;
    public static final double HEIC_BYTES_PER_MP = 0.12 * 1024 * 1024;
    public static final double AVIF_BYTES_PER_MP = 0.10 * 1024 * 1024;

    private static final int SAMPLE_COUNT = 6;

    /** Cooperative cancellation so stale/aborted estimates stop quickly. */
    public interface Cancel {
        boolean cancelled();
    }

    /** Sample-encodes a spread of images to derive bytes-per-megapixel for the chosen format. */
    public static double calibrateRatio(List<MediaImage> images, CompressMode mode, int quality,
                                        Recompressor rc, Cancel cancel) {
        if (images == null || images.isEmpty()) return defaultRatio(mode);
        long sumBytes = 0;
        double sumMp = 0;
        int n = Math.min(SAMPLE_COUNT, images.size());
        int stride = Math.max(1, images.size() / n);
        for (int i = 0; i < images.size() && sumMp < 100; i += stride) {
            if (cancel != null && cancel.cancelled()) break;
            MediaImage img = images.get(i);
            double mp = img.megapixels();
            if (mp <= 0) continue;
            long bytes = rc.encodedSize(img.contentUri(), mode, quality);
            if (bytes > 0) {
                sumBytes += bytes;
                sumMp += mp;
            }
        }
        return sumMp > 0 ? sumBytes / sumMp : defaultRatio(mode);
    }

    /** Total estimated bytes for {@code images} using a precomputed ratio. Honors skip-favorites. */
    public static long estimateWithRatio(List<MediaImage> images, double ratio,
                                         CompressMode mode, boolean skipFav) {
        if (!mode.recompresses()) {
            long sum = 0;
            for (MediaImage m : images) sum += m.size;
            return sum;
        }
        long est = 0;
        for (MediaImage m : images) {
            if (skipFav && m.favorite) {
                est += m.size; // favorites are moved untouched, not compressed
                continue;
            }
            double mp = m.megapixels();
            long e = mp > 0 ? (long) (ratio * mp) : m.size;
            if (e > m.size && m.size > 0) e = m.size;
            est += e;
        }
        return est;
    }

    /** Convenience: calibrate then estimate over the same image set. */
    public static long estimateTotal(List<MediaImage> images, CompressMode mode, int quality,
                                     boolean skipFav, Recompressor rc, Cancel cancel) {
        if (!mode.recompresses()) {
            long sum = 0;
            for (MediaImage m : images) sum += m.size;
            return sum;
        }
        double ratio = calibrateRatio(images, mode, quality, rc, cancel);
        return estimateWithRatio(images, ratio, mode, skipFav);
    }

    public static double defaultRatio(CompressMode mode) {
        switch (mode) {
            case HEIC: return HEIC_BYTES_PER_MP;
            case AVIF: return AVIF_BYTES_PER_MP;
            default: return WEBP_BYTES_PER_MP;
        }
    }
}
