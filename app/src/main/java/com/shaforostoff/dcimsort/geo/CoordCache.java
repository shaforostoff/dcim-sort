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
 * Disk-backed per-image GPS cache. EXIF coordinates for a given photo never change, yet reading
 * them means opening the original bytes (costly on scoped storage) and parsing EXIF on every run.
 * Keyed by a stable image identity; an empty value means "looked at, no GPS" so photos without a
 * location tag aren't re-opened either. Persisted with framework JSON (no deps), like {@link GeoCache}.
 */
public class CoordCache {
    private final File file;
    private final Map<String, String> map = new HashMap<>();
    private boolean dirty;

    public CoordCache(Context ctx) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), "coordcache.json");
        load();
    }

    /** Stable identity for one image; changes if the file is edited (size/date shift). */
    public static String key(long id, long size, long dateTakenMillis) {
        return id + ":" + size + ":" + dateTakenMillis;
    }

    public synchronized boolean contains(String key) {
        return map.containsKey(key);
    }

    /** @return {lat, lon}, or null if absent or cached as "no GPS". */
    public synchronized double[] get(String key) {
        String v = map.get(key);
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
    public synchronized void put(String key, double[] ll) {
        map.put(key, ll == null ? "" : (ll[0] + "," + ll[1]));
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
            if (!tmp.renameTo(file)) {
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
