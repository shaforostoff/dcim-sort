package com.shaforostoff.dcimsort.data;

import android.content.ContentUris;
import android.net.Uri;
import android.provider.MediaStore;

/** Lightweight view of one image row from MediaStore. */
public class MediaImage {
    public final long id;
    public final String displayName;
    public final String relativePath; // e.g. "DCIM/Camera/" (29+); null on legacy
    public final String dataPath;      // absolute file path (legacy / when available)
    public final long dateTakenMillis; // DATE_TAKEN, or 0 if unknown
    public final long size;            // bytes
    public final boolean favorite;     // IS_FAVORITE (30+); false otherwise
    public final String mimeType;
    public final int width;
    public final int height;
    public final String description;   // MediaStore DESCRIPTION; null if unset

    public MediaImage(long id, String displayName, String relativePath, String dataPath,
                      long dateTakenMillis, long size, boolean favorite, String mimeType,
                      int width, int height, String description) {
        this.id = id;
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.dataPath = dataPath;
        this.dateTakenMillis = dateTakenMillis;
        this.size = size;
        this.favorite = favorite;
        this.mimeType = mimeType;
        this.width = width;
        this.height = height;
        this.description = description;
    }

    public Uri contentUri() {
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    public double megapixels() {
        if (width <= 0 || height <= 0) return 0;
        return (width * (double) height) / 1_000_000.0;
    }
}
