package com.shaforostoff.dcimsort.work;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    public static final double JPEG_BYTES_PER_MP = 0.30 * 1024 * 1024;

    private static final int SAMPLE_COUNT = 4;

    /**
     * Calibration encodes at a reduced resolution: bytes-per-megapixel is roughly
     * resolution-invariant, so a smaller decode/encode gives nearly the same ratio far faster and
     * with much less RAM than the full-resolution output path ({@code Recompressor.MAX_LONG_SIDE}).
     */
    private static final int CALIBRATION_LONG_SIDE = 2048;

    /** Cooperative cancellation so stale/aborted estimates stop quickly. */
    public interface Cancel {
        boolean cancelled();
    }

    /** Sample-encodes a spread of images to derive bytes-per-megapixel for the chosen format. */
    public static double calibrateRatio(List<MediaImage> images, CompressMode mode, int quality,
                                        Recompressor rc, Cancel cancel) {
        if (images == null || images.isEmpty()) return defaultRatio(mode);
        if (cancel != null && cancel.cancelled()) return defaultRatio(mode);

        // Pick up to SAMPLE_COUNT evenly-spaced images with positive megapixels.
        int n = Math.min(SAMPLE_COUNT, images.size());
        int stride = Math.max(1, images.size() / n);
        List<MediaImage> samples = new ArrayList<>(n);
        for (int i = 0; i < images.size() && samples.size() < n; i += stride) {
            MediaImage img = images.get(i);
            if (img.megapixels() > 0) samples.add(img);
        }
        if (samples.isEmpty()) return defaultRatio(mode);

        // Encode the samples in parallel (one thread each) at the reduced calibration resolution.
        ExecutorService pool = Executors.newFixedThreadPool(samples.size());
        List<Future<double[]>> futures = new ArrayList<>(samples.size());
        try {
            for (MediaImage img : samples) {
                futures.add(pool.submit(() -> {
                    if (cancel != null && cancel.cancelled()) return null;
                    long bytes = rc.encodedSize(img.contentUri(), mode, quality, CALIBRATION_LONG_SIDE);
                    return bytes > 0 ? new double[]{bytes, img.megapixels()} : null;
                }));
            }
            long sumBytes = 0;
            double sumMp = 0;
            for (Future<double[]> f : futures) {
                double[] r;
                try {
                    r = f.get();
                } catch (Exception e) {
                    r = null;
                }
                if (r != null) {
                    sumBytes += (long) r[0];
                    sumMp += r[1];
                }
            }
            return sumMp > 0 ? sumBytes / sumMp : defaultRatio(mode);
        } finally {
            pool.shutdownNow();
        }
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
            case JPEG: return JPEG_BYTES_PER_MP;
            default: return WEBP_BYTES_PER_MP;
        }
    }
}
