package com.shaforostoff.dcimsort.util;

import java.util.Locale;

/** Human-readable byte sizes. Uses binary-ish powers of 1024 with one decimal. */
public final class Formatter {
    private Formatter() {}

    public static String humanReadableBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return trim(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return trim(mb) + " MB";
        double gb = mb / 1024.0;
        return trim(gb) + " GB";
    }

    private static String trim(double v) {
        // One decimal, but drop trailing ".0".
        String s = String.format(Locale.US, "%.1f", v);
        if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
        return s;
    }
}
