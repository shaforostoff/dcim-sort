package com.shaforostoff.dcimsort.geo;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;

/**
 * On-disk geo caches, one SQLite database shared by Preview and Organize:
 * <ul>
 *   <li>{@code coords}: EXIF GPS per photo. Reading it means opening the original (costly on scoped
 *   storage), and it never changes while the photo's size and date stay the same.</li>
 *   <li>{@code places}: place name per ~10 km grid cell, so nearby photos cost one lookup.</li>
 * </ul>
 * Each put is its own small WAL commit, so there is nothing to flush and a kill loses at most the
 * last few entries. It is only a cache: any SQLite error reads as "unknown" and is never fatal.
 */
public final class GeoDb extends SQLiteOpenHelper {
    /** {@link #coords} result for a photo that isn't cached (or changed since). */
    public static final double[] UNKNOWN = new double[0];

    private static GeoDb instance;
    private final Context ctx;

    public static synchronized GeoDb get(Context ctx) {
        if (instance == null) instance = new GeoDb(ctx.getApplicationContext());
        return instance;
    }

    private GeoDb(Context ctx) {
        super(ctx, "geo.db", null, 1);
        this.ctx = ctx;
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // With WAL this syncs only at checkpoints: a crash may drop recent entries, never corrupt.
        db.execSQL("PRAGMA synchronous = NORMAL");
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // lat/lon NULL = looked at, no GPS. name '' = looked up, no place.
        db.execSQL("CREATE TABLE coords (id INTEGER PRIMARY KEY, size INTEGER NOT NULL,"
                + " taken INTEGER NOT NULL, lat REAL, lon REAL)");
        db.execSQL("CREATE TABLE places (cell TEXT PRIMARY KEY, name TEXT NOT NULL)");
        // The JSON caches this database replaces; a cache, so they are dropped, not imported.
        for (String name : new String[]{"geocache.json", "coordcache.json"}) {
            new File(ctx.getFilesDir(), name).delete();
            new File(ctx.getFilesDir(), name + ".tmp").delete();
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    /**
     * Cached GPS for photo {@code id}: {@link #UNKNOWN} when absent or the photo changed (another
     * size or date), null when it has no GPS, else {lat, lon}.
     */
    public double[] coords(long id, long size, long taken) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT lat, lon FROM coords WHERE id = ? AND size = ? AND taken = ?",
                new String[]{Long.toString(id), Long.toString(size), Long.toString(taken)})) {
            if (!c.moveToFirst()) return UNKNOWN;
            return c.isNull(0) ? null : new double[]{c.getDouble(0), c.getDouble(1)};
        } catch (SQLException e) {
            return UNKNOWN;
        }
    }

    /** Stores {@code ll} ({lat, lon}, or null for no GPS), replacing any older entry for the photo. */
    public void putCoords(long id, long size, long taken, double[] ll) {
        ContentValues cv = new ContentValues();
        cv.put("id", id);
        cv.put("size", size);
        cv.put("taken", taken);
        cv.put("lat", ll != null ? ll[0] : null);
        cv.put("lon", ll != null ? ll[1] : null);
        put("coords", cv);
    }

    /** Cached place for a grid cell: null when unknown, "" when looked up and none found. */
    public String place(String cell) {
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT name FROM places WHERE cell = ?", new String[]{cell})) {
            return c.moveToFirst() ? c.getString(0) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    /** Stores a lookup answer for a cell; "" records that it has no place. */
    public void putPlace(String cell, String name) {
        ContentValues cv = new ContentValues();
        cv.put("cell", cell);
        cv.put("name", name);
        put("places", cv);
    }

    private void put(String table, ContentValues cv) {
        try {
            getWritableDatabase().insertWithOnConflict(table, null, cv,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (SQLException ignore) {
            // Disk full or similar: the entry is simply looked up again next time.
        }
    }
}
