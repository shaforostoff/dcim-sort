package com.shaforostoff.dcimsort.data;

/** Count and total byte size of images in a folder (one cursor pass). */
public class FolderStats {
    public final int count;
    public final long totalBytes;

    public FolderStats(int count, long totalBytes) {
        this.count = count;
        this.totalBytes = totalBytes;
    }
}
