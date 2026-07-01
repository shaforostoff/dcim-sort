package com.shaforostoff.dcimsort.data;

public enum GroupMode {
    NONE,
    PLACE_MONTH,
    PLACE_DAY;

    public static GroupMode fromName(String name, GroupMode fallback) {
        if (name == null) return fallback;
        try { return valueOf(name); } catch (IllegalArgumentException e) { return fallback; }
    }
}
