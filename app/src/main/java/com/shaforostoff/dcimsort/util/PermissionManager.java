package com.shaforostoff.dcimsort.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/** Computes and checks the runtime permission set appropriate for the running SDK level. */
public final class PermissionManager {
    private PermissionManager() {}

    /** The read/media permission needed to enumerate images on this device. */
    public static String readMediaPermission() {
        if (Sdk.atLeastT()) {
            return Manifest.permission.READ_MEDIA_IMAGES;
        }
        return Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    /** All permissions we should request up front (read access + GPS access + legacy write). */
    public static String[] requiredPermissions() {
        List<String> p = new ArrayList<>();
        p.add(readMediaPermission());
        if (Sdk.atLeastQ()) {
            // Needed so MediaStore returns un-redacted EXIF GPS via setRequireOriginal().
            p.add(Manifest.permission.ACCESS_MEDIA_LOCATION);
        }
        // Legacy write access. On <=28 it enables direct file moves. On Android 10 (API 29),
        // combined with requestLegacyExternalStorage=true, it activates the full-access storage
        // view so MediaStore update/delete on other apps' images succeed without per-file consent
        // (createWriteRequest only exists on API 30+, handled separately in MainActivity). On
        // API 30+ the permission is ignored and not grantable, so we don't request it there.
        if (!Sdk.atLeastR()) {
            p.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        return p.toArray(new String[0]);
    }

    public static boolean has(Context ctx, String permission) {
        // Context.checkSelfPermission exists since API 23; minSdk is 24.
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasReadMedia(Context ctx) {
        return has(ctx, readMediaPermission());
    }

    /** Returns the subset of {@link #requiredPermissions()} not yet granted. */
    public static String[] missing(Context ctx) {
        List<String> missing = new ArrayList<>();
        for (String perm : requiredPermissions()) {
            if (!has(ctx, perm)) missing.add(perm);
        }
        return missing.toArray(new String[0]);
    }

    /** POST_NOTIFICATIONS is a runtime permission only on API 33+. */
    public static boolean needsNotificationPermission(Context ctx) {
        return Sdk.atLeastT() && !has(ctx, Manifest.permission.POST_NOTIFICATIONS);
    }
}
