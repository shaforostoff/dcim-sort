package com.shaforostoff.dcimsort.data;

/** Recompression target chosen on the main screen. */
public enum CompressMode {
    NONE,
    WEBP,
    HEIC,
    AVIF,
    JPEG;

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
