package com.shaforostoff.dcimsort.work;

import android.content.Context;

import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.geo.GeoDb;
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
    private final PlaceResolver places;
    private final GeoDb cache; // per-photo GPS, so repeat runs skip re-reading EXIF
    private final GroupMode groupMode;

    public TargetResolver(Context ctx, MediaRepository repo, GroupMode groupMode) {
        this.geo = new GeoExtractor(repo);
        this.places = new PlaceResolver(ctx);
        this.cache = GeoDb.get(ctx);
        this.groupMode = groupMode;
    }

    /** Returns the destination subfolder name, or {@code null} if GroupMode is NONE. */
    public String folderFor(MediaImage img) {
        if (groupMode == GroupMode.NONE) return null;
        String place = null;
        double[] ll = coordsFor(img);
        if (ll != null) {
            place = places.resolve(ll[0], ll[1]);
        }
        return FolderNamer.folderName(img.dateTakenMillis, place, groupMode == GroupMode.PLACE_DAY);
    }

    /** EXIF GPS for an image, served from {@link GeoDb} when available to skip the file read. */
    private double[] coordsFor(MediaImage img) {
        // Picks without a MediaStore row (cloud picker) have no stable id to cache under — they
        // all share id -1 — and their bytes are only reachable through the picker URI.
        if (!img.isMovable()) {
            return geo.latLon(img.readUri());
        }
        double[] cached = cache.coords(img.id, img.size, img.dateTakenMillis);
        if (cached != GeoDb.UNKNOWN) return cached;
        double[] ll = geo.latLon(img.readUri());
        cache.putCoords(img.id, img.size, img.dateTakenMillis, ll);
        return ll;
    }
}
