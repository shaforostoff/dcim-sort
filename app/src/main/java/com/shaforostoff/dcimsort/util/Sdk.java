package com.shaforostoff.dcimsort.util;

import android.os.Build;

/** Centralized API-level gates so version checks read consistently across the app. */
public final class Sdk {
    private Sdk() {}

    /** Android 9 (API 28) — HeifWriter / HEIC encoding available. */
    public static boolean atLeastP() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /** Android 10 (API 29) — scoped storage, RELATIVE_PATH, IS_PENDING, setRequireOriginal. */
    public static boolean atLeastQ() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** Android 11 (API 30) — IS_FAVORITE, createWriteRequest/createTrashRequest, WEBP_LOSSY. */
    public static boolean atLeastR() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
    }

    /** Android 12 (API 31). */
    public static boolean atLeastS() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.S;
    }

    /** Android 13 (API 33) — READ_MEDIA_IMAGES, async Geocoder, POST_NOTIFICATIONS. */
    public static boolean atLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /** Android 14 (API 34) — typed foreground services. */
    public static boolean atLeastU() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
    }

    /** Android 16 (API 36) — mandated AV1 image encoder; AVIF encoding available. */
    public static boolean atLeastBaklava() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
    }
}
