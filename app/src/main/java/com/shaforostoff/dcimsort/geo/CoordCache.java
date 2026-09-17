package com.shaforostoff.dcimsort.geo;

import android.content.Context;

/**
 * Disk-backed per-image GPS cache. EXIF coordinates for a given photo never change, yet reading
 * them means opening the original bytes (costly on scoped storage) and parsing EXIF on every run.
 * Keyed by a stable image identity; an empty value means "looked at, no GPS" so photos without a
 * location tag aren't re-opened either.
 */
public class CoordCache extends JsonKeyValueStore {

    public CoordCache(Context ctx) {
        super(ctx, "coordcache.json");
    }

    /** Stable identity for one image; changes if the file is edited (size/date shift). */
    public static String key(long id, long size, long dateTakenMillis) {
        return id + ":" + size + ":" + dateTakenMillis;
    }

    /** @return {lat, lon}, or null if absent or cached as "no GPS". */
    public double[] get(String key) {
        String v = rawGet(key);
        if (v == null || v.isEmpty()) return null;
        int comma = v.indexOf(',');
        if (comma <= 0) return null;
        try {
            return new double[]{
                    Double.parseDouble(v.substring(0, comma)),
                    Double.parseDouble(v.substring(comma + 1))
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Stores coordinates, or "no GPS" when {@code ll} is null. */
    public void put(String key, double[] ll) {
        rawPut(key, ll == null ? null : (ll[0] + "," + ll[1]));
    }
}
