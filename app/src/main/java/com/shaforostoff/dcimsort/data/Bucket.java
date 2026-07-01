package com.shaforostoff.dcimsort.data;

/** A MediaStore image bucket ("folder"). */
public class Bucket {
    public final long id;
    public final String displayName;
    public final String relativePath; // 29+, e.g. "DCIM/Camera/"; null on legacy
    public final String dataDir;       // absolute dir path (legacy/best-effort), no trailing slash
    public final int count;
    public final String volumeName;    // 29+: "external_primary" = internal, else SD UUID; null on legacy

    public Bucket(long id, String displayName, String relativePath, String dataDir, int count) {
        this(id, displayName, relativePath, dataDir, count, null);
    }

    public Bucket(long id, String displayName, String relativePath, String dataDir, int count,
                  String volumeName) {
        this.id = id;
        this.displayName = displayName;
        this.relativePath = relativePath;
        this.dataDir = dataDir;
        this.count = count;
        this.volumeName = volumeName;
    }
}
