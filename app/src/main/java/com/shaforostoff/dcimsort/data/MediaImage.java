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
    /**
     * Source URI for reading bytes when this image has no MediaStore row (id < 0) — e.g. a photo
     * picker / Google Photos cloud pick. Null for normal MediaStore-backed images.
     */
    public final Uri sourceUri;

    public MediaImage(long id, String displayName, String relativePath, String dataPath,
                      long dateTakenMillis, long size, boolean favorite, String mimeType,
                      int width, int height, String description) {
        this(id, displayName, relativePath, dataPath, dateTakenMillis, size, favorite, mimeType,
                width, height, description, null);
    }

    public MediaImage(long id, String displayName, String relativePath, String dataPath,
                      long dateTakenMillis, long size, boolean favorite, String mimeType,
                      int width, int height, String description, Uri sourceUri) {
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
        this.sourceUri = sourceUri;
    }

    public Uri contentUri() {
        return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id);
    }

    /** True when this image has a real MediaStore row that can be relocated/deleted in place. */
    public boolean isMovable() {
        return id >= 0 && sourceUri == null;
    }

    /** URI to read the original bytes from: the picker source if present, else the MediaStore row. */
    public Uri readUri() {
        return sourceUri != null ? sourceUri : contentUri();
    }

    /**
     * Stable identity for selection tracking. MediaStore rows key by id; cloud picks (id = -1, which
     * would otherwise all collide) key by their source URI.
     */
    public String key() {
        return id >= 0 ? ("id:" + id) : ("uri:" + sourceUri);
    }

    public double megapixels() {
        if (width <= 0 || height <= 0) return 0;
        return (width * (double) height) / 1_000_000.0;
    }
}
