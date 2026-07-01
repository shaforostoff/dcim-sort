package com.shaforostoff.dcimsort.ui;

import com.shaforostoff.dcimsort.data.MediaImage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-RAM set of image IDs the user has excluded from processing in the current Preview session. */
public class SelectionStore {

    private static final Set<String> deselected = new HashSet<>();

    public static void clear() { deselected.clear(); }

    public static boolean isSelected(String key) { return !deselected.contains(key); }

    public static void toggle(String key) {
        if (!deselected.remove(key)) deselected.add(key);
    }

    public static boolean allSelected(List<MediaImage> images) {
        for (MediaImage img : images) {
            if (deselected.contains(img.key())) return false;
        }
        return true;
    }

    public static void selectAll(List<MediaImage> images) {
        for (MediaImage img : images) deselected.remove(img.key());
    }

    public static void deselectAll(List<MediaImage> images) {
        for (MediaImage img : images) deselected.add(img.key());
    }

    /** Returns a filtered copy, or the same list instance if nothing is deselected. */
    public static List<MediaImage> filter(List<MediaImage> images) {
        if (deselected.isEmpty()) return images;
        List<MediaImage> out = new ArrayList<>();
        for (MediaImage img : images) {
            if (!deselected.contains(img.key())) out.add(img);
        }
        return out;
    }
}
