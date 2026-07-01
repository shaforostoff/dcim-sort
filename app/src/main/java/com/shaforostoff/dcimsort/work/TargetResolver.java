package com.shaforostoff.dcimsort.work;

import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.geo.CoordCache;
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
    private final GroupMode groupMode;
    private final CoordCache coords; // nullable; skips re-reading EXIF on repeat runs

    public TargetResolver(GeoExtractor geo, PlaceResolver places, GroupMode groupMode) {
        this(geo, places, groupMode, null);
    }

    public TargetResolver(GeoExtractor geo, PlaceResolver places, GroupMode groupMode,
                          CoordCache coords) {
        this.geo = geo;
        this.places = places;
        this.groupMode = groupMode;
        this.coords = coords;
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
        if (coords == null) {
            return geo.latLon(img.contentUri());
        }
        String key = CoordCache.key(img.id, img.size, img.dateTakenMillis);
        if (coords.contains(key)) {
            return coords.get(key);
        }
        double[] ll = geo.latLon(img.contentUri());
        coords.put(key, ll);
        return ll;
    }
}
