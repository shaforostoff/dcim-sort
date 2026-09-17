package com.shaforostoff.dcimsort.util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Stream copying with a per-thread scratch buffer. Copies happen once per photo on a handful of
 * long-lived pool threads, so one buffer per thread replaces a 64 KB allocation per file — over a
 * large organize run that is the difference between a few hundred KB and hundreds of MB of churn.
 */
public final class Io {
    private Io() {}

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final ThreadLocal<byte[]> BUFFER =
            ThreadLocal.withInitial(() -> new byte[BUFFER_SIZE]);

    /** Copies {@code in} to {@code out} and flushes. Neither stream is closed. */
    public static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = BUFFER.get();
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    /** Copies a whole file into {@code out}, which is left open. */
    public static void copyFileTo(File src, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            copy(in, out);
        }
    }

    public static void copyFile(File src, File dst) throws IOException {
        try (OutputStream out = new FileOutputStream(dst)) {
            copyFileTo(src, out);
        }
    }
}
