package com.shaforostoff.dcimsort.work;

import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.geo.GeoExtractor;
import com.shaforostoff.dcimsort.geo.PlaceResolver;
import com.shaforostoff.dcimsort.util.FolderNamer;

/**
 * Computes the destination subfolder name ({@code Place-YYYY-MM} or {@code YYYY-MM}) for an image.
 * Shared by Preview (grouping) and Organize (moving) so naming is identical and the geo cache is
 * reused across both.
 */
public class TargetResolver {
    private final GeoExtractor geo;
    private final PlaceResolver places;

    public TargetResolver(GeoExtractor geo, PlaceResolver places) {
        this.geo = geo;
        this.places = places;
    }

    public String folderFor(MediaImage img) {
        String place = null;
        double[] ll = geo.latLon(img.contentUri());
        if (ll != null) {
            place = places.resolve(ll[0], ll[1]);
        }
        return FolderNamer.folderName(img.dateTakenMillis, place);
    }
}
