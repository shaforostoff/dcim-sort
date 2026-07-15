package com.shaforostoff.dcimsort.codec;

import android.graphics.Bitmap;

import java.io.File;

/**
 * Lite-flavor stub: no native codecs are bundled. Every capability check returns {@code false} and
 * the encode entry points are never reached (the JPEG mode and the native-AVIF path are hidden in
 * the UI). The full flavor ships a JNI-backed implementation with the identical signature.
 */
public final class NativeCodecs {
    private NativeCodecs() {}

    /** True only in the full flavor. */
    public static boolean isFull() {
        return false;
    }

    /** libavif (HDR/EXIF AVIF encoder) — never available in lite. */
    public static boolean avifAvailable() {
        return false;
    }

    /** jpegli JPEG encoder — never available in lite. */
    public static boolean jpegliAvailable() {
        return false;
    }

    public static boolean encodeAvif(Bitmap base, Bitmap gainmapContents, GainmapMeta meta,
                                     int quality, byte[] exifTiff, File out) {
        return false;
    }

    public static boolean encodeJpeg(Bitmap base, int quality, File out) {
        return false;
    }

    public static boolean encodeJpegR(Bitmap base, Bitmap gainmap, GainmapMeta meta,
                                      int quality, byte[] exifTiff, File out) {
        return false;
    }

    /** No-op in lite: no native allocator to purge. Mirrors the full flavor's signature. */
    public static void purgeMemory() {
    }
}
