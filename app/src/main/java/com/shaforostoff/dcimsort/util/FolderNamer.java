package com.shaforostoff.dcimsort.util;

import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds the destination subfolder name for a photo: {@code Place-YYYY-MM} when a place is known,
 * otherwise {@code YYYY-MM}. Output is sanitized to be filesystem-safe.
 */
public final class FolderNamer {
    private FolderNamer() {}

    /**
     * @param dateTakenMillis epoch millis (DATE_TAKEN) of the shot; 0/negative falls back to "unknown-date".
     * @param place           resolved place name, or null/blank if unknown.
     */
    public static String folderName(long dateTakenMillis, String place) {
        String ym = yearMonth(dateTakenMillis);
        String safePlace = sanitize(place);
        return safePlace == null ? ym : safePlace + "-" + ym;
    }

    public static String folderNameDay(long dateTakenMillis, String place) {
        String ymd = yearMonthDay(dateTakenMillis);
        String safePlace = sanitize(place);
        return safePlace == null ? ymd : safePlace + "-" + ymd;
    }

    private static String yearMonth(long millis) {
        if (millis <= 0) return "unknown-date";
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1);
    }

    private static String yearMonthDay(long millis) {
        if (millis <= 0) return "unknown-date";
        Calendar c = Calendar.getInstance(TimeZone.getDefault());
        c.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
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
