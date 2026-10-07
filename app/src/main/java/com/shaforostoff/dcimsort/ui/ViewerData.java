package com.shaforostoff.dcimsort.ui;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.List;

/** In-process handoff of the image to view (avoids making MediaImage Parcelable). */
public class ViewerData {
    public List<MediaImage> images; // full list for swipe navigation
    public int index;               // position of the image to show
    public CompressMode mode;
    public int quality;
    public boolean skipFav;

    private static volatile ViewerData pending;

    public static void set(ViewerData d) {
        pending = d;
    }

    public static ViewerData take() {
        ViewerData d = pending;
        pending = null;
        return d;
    }
}
