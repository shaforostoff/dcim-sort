package com.shaforostoff.dcimsort.geo;

import android.net.Uri;

import androidx.exifinterface.media.ExifInterface;

import com.shaforostoff.dcimsort.data.MediaRepository;

import java.io.InputStream;

/** Reads GPS coordinates from a photo's (un-redacted) EXIF. */
public class GeoExtractor {
    private final MediaRepository repo;

    public GeoExtractor(MediaRepository repo) {
        this.repo = repo;
    }

    /** @return {lat, lon} or null if the image has no GPS tag (or it could not be read). */
    public double[] latLon(Uri uri) {
        try (InputStream in = repo.openOriginalForExif(uri)) {
            ExifInterface exif = new ExifInterface(in);
            double[] ll = exif.getLatLong();
            if (ll != null && (ll[0] != 0.0 || ll[1] != 0.0)) {
                return ll;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
