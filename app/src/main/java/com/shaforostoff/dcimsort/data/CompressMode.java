package com.shaforostoff.dcimsort.data;

/** Recompression target chosen on the main screen. */
public enum CompressMode {
    NONE(null, null, 0, false),
    WEBP(".webp", "image/webp", 0.22, false),
    HEIC(".heic", "image/heic", 0.12, true),
    AVIF(".avif", "image/avif", 0.10, true),
    JPEG(".jpg", "image/jpeg", 0.30, true);

    /** Output file extension and MIME type; null for NONE. */
    public final String extension, mime;
    /** Coarse bytes-per-megapixel fallback used when on-device calibration is unavailable. */
    public final double defaultBytesPerMp;
    /** HEIC/AVIF are codec-heavy; JPEG via the full flavor's jpegli encoder is CPU-bound too. */
    public final boolean heavyEncode;

    CompressMode(String extension, String mime, double mbPerMp, boolean heavyEncode) {
        this.extension = extension;
        this.mime = mime;
        this.defaultBytesPerMp = mbPerMp * 1024 * 1024;
        this.heavyEncode = heavyEncode;
    }

    public boolean recompresses() {
        return this != NONE;
    }

    public static CompressMode fromName(String name, CompressMode fallback) {
        if (name == null) return fallback;
        try {
            return CompressMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
