package com.shaforostoff.dcimsort.geo;

import android.content.Context;

/**
 * Disk-backed place cache keyed by a ~10 km spatial grid cell. An empty value means "looked up,
 * no place found" so we never re-query a known-empty cell.
 */
public class GeoCache extends JsonKeyValueStore {

    public GeoCache(Context ctx) {
        super(ctx, "geocache.json");
    }

    /** Snaps a coordinate to a ~10–11 km grid cell, adjusting longitude width by latitude. */
    public static String cellKey(double lat, double lon) {
        long latCell = Math.round(lat / 0.1);
        double centerLat = latCell * 0.1;
        double cosLat = Math.max(0.05, Math.cos(Math.toRadians(centerLat)));
        double lonStep = 0.1 / cosLat;
        long lonCell = Math.round(lon / lonStep);
        return latCell + "," + lonCell;
    }

    /** @return the place name, or null if absent or cached-empty. */
    public String get(String key) {
        String v = rawGet(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    public void put(String key, String value) {
        rawPut(key, value);
    }
}
