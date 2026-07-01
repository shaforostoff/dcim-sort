package com.shaforostoff.dcimsort.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.shaforostoff.dcimsort.util.Sdk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** All MediaStore reads for image buckets. Non-recursive by design. */
public class MediaRepository {

    /** Callback invoked per image row, newest first. Return false to stop iteration. */
    public interface RowCallback {
        boolean onImage(MediaImage img);
    }

    private static final Uri IMAGES = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;

    private final Context appCtx;

    public MediaRepository(Context ctx) {
        this.appCtx = ctx.getApplicationContext();
    }

    private ContentResolver resolver() {
        return appCtx.getContentResolver();
    }

    // ---- Selection helpers --------------------------------------------------

    private static final class Sel {
        final String where;
        final String[] args;
        Sel(String where, String[] args) { this.where = where; this.args = args; }
    }

    /** Builds a non-recursive selection for one folder. */
    private static Sel folderSelection(String relativePath, String dataDir) {
        if (Sdk.atLeastQ() && relativePath != null) {
            String rp = relativePath.endsWith("/") ? relativePath : relativePath + "/";
            return new Sel(MediaStore.MediaColumns.RELATIVE_PATH + " = ?", new String[]{rp});
        }
        // Legacy: match files directly inside dataDir but not in subfolders.
        String dir = dataDir;
        if (dir != null && dir.endsWith("/")) dir = dir.substring(0, dir.length() - 1);
        return new Sel(
                MediaStore.MediaColumns.DATA + " LIKE ? AND " + MediaStore.MediaColumns.DATA + " NOT LIKE ?",
                new String[]{dir + "/%", dir + "/%/%"});
    }

    // ---- Bucket listing -----------------------------------------------------

    /** Lists distinct image buckets with counts, sorted by count descending. */
    public List<Bucket> listBuckets() {
        List<String> proj = new ArrayList<>();
        proj.add(MediaStore.Images.Media.BUCKET_ID);
        proj.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
        if (Sdk.atLeastQ()) proj.add(MediaStore.MediaColumns.RELATIVE_PATH);
        proj.add(MediaStore.MediaColumns.DATA);

        Map<Long, int[]> counts = new LinkedHashMap<>();   // bucketId -> {count}
        Map<Long, Bucket> meta = new LinkedHashMap<>();

        try (Cursor c = resolver().query(IMAGES, proj.toArray(new String[0]), null, null, null)) {
            if (c == null) return new ArrayList<>();
            int iId = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
            int iName = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (c.moveToNext()) {
                if (iId < 0 || c.isNull(iId)) continue;
                long id = c.getLong(iId);
                int[] cnt = counts.get(id);
                if (cnt == null) {
                    cnt = new int[]{0};
                    counts.put(id, cnt);
                    String name = iName >= 0 ? c.getString(iName) : null;
                    String rel = iRel >= 0 ? c.getString(iRel) : null;
                    String data = iData >= 0 ? c.getString(iData) : null;
                    String dir = data != null ? new File(data).getParent() : null;
                    if (TextUtils.isEmpty(name)) {
                        name = rel != null ? rel : (dir != null ? new File(dir).getName() : "?");
                    }
                    meta.put(id, new Bucket(id, name, rel, dir, 0));
                }
                cnt[0]++;
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<Long, Bucket> e : meta.entrySet()) {
            Bucket b = e.getValue();
            int n = counts.get(e.getKey())[0];
            out.add(new Bucket(b.id, b.displayName, b.relativePath, b.dataDir, n));
        }
        Collections.sort(out, new Comparator<Bucket>() {
            @Override public int compare(Bucket a, Bucket b) { return Integer.compare(b.count, a.count); }
        });
        return out;
    }

    /** Finds the camera bucket (DCIM/Camera), or the largest DCIM bucket, else null. */
    public Bucket findDefaultCameraBucket() {
        List<Bucket> buckets = listBuckets();
        // 1) Exact DCIM/Camera by relative path (29+).
        for (Bucket b : buckets) {
            if (b.relativePath != null && equalsPath(b.relativePath, "DCIM/Camera/")) return b;
        }
        // 2) By data dir ending in /DCIM/Camera.
        for (Bucket b : buckets) {
            if (b.dataDir != null && b.dataDir.replace('\\', '/').endsWith("/DCIM/Camera")) return b;
        }
        // 3) Default DCIM path derived directly.
        File dcimCamera = new File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        for (Bucket b : buckets) {
            if (b.dataDir != null && new File(b.dataDir).equals(dcimCamera)) return b;
        }
        // 4) Largest bucket under DCIM.
        for (Bucket b : buckets) {
            boolean inDcim = (b.relativePath != null && b.relativePath.startsWith("DCIM/"))
                    || (b.dataDir != null && b.dataDir.replace('\\', '/').contains("/DCIM/"));
            if (inDcim) return b; // list is already sorted by count desc
        }
        // 5) Fall back to the largest bucket overall.
        return buckets.isEmpty() ? null : buckets.get(0);
    }

    private static boolean equalsPath(String a, String b) {
        String na = a.endsWith("/") ? a : a + "/";
        String nb = b.endsWith("/") ? b : b + "/";
        return na.equalsIgnoreCase(nb);
    }

    // ---- Count + size -------------------------------------------------------

    /** Fast count of images directly in a folder (non-recursive). */
    public int countInFolder(String relativePath, String dataDir) {
        Sel sel = folderSelection(relativePath, dataDir);
        String[] proj = {MediaStore.Images.Media._ID};
        try (Cursor c = resolver().query(IMAGES, proj, sel.where, sel.args, null)) {
            return c == null ? 0 : c.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    /** One cursor pass to compute count and total bytes for a folder. */
    public FolderStats statsForFolder(String relativePath, String dataDir) {
        Sel sel = folderSelection(relativePath, dataDir);
        String[] proj = {MediaStore.MediaColumns.SIZE};
        int count = 0;
        long total = 0;
        try (Cursor c = resolver().query(IMAGES, proj, sel.where, sel.args, null)) {
            if (c == null) return new FolderStats(0, 0);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            while (c.moveToNext()) {
                count++;
                if (iSize >= 0 && !c.isNull(iSize)) total += c.getLong(iSize);
            }
        } catch (Exception e) {
            return new FolderStats(count, total);
        }
        return new FolderStats(count, total);
    }

    // ---- Newest-first iteration ---------------------------------------------

    /** Iterates images in a folder, newest first, invoking {@code cb} per row. */
    public void forEachNewestFirst(String relativePath, String dataDir, RowCallback cb) {
        Sel sel = folderSelection(relativePath, dataDir);

        List<String> proj = new ArrayList<>();
        proj.add(MediaStore.Images.Media._ID);
        proj.add(MediaStore.Images.Media.DISPLAY_NAME);
        proj.add(MediaStore.MediaColumns.DATA);
        proj.add(MediaStore.Images.Media.DATE_TAKEN);
        proj.add(MediaStore.Images.Media.DATE_ADDED);
        proj.add(MediaStore.MediaColumns.SIZE);
        proj.add(MediaStore.MediaColumns.MIME_TYPE);
        proj.add(MediaStore.MediaColumns.WIDTH);
        proj.add(MediaStore.MediaColumns.HEIGHT);
        if (Sdk.atLeastQ()) proj.add(MediaStore.MediaColumns.RELATIVE_PATH);
        if (Sdk.atLeastR()) proj.add(MediaStore.MediaColumns.IS_FAVORITE);

        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor c = resolver().query(IMAGES, proj.toArray(new String[0]), sel.where, sel.args, sort)) {
            if (c == null) return;
            int iId = c.getColumnIndex(MediaStore.Images.Media._ID);
            int iName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int iTaken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            int iAdded = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int iW = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int iH = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int iFav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE);

            while (c.moveToNext()) {
                long id = iId >= 0 ? c.getLong(iId) : -1;
                String name = iName >= 0 ? c.getString(iName) : null;
                String data = iData >= 0 ? c.getString(iData) : null;
                long taken = iTaken >= 0 && !c.isNull(iTaken) ? c.getLong(iTaken) : 0;
                if (taken <= 0 && iAdded >= 0 && !c.isNull(iAdded)) {
                    taken = c.getLong(iAdded) * 1000L; // DATE_ADDED is seconds
                }
                long size = iSize >= 0 && !c.isNull(iSize) ? c.getLong(iSize) : 0;
                String mime = iMime >= 0 ? c.getString(iMime) : null;
                int w = iW >= 0 && !c.isNull(iW) ? c.getInt(iW) : 0;
                int h = iH >= 0 && !c.isNull(iH) ? c.getInt(iH) : 0;
                String rel = iRel >= 0 ? c.getString(iRel) : null;
                boolean fav = iFav >= 0 && !c.isNull(iFav) && c.getInt(iFav) != 0;

                MediaImage img = new MediaImage(id, name, rel, data, taken, size, fav, mime, w, h);
                if (!cb.onImage(img)) return;
            }
        } catch (Exception ignore) {
            // Treat query failures as an empty/partial folder.
        }
    }

    // ---- EXIF original stream ----------------------------------------------

    /**
     * Opens the image's original bytes for EXIF reading. On 29+ requests the un-redacted original
     * so GPS survives (requires ACCESS_MEDIA_LOCATION). Caller closes the stream.
     */
    public InputStream openOriginalForExif(Uri uri) throws IOException {
        Uri toOpen = uri;
        if (Sdk.atLeastQ()) {
            try {
                toOpen = MediaStore.setRequireOriginal(uri);
            } catch (Exception ignore) {
                toOpen = uri;
            }
        }
        InputStream in = resolver().openInputStream(toOpen);
        if (in == null) throw new IOException("Cannot open " + uri);
        return in;
    }
}
