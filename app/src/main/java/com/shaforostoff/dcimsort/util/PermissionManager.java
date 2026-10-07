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
    private static String readMediaPermission() {
        return Sdk.atLeastT() ? Manifest.permission.READ_MEDIA_IMAGES
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    public static boolean hasReadMedia(Context ctx) {
        return has(ctx, readMediaPermission());
    }

    /** Every permission to request up front that isn't granted yet. */
    public static String[] missing(Context ctx) {
        String[] wanted = {
                readMediaPermission(),
                // Needed so MediaStore returns un-redacted EXIF GPS via setRequireOriginal().
                Sdk.atLeastQ() ? Manifest.permission.ACCESS_MEDIA_LOCATION : null,
                // Legacy write access. On <=28 it enables direct file moves. On Android 10 (API 29),
                // combined with requestLegacyExternalStorage=true, it activates the full-access
                // storage view so MediaStore update/delete on other apps' images succeed without
                // per-file consent (createWriteRequest only exists on API 30+, handled separately in
                // MainActivity). On API 30+ it is ignored and not grantable, so it isn't requested.
                Sdk.atLeastR() ? null : Manifest.permission.WRITE_EXTERNAL_STORAGE,
                // POST_NOTIFICATIONS is a runtime permission only on API 33+.
                Sdk.atLeastT() ? Manifest.permission.POST_NOTIFICATIONS : null,
        };
        List<String> missing = new ArrayList<>();
        for (String p : wanted) {
            if (p != null && !has(ctx, p)) missing.add(p);
        }
        return missing.toArray(new String[0]);
    }

    private static boolean has(Context ctx, String permission) {
        // Context.checkSelfPermission exists since API 23; minSdk is 24.
        return ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
