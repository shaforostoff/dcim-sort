package com.shaforostoff.dcimsort.data;

/**
 * Inclusive shooting-date range used to scope the organize job. Photos with an unknown date
 * (dateTakenMillis &lt;= 0) are always considered in range — they have no date to filter on and
 * land in the {@code unknown-date} folder regardless.
 */
public class DateRange {
    public final long fromMillis;
    public final long toMillis;

    public DateRange(long fromMillis, long toMillis) {
        this.fromMillis = fromMillis;
        this.toMillis = toMillis;
    }

    /** A range that includes everything. */
    public static DateRange all() {
        return new DateRange(Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public boolean contains(long dateTakenMillis) {
        if (dateTakenMillis <= 0) return true;
        return dateTakenMillis >= fromMillis && dateTakenMillis <= toMillis;
    }
}
