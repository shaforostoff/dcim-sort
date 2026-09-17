package com.shaforostoff.dcimsort.geo;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonWriter;

import com.shaforostoff.dcimsort.util.Io;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Disk-backed {@code String -> String} map persisted as a flat JSON object with the framework's
 * {@link JsonReader}/{@link JsonWriter} (no dependencies). Edits are held in memory and only reach
 * disk on {@link #flush()}, via a temp file + rename so a kill mid-write can't corrupt the cache.
 *
 * <p>An empty value is meaningful: it records "looked this up, found nothing", so a fruitless
 * lookup is never repeated. That is why {@link #contains} is separate from {@link #rawGet} —
 * subclasses test the former before deciding to do work.
 */
public abstract class JsonKeyValueStore {
    private final File file;
    private final Map<String, String> map = new HashMap<>();
    private boolean dirty;

    protected JsonKeyValueStore(Context ctx, String fileName) {
        this.file = new File(ctx.getApplicationContext().getFilesDir(), fileName);
        load();
    }

    public synchronized boolean contains(String key) {
        return map.containsKey(key);
    }

    /** Raw stored value: null when absent, empty when cached as "nothing found". */
    protected synchronized String rawGet(String key) {
        return map.get(key);
    }

    /** Stores {@code value}, mapping null to the empty "nothing found" marker. */
    protected synchronized void rawPut(String key, String value) {
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
            // Atomic-ish replace; fall back to a direct overwrite if rename fails.
            if (!tmp.renameTo(file)) {
                Io.copyFile(tmp, file);
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
                map.put(r.nextName(), r.nextString());
            }
            r.endObject();
        } catch (Exception ignore) {
            map.clear();
        }
    }
}
