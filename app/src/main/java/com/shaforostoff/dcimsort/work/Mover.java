package com.shaforostoff.dcimsort.work;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.provider.MediaStore;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaImage;
import com.shaforostoff.dcimsort.util.Sdk;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Relocates originals (lossless move) and publishes recompressed files. On 29+ uses MediaStore
 * (RELATIVE_PATH update / pending insert); on legacy uses direct file operations. The recompress
 * publish follows write→commit→verify→delete-original ordering so a stop never leaves both files.
 */
public class Mover {
    public enum Outcome { MOVED, SKIPPED, FAILED }

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    private final Context ctx;
    private final OrganizeJournal journal;

    public Mover(Context ctx, OrganizeJournal journal) {
        this.ctx = ctx.getApplicationContext();
        this.journal = journal;
    }

    private ContentResolver resolver() {
        return ctx.getContentResolver();
    }

    // ---- Path helpers -------------------------------------------------------

    static String childRelativePath(String sourceRel, String folder) {
        String base = sourceRel;
        if (base == null || base.isEmpty()) base = "DCIM/Camera/";
        if (!base.endsWith("/")) base += "/";
        return base + folder + "/";
    }

    static String baseName(String name) {
        if (name == null) return "img";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        String base = baseName(name);
        String ext = name.substring(base.length());
        for (int i = 1; i < 10000; i++) {
            File cand = new File(dir, base + "_" + i + ext);
            if (!cand.exists()) return cand;
        }
        return new File(dir, base + "_" + System.nanoTime() + ext);
    }

    // ---- Move (no recompress) ----------------------------------------------

    public Outcome move(MediaImage img, String sourceRel, String folder) {
        if (Sdk.atLeastQ()) {
            return moveViaMediaStore(img, sourceRel, folder);
        }
        return moveLegacy(img, folder);
    }

    private Outcome moveViaMediaStore(MediaImage img, String sourceRel, String folder) {
        String newRel = childRelativePath(sourceRel, folder);
        if (img.relativePath != null && img.relativePath.equalsIgnoreCase(newRel)) {
            return Outcome.SKIPPED;
        }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, newRel);
        try {
            int n = resolver().update(img.contentUri(), cv, null, null);
            return n > 0 ? Outcome.MOVED : Outcome.FAILED;
        } catch (Exception e) {
            return Outcome.FAILED;
        }
    }

    private Outcome moveLegacy(MediaImage img, String folder) {
        if (img.dataPath == null) return Outcome.FAILED;
        File srcFile = new File(img.dataPath);
        if (!srcFile.exists()) return Outcome.FAILED;
        File parent = srcFile.getParentFile();
        if (parent == null) return Outcome.FAILED;
        if (parent.getName().equalsIgnoreCase(folder)) return Outcome.SKIPPED;
        File destDir = new File(parent, folder);
        if (!destDir.exists() && !destDir.mkdirs()) return Outcome.FAILED;
        File dst = uniqueFile(destDir, srcFile.getName());
        boolean ok = srcFile.renameTo(dst);
        if (!ok) {
            try {
                copyFile(srcFile, dst);
                ok = srcFile.delete();
            } catch (IOException e) {
                dst.delete();
                return Outcome.FAILED;
            }
        }
        scan(srcFile.getAbsolutePath(), dst.getAbsolutePath());
        return ok ? Outcome.MOVED : Outcome.FAILED;
    }

    // ---- Publish recompressed ----------------------------------------------

    public boolean publishRecompressed(MediaImage img, File temp, CompressMode mode,
                                       String sourceRel, String folder) {
        if (Sdk.atLeastQ()) {
            return publishViaMediaStore(img, temp, mode, sourceRel, folder);
        }
        return publishLegacy(img, temp, mode, folder);
    }

    private boolean publishViaMediaStore(MediaImage img, File temp, CompressMode mode,
                                         String sourceRel, String folder) {
        String newRel = childRelativePath(sourceRel, folder);
        String newName = baseName(img.displayName) + Recompressor.extensionFor(mode);

        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, newName);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, Recompressor.mimeFor(mode));
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, newRel);
        cv.put(MediaStore.MediaColumns.IS_PENDING, 1);
        if (img.favorite && Sdk.atLeastR()) {
            cv.put(MediaStore.MediaColumns.IS_FAVORITE, 1);
        }

        Uri newUri;
        try {
            newUri = resolver().insert(IMAGES, cv);
        } catch (Exception e) {
            return false;
        }
        if (newUri == null) return false;

        journal.begin(img.id, newUri);
        try {
            try (OutputStream os = resolver().openOutputStream(newUri)) {
                if (os == null) throw new IOException("null output stream");
                writeFileTo(temp, os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver().update(newUri, done, null, null);

            if (!verify(newUri)) {
                safeDelete(newUri);
                journal.abort(img.id);
                return false;
            }
            // Commit succeeded; delete the original (write/delete consent already granted on 30+).
            try {
                resolver().delete(img.contentUri(), null, null);
            } catch (Exception e) {
                // Original could not be deleted: roll back the new file to avoid duplicates.
                safeDelete(newUri);
                journal.abort(img.id);
                return false;
            }
            journal.complete(img.id);
            return true;
        } catch (Exception e) {
            safeDelete(newUri);
            journal.abort(img.id);
            return false;
        }
    }

    private boolean publishLegacy(MediaImage img, File temp, CompressMode mode, String folder) {
        if (img.dataPath == null) return false;
        File srcFile = new File(img.dataPath);
        File parent = srcFile.getParentFile();
        if (parent == null) return false;
        File destDir = new File(parent, folder);
        if (!destDir.exists() && !destDir.mkdirs()) return false;
        File dst = uniqueFile(destDir, baseName(srcFile.getName()) + Recompressor.extensionFor(mode));
        File tmp = new File(destDir, "." + dst.getName() + ".tmp");
        try {
            copyFile(temp, tmp);
            if (!tmp.renameTo(dst)) {
                tmp.delete();
                return false;
            }
            scan(dst.getAbsolutePath());
            boolean del = srcFile.delete();
            scan(srcFile.getAbsolutePath());
            return del;
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
    }

    private boolean verify(Uri uri) {
        try (Cursor c = resolver().query(uri, new String[]{MediaStore.MediaColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return !c.isNull(0) && c.getLong(0) > 0;
            }
        } catch (Exception ignore) {
        }
        return false;
    }

    private void safeDelete(Uri uri) {
        try {
            resolver().delete(uri, null, null);
        } catch (Exception ignore) {
        }
    }

    private void scan(String... paths) {
        try {
            MediaScannerConnection.scanFile(ctx, paths, null, null);
        } catch (Exception ignore) {
        }
    }

    private static void writeFileTo(File src, OutputStream out) throws IOException {
        try (FileInputStream in = new FileInputStream(src)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        try (FileInputStream in = new FileInputStream(src);
             OutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
        }
    }
}
