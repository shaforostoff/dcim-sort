package com.shaforostoff.dcimsort.ui;

/** Shared Intent extra keys. */
public final class Extras {
    private Extras() {}

    public static final String REL_PATH = "rel_path";
    public static final String DATA_DIR = "data_dir";
    public static final String DISPLAY = "display";
    public static final String MODE = "mode";
    public static final String QUALITY = "quality";
    public static final String SKIP_FAV = "skip_fav";
    public static final String SKIP_LOW_GAIN = "skip_low_gain";
    public static final String MIN_GAIN_PERCENT = "min_gain_percent"; // skip-low-gain threshold
    public static final String GROUP_MODE = "group_mode";
    public static final String DATE_FROM = "date_from";
    public static final String DATE_TO = "date_to";
    public static final String RATIO = "ratio"; // bytes-per-megapixel calibrated by the plan summary
    public static final String FILE_URIS = "file_uris"; // picked URIs for files-mode preview (no folder)

    // FolderPicker result
    public static final String RESULT_BUCKET_ID = "bucket_id";
    public static final String RESULT_REL_PATH = "rel_path";
    public static final String RESULT_DATA_DIR = "data_dir";
    public static final String RESULT_DISPLAY = "display";
    public static final String VOLUME_NAME = "volume_name"; // "external_primary" or SD UUID

    // Viewer
    public static final String FOLDER_NAME = "folder_name";
    public static final String START_INDEX = "start_index";
}
