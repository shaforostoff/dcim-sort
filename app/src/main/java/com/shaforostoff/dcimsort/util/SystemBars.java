package com.shaforostoff.dcimsort.util;

import android.app.Activity;
import android.view.View;
import android.view.WindowInsets;

/**
 * System-bar inset handling, shared by every Activity (edge-to-edge is mandatory from API 35, so
 * each screen has to pad itself out from under the status and navigation bars).
 */
public final class SystemBars {
    private SystemBars() {}

    /** Pads the activity's content view by the top and bottom system-bar insets. */
    public static void padContent(Activity activity) {
        View root = activity.findViewById(android.R.id.content);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(0, top(insets), 0, bottom(insets));
            return insets;
        });
        root.requestApplyInsets();
    }

    public static int top(WindowInsets insets) {
        return Sdk.atLeastR()
                ? insets.getInsets(WindowInsets.Type.systemBars()).top
                : insets.getSystemWindowInsetTop();
    }

    public static int bottom(WindowInsets insets) {
        return Sdk.atLeastR()
                ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                : insets.getSystemWindowInsetBottom();
    }
}
