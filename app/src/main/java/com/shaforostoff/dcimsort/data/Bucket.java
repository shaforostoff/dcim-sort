package com.shaforostoff.dcimsort.data;

/** A MediaStore image bucket ("folder"). */
public class Bucket {
    public final long id;
    public final String displayName;
    public final String relativePath; // 29+, e.g. "DCIM/Camera/"; null on legacy
    public final String dataDir;       // absolute dir path (legacy/best-effort), no trailing slash
    public final int count;

    public Bucket(long id, String displayName, String relativePath, String dataDir, int count) {
        this.id = id;
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.dataDir = dataDir;
        this.count = count;
    }
}
