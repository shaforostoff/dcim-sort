package com.shaforostoff.dcimsort.util;

import java.util.Locale;

/**
 * Builds the destination subfolder name for a photo: {@code Place-YYYY-MM} (or {@code -YYYY-MM-DD})
 * when a place is known, otherwise just the date. Output is sanitized to be filesystem-safe.
 */
public final class FolderNamer {
    private FolderNamer() {}

    /**
     * @param dateTakenMillis epoch millis (DATE_TAKEN) of the shot; 0/negative falls back to "unknown-date".
     * @param place           resolved place name, or null/blank if unknown.
     * @param perDay          {@code YYYY-MM-DD} instead of {@code YYYY-MM}.
     */
    public static String folderName(long dateTakenMillis, String place, boolean perDay) {
        // A Long argument formats in the default time zone.
        String date = dateTakenMillis <= 0 ? "unknown-date" : String.format(Locale.US,
                perDay ? "%1$tY-%1$tm-%1$td" : "%1$tY-%1$tm", dateTakenMillis);
        String safePlace = sanitize(place);
        return safePlace == null ? date : safePlace + "-" + date;
    }

    /** Returns a filesystem-safe place token, or null if the input is empty after cleaning. */
    public static String sanitize(String place) {
        if (place == null) return null;
        String s = place.trim();
        if (s.isEmpty()) return null;
        // Replace path separators and reserved/troublesome characters with nothing or a dash.
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '/' || ch == '\\' || ch == ':' || ch == '*' || ch == '?'
                    || ch == '"' || ch == '<' || ch == '>' || ch == '|' || ch < 0x20) {
                // skip
            } else if (ch == '-') {
                sb.append(' '); // keep our own dash as the place/date separator unambiguous
            } else {
                sb.append(ch);
            }
        }
        String cleaned = sb.toString().trim();
        // Collapse internal whitespace runs into a single space.
        cleaned = cleaned.replaceAll("\\s+", " ");
        if (cleaned.isEmpty()) return null;
        // Cap length so folder names stay reasonable.
        if (cleaned.length() > 40) cleaned = cleaned.substring(0, 40).trim();
        return cleaned;
    }
}
