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
        if (Sdk.atLeastQ()) proj.add(MediaStore.MediaColumns.VOLUME_NAME);
        proj.add(MediaStore.MediaColumns.DATA);

        // Key: bucketId + ":" + volumeName to distinguish same-name folders on different volumes.
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, Bucket> meta = new LinkedHashMap<>();

        try (Cursor c = resolver().query(IMAGES, proj.toArray(new String[0]), null, null, null)) {
            if (c == null) return new ArrayList<>();
            int iId = c.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
            int iName = c.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            int iRel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int iVol = c.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            while (c.moveToNext()) {
                if (iId < 0 || c.isNull(iId)) continue;
                long id = c.getLong(iId);
                String vol = iVol >= 0 ? c.getString(iVol) : null;
                String key = id + ":" + vol;
                int[] cnt = counts.get(key);
                if (cnt == null) {
                    cnt = new int[]{0};
                    counts.put(key, cnt);
                    String name = iName >= 0 ? c.getString(iName) : null;
                    String rel = iRel >= 0 ? c.getString(iRel) : null;
                    String data = iData >= 0 ? c.getString(iData) : null;
                    String dir = data != null ? new File(data).getParent() : null;
                    if (TextUtils.isEmpty(name)) {
                        name = rel != null ? rel : (dir != null ? new File(dir).getName() : "?");
                    }
                    meta.put(key, new Bucket(id, name, rel, dir, 0, vol));
                }
                cnt[0]++;
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<String, Bucket> e : meta.entrySet()) {
            Bucket b = e.getValue();
            int n = counts.get(e.getKey())[0];
            out.add(new Bucket(b.id, b.displayName, b.relativePath, b.dataDir, n, b.volumeName));
        }
        Collections.sort(out, new Comparator<Bucket>() {
            @Override public int compare(Bucket a, Bucket b) { return Integer.compare(b.count, a.count); }
        });
        return out;
    }

    /** Finds the camera bucket (DCIM/Camera), or the largest DCIM bucket, else null. */
    public Bucket findDefaultCameraBucket() {
        List<Bucket> buckets = listBuckets();
        // 1) Exact DCIM/Camera on internal storage first, then any volume.
        Bucket anyCamera = null;
        for (Bucket b : buckets) {
            if (b.relativePath != null && equalsPath(b.relativePath, "DCIM/Camera/")) {
                if ("external_primary".equals(b.volumeName)) return b;
                if (anyCamera == null) anyCamera = b;
            }
        }
        if (anyCamera != null) return anyCamera;
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

    // ---- Newest-first iteration ---------------------------------------------

    /** Iterates images in a folder, newest first, invoking {@code cb} per row. */
    public void forEachNewestFirst(String relativePath, String dataDir, RowCallback cb) {
        forEachNewestFirst(relativePath, dataDir, null, cb);
    }

    /** Iterates images in a folder on a specific storage volume, newest first. */
    public void forEachNewestFirst(String relativePath, String dataDir, String volumeName,
                                   RowCallback cb) {
        // On API 29+, use a volume-specific URI so files from other volumes with the same
        // relative path (e.g. both internal and SD card having DCIM/Camera/) are not mixed in.
        Uri baseUri = IMAGES;
        if (Sdk.atLeastQ() && volumeName != null) {
            try {
                baseUri = MediaStore.Images.Media.getContentUri(volumeName);
            } catch (Exception ignore) {}
        }
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
        proj.add(MediaStore.Images.ImageColumns.DESCRIPTION);

        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC";

        try (Cursor c = resolver().query(baseUri, proj.toArray(new String[0]), sel.where, sel.args, sort)) {
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
            int iDesc = c.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION);

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
                String desc = iDesc >= 0 && !c.isNull(iDesc) ? c.getString(iDesc) : null;

                MediaImage img = new MediaImage(id, name, rel, data, taken, size, fav, mime, w, h, desc);
                if (!cb.onImage(img)) return;
            }
        } catch (Exception ignore) {
            // Treat query failures as an empty/partial folder.
        }
    }

    // ---- Fetch by picked URIs / ID list ------------------------------------

    /**
     * Resolves photo-picker / document URIs to on-device MediaStore rows.
     *
     * <p>Picker URIs come in two shapes: ones carrying the MediaStore {@code _ID} (photo picker
     * local items {@code .../photopicker/media/1234}, DocumentsProvider {@code .../document/image:1234}),
     * and opaque ones with no usable id (Google Photos cloud picker
     * {@code .../cloudpicker/media/<uuid>}). The first kind is looked up by id; the second is matched
     * to a local row by display name + size. Cloud-only photos that aren't on the device have no
     * MediaStore row and are silently dropped — they can't be moved or recompressed anyway.
     */
    public List<MediaImage> fetchByUris(List<Uri> uris) {
        if (uris == null || uris.isEmpty()) return Collections.emptyList();
        List<Long> ids = new ArrayList<>();
        List<Uri> cloud = new ArrayList<>();
        for (Uri u : uris) {
            long id = idFromUri(u);
            if (id >= 0) ids.add(id); else cloud.add(u);
        }
        List<MediaImage> out = new ArrayList<>(fetchByIds(ids));
        // Picks with no MediaStore row (Google Photos cloud picker etc.): keep them as
        // copy-only images carrying their source URI; their bytes are read at organize time.
        for (Uri u : cloud) {
            MediaImage m = pickerImage(u);
            if (m != null) out.add(m);
        }
        return out;
    }

    /** Numeric MediaStore {@code _ID} from a URI's last segment, or -1 if it isn't a plain id. */
    private static long idFromUri(Uri uri) {
        if (uri == null) return -1;
        String seg = uri.getLastPathSegment();
        if (seg == null) return -1;
        int colon = seg.lastIndexOf(':'); // DocumentsProvider form "image:1234"
        if (colon >= 0) seg = seg.substring(colon + 1);
        if (seg.isEmpty()) return -1;
        for (int i = 0; i < seg.length(); i++) {
            if (!Character.isDigit(seg.charAt(i))) return -1; // cloud uuids etc.
        }
        try { return Long.parseLong(seg); }
        catch (NumberFormatException e) { return -1; }
    }

    /**
     * Builds a copy-only {@link MediaImage} for a picked URI that has no MediaStore row (id = -1,
     * sourceUri set). Reads whatever metadata the picker exposes (display name, size, date taken,
     * dimensions). Output naming prefers a date-based name since cloud picks carry synthetic names.
     */
    private MediaImage pickerImage(Uri uri) {
        String name = null, mime = null;
        long size = 0, taken = 0;
        int w = 0, h = 0;
        try (Cursor c = resolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = colString(c, android.provider.OpenableColumns.DISPLAY_NAME);
                size = colLong(c, android.provider.OpenableColumns.SIZE);
                mime = colString(c, MediaStore.MediaColumns.MIME_TYPE);
                taken = colLong(c, MediaStore.MediaColumns.DATE_TAKEN);
                w = (int) colLong(c, MediaStore.MediaColumns.WIDTH);
                h = (int) colLong(c, MediaStore.MediaColumns.HEIGHT);
            }
        } catch (Exception ignore) {}
        if (mime == null) {
            try { mime = resolver().getType(uri); } catch (Exception ignore) {}
        }
        if (mime == null) mime = "image/jpeg";
        // Cloud picks expose an opaque UUID filename; synthesize a clean date-based name instead.
        String ext = extensionForMime(mime);
        if (taken > 0) {
            name = "IMG_" + DATE_NAME_FMT.format(new java.util.Date(taken)) + ext;
        } else if (TextUtils.isEmpty(name)) {
            name = "IMG" + ext;
        }
        return new MediaImage(-1, name, null, null, taken, size, false, mime, w, h, null, uri);
    }

    private static final java.text.SimpleDateFormat DATE_NAME_FMT =
            new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US);

    private static String extensionForMime(String mime) {
        if (mime == null) return ".jpg";
        switch (mime) {
            case "image/png": return ".png";
            case "image/webp": return ".webp";
            case "image/heic": case "image/heif": return ".heic";
            case "image/gif": return ".gif";
            default: return ".jpg";
        }
    }

    private static String colString(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getString(i) : null;
    }

    private static long colLong(Cursor c, String col) {
        int i = c.getColumnIndex(col);
        return i >= 0 && !c.isNull(i) ? c.getLong(i) : 0;
    }

    /** Returns MediaImage objects for the given MediaStore IDs, sorted by DATE_TAKEN DESC. */
    public List<MediaImage> fetchByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String[] placeholders = new String[ids.size()];
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            placeholders[i] = "?";
            args[i] = String.valueOf(ids.get(i));
        }
        String where = MediaStore.Images.Media._ID + " IN (" + TextUtils.join(",", placeholders) + ")";
        return queryImages(where, args);
    }

    /** Shared image query: applies {@code where}/{@code args}, sorts newest first, builds rows. */
    private List<MediaImage> queryImages(String where, String[] args) {
        String sort = MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                + MediaStore.Images.Media.DATE_ADDED + " DESC";

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
        proj.add(MediaStore.Images.ImageColumns.DESCRIPTION);

        List<MediaImage> result = new ArrayList<>();
        try (Cursor c = resolver().query(IMAGES, proj.toArray(new String[0]), where, args, sort)) {
            if (c == null) return result;
            int iId   = c.getColumnIndex(MediaStore.Images.Media._ID);
            int iName = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            int iData = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            int iTaken= c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            int iAdded= c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            int iSize = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            int iMime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            int iW    = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            int iH    = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            int iRel  = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            int iFav  = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE);
            int iDesc = c.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION);
            while (c.moveToNext()) {
                long id   = iId   >= 0 ? c.getLong(iId)   : -1;
                String name = iName >= 0 ? c.getString(iName) : null;
                String data = iData >= 0 ? c.getString(iData) : null;
                long taken  = iTaken >= 0 && !c.isNull(iTaken) ? c.getLong(iTaken) : 0;
                if (taken <= 0 && iAdded >= 0 && !c.isNull(iAdded))
                    taken = c.getLong(iAdded) * 1000L;
                long size  = iSize >= 0 && !c.isNull(iSize) ? c.getLong(iSize) : 0;
                String mime = iMime >= 0 ? c.getString(iMime) : null;
                int w  = iW >= 0 && !c.isNull(iW) ? c.getInt(iW) : 0;
                int h  = iH >= 0 && !c.isNull(iH) ? c.getInt(iH) : 0;
                String rel  = iRel  >= 0 ? c.getString(iRel)  : null;
                boolean fav = iFav  >= 0 && !c.isNull(iFav) && c.getInt(iFav) != 0;
                String desc = iDesc >= 0 && !c.isNull(iDesc) ? c.getString(iDesc) : null;
                result.add(new MediaImage(id, name, rel, data, taken, size, fav, mime, w, h, desc));
            }
        } catch (Exception ignore) {}
        return result;
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
