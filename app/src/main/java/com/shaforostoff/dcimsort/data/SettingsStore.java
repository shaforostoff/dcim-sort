package com.shaforostoff.dcimsort.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.shaforostoff.dcimsort.data.GroupMode;
import com.shaforostoff.dcimsort.work.SizeEstimator;

/** Persists the selected source folder and compression settings across app restarts. */
public class SettingsStore {
    private static final String PREFS = "dcimsort_settings";

    private static final String K_REL_PATH = "source_relative_path";
    private static final String K_BUCKET_ID = "source_bucket_id";
    private static final String K_DISPLAY = "source_display_name";
    private static final String K_DATA_PATH = "source_data_path";
    private static final String K_VOLUME = "source_volume_name";
    private static final String K_MODE = "compress_mode";
    private static final String K_GROUP_MODE = "group_mode";
    private static final String K_QUALITY = "quality";
    private static final String K_SKIP_FAV = "skip_favorites";
    private static final String K_SKIP_LOW_GAIN = "skip_low_gain";
    private static final String K_MIN_GAIN_PERCENT = "min_gain_percent";
    private static final String K_FOLDER_SORT_ALPHA = "folder_sort_alpha";

    private final SharedPreferences prefs;

    public SettingsStore(Context ctx) {
        prefs = ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean hasSourceFolder() {
        return prefs.contains(K_REL_PATH) || prefs.contains(K_DATA_PATH);
    }

    public void setSourceFolder(String relativePath, long bucketId, String displayName,
                                String dataPath, String volumeName) {
        prefs.edit()
                .putString(K_REL_PATH, relativePath)
                .putLong(K_BUCKET_ID, bucketId)
                .putString(K_DISPLAY, displayName)
                .putString(K_DATA_PATH, dataPath)
                .putString(K_VOLUME, volumeName)
                .apply();
    }

    public String getRelativePath() { return prefs.getString(K_REL_PATH, null); }
    public long getBucketId() { return prefs.getLong(K_BUCKET_ID, -1); }
    public String getDisplayName() { return prefs.getString(K_DISPLAY, null); }
    public String getDataPath() { return prefs.getString(K_DATA_PATH, null); }
    public String getVolumeName() { return prefs.getString(K_VOLUME, null); }

    public CompressMode getMode() {
        return CompressMode.fromName(prefs.getString(K_MODE, CompressMode.NONE.name()), CompressMode.NONE);
    }

    public void setMode(CompressMode mode) {
        prefs.edit().putString(K_MODE, mode.name()).apply();
    }

    public GroupMode getGroupMode() {
        return GroupMode.fromName(prefs.getString(K_GROUP_MODE, GroupMode.PLACE_MONTH.name()), GroupMode.PLACE_MONTH);
    }

    public void setGroupMode(GroupMode mode) {
        prefs.edit().putString(K_GROUP_MODE, mode.name()).apply();
    }

    /** Quality is remembered per compression mode (WebP and HEIC keep separate values). */
    public int getQuality(CompressMode mode) {
        return prefs.getInt(qualityKey(mode), 80);
    }

    public void setQuality(CompressMode mode, int quality) {
        prefs.edit().putInt(qualityKey(mode), Math.max(0, Math.min(100, quality))).apply();
    }

    private static String qualityKey(CompressMode mode) {
        return K_QUALITY + "_" + mode.name();
    }

    public boolean getSkipFavorites() { return prefs.getBoolean(K_SKIP_FAV, false); }

    public void setSkipFavorites(boolean skip) {
        prefs.edit().putBoolean(K_SKIP_FAV, skip).apply();
    }

    public boolean getSkipLowGain() { return prefs.getBoolean(K_SKIP_LOW_GAIN, false); }

    public void setSkipLowGain(boolean skip) {
        prefs.edit().putBoolean(K_SKIP_LOW_GAIN, skip).apply();
    }

    /** Minimum saving (percent of the original) that makes compressing worth it; kept even when
     *  skip-low-gain is off, so toggling the checkbox doesn't lose the chosen threshold. */
    public int getMinGainPercent() {
        return SizeEstimator.clampMinGain(
                prefs.getInt(K_MIN_GAIN_PERCENT, SizeEstimator.DEFAULT_MIN_GAIN_PERCENT));
    }

    public void setMinGainPercent(int percent) {
        prefs.edit().putInt(K_MIN_GAIN_PERCENT, SizeEstimator.clampMinGain(percent)).apply();
    }

    public boolean isFolderSortAlpha() { return prefs.getBoolean(K_FOLDER_SORT_ALPHA, false); }

    public void setFolderSortAlpha(boolean alpha) {
        prefs.edit().putBoolean(K_FOLDER_SORT_ALPHA, alpha).apply();
    }
}
