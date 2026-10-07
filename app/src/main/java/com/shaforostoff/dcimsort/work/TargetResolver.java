package com.shaforostoff.dcimsort.work;

import android.content.Context;

import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.geo.CoordCache;
import com.shaforostoff.dcimsort.geo.GeoCache;
import com.shaforostoff.dcimsort.geo.GeoExtractor;
import com.shaforostoff.dcimsort.geo.PlaceResolver;
import com.shaforostoff.dcimsort.util.FolderNamer;

/**
 * Computes the destination subfolder name for an image based on the selected {@link GroupMode}.
 * Returns {@code null} when GroupMode is NONE (stay in source folder).
 * Shared by Preview and Organize so naming is identical and the geo cache is reused across both.
 */
public class TargetResolver {
    private final GeoExtractor geo;
    private final GeoCache placeCache;
    private final PlaceResolver places;
    private final CoordCache coords; // skips re-reading EXIF on repeat runs
    private final GroupMode groupMode;

    /** Loads the on-disk place and GPS caches; {@link #flush()} persists what was added. */
    public TargetResolver(Context ctx, MediaRepository repo, GroupMode groupMode) {
        this.geo = new GeoExtractor(repo);
        this.placeCache = new GeoCache(ctx);
        this.places = new PlaceResolver(ctx, placeCache);
        this.coords = new CoordCache(ctx);
        this.groupMode = groupMode;
    }

    /** Writes newly resolved places and per-image GPS to disk. */
    public void flush() {
        placeCache.flush();
        coords.flush();
    }

    /** Returns the destination subfolder name, or {@code null} if GroupMode is NONE. */
    public String folderFor(MediaImage img) {
        if (groupMode == GroupMode.NONE) return null;
        String place = null;
        double[] ll = coordsFor(img);
        if (ll != null) {
            place = places.resolve(ll[0], ll[1]);
        }
        if (groupMode == GroupMode.PLACE_DAY) {
            return FolderNamer.folderNameDay(img.dateTakenMillis, place);
        }
        return FolderNamer.folderName(img.dateTakenMillis, place);
    }

    /** EXIF GPS for an image, served from {@link CoordCache} when available to skip the file read. */
    private double[] coordsFor(MediaImage img) {
        // Picks without a MediaStore row (cloud picker) have no stable id to cache under — they
        // all share id -1 — and their bytes are only reachable through the picker URI.
        if (!img.isMovable()) {
            return geo.latLon(img.readUri());
        }
        String key = CoordCache.key(img.id, img.size, img.dateTakenMillis);
        if (coords.contains(key)) {
            return coords.get(key);
        }
        double[] ll = geo.latLon(img.readUri());
        coords.put(key, ll);
        return ll;
    }
}
