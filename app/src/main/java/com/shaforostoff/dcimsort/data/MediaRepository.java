package com.shaforostoff.dcimsort.data;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.text.TextUtils;

import com.shaforostoff.dcimsort.util.Sdk;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
        Map<String, Bucket> buckets = new LinkedHashMap<>();

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
                Bucket b = buckets.get(key);
                if (b == null) {
                    String name = iName >= 0 ? c.getString(iName) : null;
                    String rel = iRel >= 0 ? c.getString(iRel) : null;
                    String data = iData >= 0 ? c.getString(iData) : null;
                    String dir = data != null ? new File(data).getParent() : null;
                    if (TextUtils.isEmpty(name)) {
                        name = rel != null ? rel : (dir != null ? new File(dir).getName() : "?");
                    }
                    b = new Bucket(id, name, rel, dir, 0, vol);
                    buckets.put(key, b);
                }
                b.count++;
            }
        } catch (Exception e) {
            return new ArrayList<>();
        }

        List<Bucket> out = new ArrayList<>(buckets.values());
        Collections.sort(out, (a, b) -> Integer.compare(b.count, a.count));
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
        // 3) Largest bucket under DCIM.
        for (Bucket b : buckets) {
            boolean inDcim = (b.relativePath != null && b.relativePath.startsWith("DCIM/"))
                    || (b.dataDir != null && b.dataDir.replace('\\', '/').contains("/DCIM/"));
            if (inDcim) return b; // list is already sorted by count desc
        }
        // 4) Fall back to the largest bucket overall.
        return buckets.isEmpty() ? null : buckets.get(0);
    }

    private static boolean equalsPath(String a, String b) {
        String na = a.endsWith("/") ? a : a + "/";
        String nb = b.endsWith("/") ? b : b + "/";
        return na.equalsIgnoreCase(nb);
    }

    // ---- Shared image query plumbing ---------------------------------------

    /** Columns every image query reads. Fixed for the process, so it is built once. */
    private static final String[] PROJECTION = buildProjection();

    private static String[] buildProjection() {
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
        return proj.toArray(new String[0]);
    }

    private static final String SORT_NEWEST_FIRST =
            MediaStore.Images.Media.DATE_TAKEN + " DESC, "
                    + MediaStore.Images.Media.DATE_ADDED + " DESC";

    /** Column indices for {@link #PROJECTION}, resolved once per cursor instead of once per row. */
    private static final class Cols {
        final int id, name, data, taken, added, size, mime, w, h, rel, fav, desc;

        Cols(Cursor c) {
            id = c.getColumnIndex(MediaStore.Images.Media._ID);
            name = c.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME);
            data = c.getColumnIndex(MediaStore.MediaColumns.DATA);
            taken = c.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
            added = c.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
            size = c.getColumnIndex(MediaStore.MediaColumns.SIZE);
            mime = c.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE);
            w = c.getColumnIndex(MediaStore.MediaColumns.WIDTH);
            h = c.getColumnIndex(MediaStore.MediaColumns.HEIGHT);
            rel = c.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH);
            fav = c.getColumnIndex(MediaStore.MediaColumns.IS_FAVORITE);
            desc = c.getColumnIndex(MediaStore.Images.ImageColumns.DESCRIPTION);
        }
    }

    /**
     * Collapses the strings a folder scan repeats on every row — one relative path and a handful of
     * mime types across thousands of photos — to a single instance each. The cursor hands back a
     * fresh String per row, so without this a 20k-photo folder holds 40k redundant Strings alive for
     * as long as the listing does.
     */
    private static final class Interner {
        private final Map<String, String> pool = new HashMap<>();

        String get(String s) {
            if (s == null) return null;
            String existing = pool.putIfAbsent(s, s);
            return existing != null ? existing : s;
        }
    }

    /** Builds one {@link MediaImage} from the cursor's current row. */
    private static MediaImage readRow(Cursor c, Cols k, Interner interner) {
        long id = k.id >= 0 ? c.getLong(k.id) : -1;
        String name = k.name >= 0 ? c.getString(k.name) : null;
        String data = k.data >= 0 ? c.getString(k.data) : null;
        long taken = k.taken >= 0 && !c.isNull(k.taken) ? c.getLong(k.taken) : 0;
        if (taken <= 0 && k.added >= 0 && !c.isNull(k.added)) {
            taken = c.getLong(k.added) * 1000L; // DATE_ADDED is seconds
        }
        long size = k.size >= 0 && !c.isNull(k.size) ? c.getLong(k.size) : 0;
        String mime = k.mime >= 0 ? c.getString(k.mime) : null;
        int w = k.w >= 0 && !c.isNull(k.w) ? c.getInt(k.w) : 0;
        int h = k.h >= 0 && !c.isNull(k.h) ? c.getInt(k.h) : 0;
        String rel = k.rel >= 0 ? c.getString(k.rel) : null;
        boolean fav = k.fav >= 0 && !c.isNull(k.fav) && c.getInt(k.fav) != 0;
        String desc = k.desc >= 0 && !c.isNull(k.desc) ? c.getString(k.desc) : null;
        return new MediaImage(id, name, interner.get(rel), data, taken, size, fav,
                interner.get(mime), w, h, desc);
    }

    // ---- Newest-first iteration ---------------------------------------------

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
        query(baseUri, sel.where, sel.args, cb);
    }

    /** Shared image query: applies {@code where}/{@code args}, newest first, one row per callback. */
    private void query(Uri uri, String where, String[] args, RowCallback cb) {
        try (Cursor c = resolver().query(uri, PROJECTION, where, args, SORT_NEWEST_FIRST)) {
            if (c == null) return;
            Cols k = new Cols(c);
            Interner interner = new Interner();
            while (c.moveToNext()) {
                if (!cb.onImage(readRow(c, k, interner))) return;
            }
        } catch (Exception ignore) {
            // Treat query failures as an empty/partial result.
        }
    }

    // ---- Fetch by picked URIs / ID list ------------------------------------

    /**
     * Resolves photo-picker / document URIs to images.
     *
     * <p>Picker URIs come in two shapes: ones carrying the MediaStore {@code _ID} (photo picker
     * local items {@code .../photopicker/media/1234}, DocumentsProvider {@code .../document/image:1234}),
     * and opaque ones with no usable id (Google Photos cloud picker
     * {@code .../cloudpicker/media/<uuid>}). The first kind is looked up by id; the second becomes a
     * copy-only image that keeps its source URI (see {@link #pickerImage}).
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
            case "image/avif": return ".avif";
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
    private List<MediaImage> fetchByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return Collections.emptyList();
        String[] placeholders = new String[ids.size()];
        String[] args = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            placeholders[i] = "?";
            args[i] = String.valueOf(ids.get(i));
        }
        String where = MediaStore.Images.Media._ID + " IN (" + TextUtils.join(",", placeholders) + ")";
        List<MediaImage> result = new ArrayList<>();
        query(IMAGES, where, args, result::add);
        return result;
    }

    // ---- EXIF original stream ----------------------------------------------

    /**
     * Opens the image's original bytes for EXIF reading. On 29+ requests the un-redacted original
     * so GPS survives (requires ACCESS_MEDIA_LOCATION). Caller closes the stream.
     */
    public InputStream openOriginalForExif(Uri uri) throws IOException {
        if (Sdk.atLeastQ()) {
            try {
                InputStream in = resolver().openInputStream(MediaStore.setRequireOriginal(uri));
                if (in != null) return in;
            } catch (Exception ignore) {
                // Not a MediaStore item (e.g. a cloud picker URI) or ACCESS_MEDIA_LOCATION denied:
                // fall back to the plain stream, where the provider may have redacted GPS.
            }
        }
        InputStream in = resolver().openInputStream(uri);
        if (in == null) throw new IOException("Cannot open " + uri);
        return in;
    }
}
