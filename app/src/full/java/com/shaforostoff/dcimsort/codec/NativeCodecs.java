package com.shaforostoff.dcimsort.codec;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.File;

/**
 * Full-flavor implementation backed by {@code libdcimsort_codecs.so} (jpegli, and optionally
 * libavif + aom when built with {@code -DENABLE_AVIF=ON}).
 *
 * <p>JPEG accepts an ARGB_8888 {@link Bitmap}; AVIF additionally accepts an optional gain map
 * (contents bitmap + {@link GainmapMeta}) and an EXIF TIFF block that libavif embeds directly.
 */
public final class NativeCodecs {
    private static final String TAG = "NativeCodecs";
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("dcimsort_codecs");
            ok = true;
        } catch (Throwable t) {
            Log.e(TAG, "Failed to load native codecs", t);
            ok = false;
        }
        LOADED = ok;
    }

    private NativeCodecs() {}

    public static boolean isFull() {
        return true;
    }

    public static boolean avifAvailable() {
        return LOADED && nativeAvifAvailable();
    }

    public static boolean jpegliAvailable() {
        return LOADED;
    }

    /**
     * Encodes {@code base} (ARGB_8888) to AVIF at {@code out}.
     *
     * @param gainmapContents optional gain-map bitmap (ARGB_8888); null for SDR.
     * @param meta            gain-map metadata; ignored when {@code gainmapContents} is null.
     * @param quality         0..100 (mapped to libavif quantizers natively).
     * @param exifTiff        optional raw TIFF/Exif block to embed; null to omit.
     */
    public static boolean encodeAvif(Bitmap base, Bitmap gainmapContents, GainmapMeta meta,
                                     int quality, byte[] exifTiff, File out) {
        if (!LOADED || base == null || out == null) return false;
        try {
            return nativeEncodeAvif(base, gainmapContents, meta, quality, exifTiff,
                    out.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "AVIF encode failed", t);
            return false;
        }
    }

    public static boolean encodeJpeg(Bitmap base, int quality, File out) {
        if (!LOADED || base == null || out == null) return false;
        try {
            return nativeEncodeJpeg(base, quality, out.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "JPEG encode failed", t);
            return false;
        }
    }

    /**
     * Encodes a UltraHDR JPEG (JPEG_R): primary base JPEG + gainmap JPEG stitched with MPF and
     * XMP (hdrgm namespace). EXIF is embedded during encode so MPF offsets stay stable.
     *
     * @param gainmap  gainmap contents bitmap (RGBA_8888 or ALPHA_8).
     * @param meta     gainmap metadata; must not be null.
     * @param exifTiff optional raw TIFF/Exif block to embed; null to omit.
     */
    public static boolean encodeJpegR(Bitmap base, Bitmap gainmap, GainmapMeta meta,
                                      int quality, byte[] exifTiff, File out) {
        if (!LOADED || base == null || gainmap == null || meta == null || out == null) return false;
        try {
            return nativeEncodeJpegR(base, gainmap, meta, quality, exifTiff,
                    out.getAbsolutePath());
        } catch (Throwable t) {
            Log.e(TAG, "JPEG_R encode failed", t);
            return false;
        }
    }

    /**
     * Asks the native allocator to release cached free memory back to the OS. Call once after a
     * compression batch finishes; it's a no-op on any harm and safe to call when unloaded. Does
     * nothing for decoded {@link Bitmap}s (those live on the graphics heap, not the malloc heap).
     */
    public static void purgeMemory() {
        if (!LOADED) return;
        try {
            nativePurgeMemory();
        } catch (Throwable t) {
            Log.w(TAG, "purgeMemory failed", t);
        }
    }

    private static native void nativePurgeMemory();

    private static native boolean nativeAvifAvailable();

    private static native boolean nativeEncodeAvif(Bitmap base, Bitmap gainmapContents,
                                                   GainmapMeta meta, int quality, byte[] exifTiff,
                                                   String outPath);

    private static native boolean nativeEncodeJpeg(Bitmap base, int quality, String outPath);

    private static native boolean nativeEncodeJpegR(Bitmap base, Bitmap gainmap, GainmapMeta meta,
                                                    int quality, byte[] exifTiff, String outPath);
}
