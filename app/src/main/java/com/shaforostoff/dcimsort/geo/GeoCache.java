package com.shaforostoff.dcimsort.geo;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Disk-backed place cache keyed by a ~10 km spatial grid cell. An empty value means "looked up,
 * no place found" so we never re-query a known-empty cell. Persisted with framework JSON (no deps).
 */
public class GeoCache {
    private final File file;
    private final Map<String, String> map = new HashMap<>();
    private boolean dirty;

    public GeoCache(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), "geocache.json");
        load();
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

    public synchronized boolean contains(String key) {
        return map.containsKey(key);
    }

    /** @return the place name, or null if absent or cached-empty. */
    public synchronized String get(String key) {
        String v = map.get(key);
        return (v == null || v.isEmpty()) ? null : v;
    }

    public synchronized void put(String key, String value) {
        map.put(key, value == null ? "" : value);
        dirty = true;
    }

    public synchronized void flush() {
        if (!dirty) return;
        File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
        try (JsonWriter w = new JsonWriter(
                new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.beginObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                w.name(e.getKey()).value(e.getValue());
            }
            w.endObject();
            w.flush();
            // Atomic-ish replace.
            if (!tmp.renameTo(file)) {
                // Fall back to direct overwrite if rename fails.
                copy(tmp, file);
                tmp.delete();
            }
            dirty = false;
        } catch (Exception ignore) {
            tmp.delete();
        }
    }

    private void load() {
        if (!file.exists()) return;
        try (JsonReader r = new JsonReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                String k = r.nextName();
                String v = r.nextString();
                map.put(k, v);
            }
            r.endObject();
        } catch (Exception ignore) {
            map.clear();
        }
    }

    private static void copy(File src, File dst) throws Exception {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }
}
