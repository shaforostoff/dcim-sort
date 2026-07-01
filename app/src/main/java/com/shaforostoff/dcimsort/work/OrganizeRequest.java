package com.shaforostoff.dcimsort.work;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.List;

/**
 * In-process handoff of a prepared organize job from the Activity (which obtains MediaStore
 * consent) to {@link OrganizeService}. Avoids serializing thousands of items through an Intent.
 */
public class OrganizeRequest {
    public List<MediaImage> images;     // newest-first
    public CompressMode mode;
    public int quality;
    public boolean skipFavorites;
    public String sourceRelativePath;   // e.g. "DCIM/Camera/"
    public String sourceDataDir;        // legacy absolute dir
    public String volumeName;           // "external_primary" = internal; SD UUID; null = legacy

    private static volatile OrganizeRequest pending;

    public static void set(OrganizeRequest r) {
        pending = r;
    }

    public static OrganizeRequest take() {
        OrganizeRequest r = pending;
        pending = null;
        return r;
    }
}
