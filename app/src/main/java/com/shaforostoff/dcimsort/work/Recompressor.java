package com.shaforostoff.dcimsort.work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Size;

import androidx.exifinterface.media.ExifInterface;
import androidx.heifwriter.AvifWriter;
import androidx.heifwriter.HeifWriter;

import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Sdk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes, re-encodes (WebP/HEIC/AVIF) and re-injects metadata. The output is written to a temp file
 * in the cache dir; callers publish it (MediaStore insert or legacy file write). Honest limitation:
 * ICC/wide-gamut profiles are lost (pixels normalize to sRGB). EXIF/GPS is re-injected for WebP via
 * ExifInterface; for HEIC/AVIF (which ExifInterface can only read) we splice an {@code Exif} metadata
 * item directly into the ISO-BMFF/HEIF container ({@link #injectExifIntoHeif}). XMP is not carried
 * into HEIC/AVIF (it would need a separate {@code mime} item).
 */
public class Recompressor {
    /** Cap on the longest side so huge sensors don't OOM and stay within HEVC encoder limits. */
    private static final int MAX_LONG_SIDE = 4096;
    /** Stop timeout for the HEVC/AV1 image writers. */
    private static final long ENCODE_TIMEOUT_US = 12_000_000L;

    private static Boolean heicEncoderCached;
    private static Boolean avifEncoderCached;

    private final Context ctx;
    private final MediaRepository repo;

    public Recompressor(Context ctx, MediaRepository repo) {
        this.ctx = ctx.getApplicationContext();
        this.repo = repo;
    }

    public static String extensionFor(CompressMode mode) {
        switch (mode) {
            case HEIC: return ".heic";
            case AVIF: return ".avif";
            default: return ".webp";
        }
    }

    public static String mimeFor(CompressMode mode) {
        switch (mode) {
            case HEIC: return "image/heic";
            case AVIF: return "image/avif";
            default: return "image/webp";
        }
    }

    /** True if this device can encode HEIC (API 28+ and an HEVC encoder is present). */
    public static synchronized boolean hasHeicEncoder() {
        if (heicEncoderCached != null) return heicEncoderCached;
        boolean ok = false;
        if (Sdk.atLeastP()) {
            try {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                for (MediaCodecInfo info : list.getCodecInfos()) {
                    if (!info.isEncoder()) continue;
                    for (String t : info.getSupportedTypes()) {
                        if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(t)) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) break;
                }
            } catch (Throwable ignore) {
                ok = false;
            }
        }
        heicEncoderCached = ok;
        return ok;
    }

    /** True if this device can encode AVIF (Android 16+ and an AV1 encoder is present). */
    public static synchronized boolean hasAvifEncoder() {
        if (avifEncoderCached != null) return avifEncoderCached;
        boolean ok = false;
        if (Sdk.atLeastBaklava()) {
            try {
                MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                for (MediaCodecInfo info : list.getCodecInfos()) {
                    if (!info.isEncoder()) continue;
                    for (String t : info.getSupportedTypes()) {
                        if (MediaFormat.MIMETYPE_VIDEO_AV1.equalsIgnoreCase(t)) {
                            ok = true;
                            break;
                        }
                    }
                    if (ok) break;
                }
            } catch (Throwable ignore) {
                ok = false;
            }
        }
        avifEncoderCached = ok;
        return ok;
    }

    /**
     * Produces a compressed temp file (in cacheDir) with metadata re-injected. Caller deletes it.
     * @return the temp file, or null on failure.
     */
    public File compressToTemp(Uri source, CompressMode mode, int quality) {
        Bitmap bmp = decodeOriented(source, MAX_LONG_SIDE);
        if (bmp == null) return null;
        File out = null;
        try {
            out = File.createTempFile("cmp_", extensionFor(mode), ctx.getCacheDir());
            boolean ok = encodeBitmap(bmp, mode, quality, out);
            if (!ok) {
                out.delete();
                return null;
            }
            reinjectExif(source, out, mode);
            return out;
        } catch (IOException e) {
            if (out != null) out.delete();
            return null;
        } finally {
            bmp.recycle();
        }
    }

    /** Size in bytes the image would occupy after compression, or -1 on failure. */
    public long encodedSize(Uri source, CompressMode mode, int quality) {
        File f = compressToTemp(source, mode, quality);
        if (f == null) return -1;
        long len = f.length();
        f.delete();
        return len;
    }

    // ---- Decoding -----------------------------------------------------------

    /** Decodes an image with EXIF orientation applied to pixels, downsampled to maxLongSide. */
    public Bitmap decodeOriented(Uri uri, int maxLongSide) {
        try {
            if (Sdk.atLeastP()) {
                return decodeWithImageDecoder(uri, maxLongSide);
            }
            return decodeLegacy(uri, maxLongSide);
        } catch (Throwable t) {
            // OOM or decode failure: retry once at half size.
            if (maxLongSide > 1024) {
                try {
                    return decodeOriented(uri, maxLongSide / 2);
                } catch (Throwable ignore) {
                    return null;
                }
            }
            return null;
        }
    }

    private Bitmap decodeWithImageDecoder(Uri uri, int maxLongSide) throws IOException {
        ImageDecoder.Source src = ImageDecoder.createSource(ctx.getContentResolver(), uri);
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            decoder.setMutableRequired(false);
            if (maxLongSide > 0) {
                Size sz = info.getSize();
                int longest = Math.max(sz.getWidth(), sz.getHeight());
                if (longest > maxLongSide) {
                    float scale = maxLongSide / (float) longest;
                    decoder.setTargetSize(
                            Math.max(1, Math.round(sz.getWidth() * scale)),
                            Math.max(1, Math.round(sz.getHeight() * scale)));
                }
            }
        });
    }

    private Bitmap decodeLegacy(Uri uri, int maxLongSide) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int sample = 1;
        if (maxLongSide > 0) {
            int longest = Math.max(bounds.outWidth, bounds.outHeight);
            while (longest / sample > maxLongSide) sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, opts);
        }
        if (bmp == null) return null;
        return applyExifRotation(uri, bmp);
    }

    private Bitmap applyExifRotation(Uri uri, Bitmap bmp) {
        try (InputStream in = repo.openOriginalForExif(uri)) {
            ExifInterface exif = new ExifInterface(in);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            android.graphics.Matrix m = new android.graphics.Matrix();
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90: m.postRotate(90); break;
                case ExifInterface.ORIENTATION_ROTATE_180: m.postRotate(180); break;
                case ExifInterface.ORIENTATION_ROTATE_270: m.postRotate(270); break;
                case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.postScale(-1, 1); break;
                case ExifInterface.ORIENTATION_FLIP_VERTICAL: m.postScale(1, -1); break;
                default: return bmp;
            }
            Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            if (rotated != bmp) bmp.recycle();
            return rotated;
        } catch (Exception e) {
            return bmp;
        }
    }

    // ---- Encoding -----------------------------------------------------------

    private boolean encodeBitmap(Bitmap bmp, CompressMode mode, int quality, File out) {
        if (mode == CompressMode.WEBP) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                Bitmap.CompressFormat fmt = Sdk.atLeastR()
                        ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
                return bmp.compress(fmt, quality, fos);
            } catch (IOException e) {
                return false;
            }
        } else if (mode == CompressMode.HEIC) {
            return encodeHeic(bmp, quality, out);
        } else if (mode == CompressMode.AVIF) {
            return encodeAvif(bmp, quality, out);
        }
        return false;
    }

    private boolean encodeHeic(Bitmap src, int quality, File out) {
        if (!Sdk.atLeastP()) return false;
        // HEVC encoders require even dimensions.
        Bitmap bmp = src;
        int w = src.getWidth() & ~1;
        int h = src.getHeight() & ~1;
        if (w != src.getWidth() || h != src.getHeight()) {
            bmp = Bitmap.createBitmap(src, 0, 0, Math.max(2, w), Math.max(2, h));
        }
        HeifWriter writer = null;
        try {
            writer = new HeifWriter.Builder(
                    out.getAbsolutePath(), bmp.getWidth(), bmp.getHeight(), HeifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                    .build();
            writer.start();
            writer.addBitmap(bmp);
            writer.stop(ENCODE_TIMEOUT_US);
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignore) {}
            }
            if (bmp != src) bmp.recycle();
        }
    }

    private boolean encodeAvif(Bitmap src, int quality, File out) {
        if (!Sdk.atLeastBaklava()) return false;
        // AV1 encoders require even dimensions.
        Bitmap bmp = src;
        int w = src.getWidth() & ~1;
        int h = src.getHeight() & ~1;
        if (w != src.getWidth() || h != src.getHeight()) {
            bmp = Bitmap.createBitmap(src, 0, 0, Math.max(2, w), Math.max(2, h));
        }
        AvifWriter writer = null;
        try {
            writer = new AvifWriter.Builder(
                    out.getAbsolutePath(), bmp.getWidth(), bmp.getHeight(), AvifWriter.INPUT_MODE_BITMAP)
                    .setQuality(quality)
                    .setMaxImages(1)
                    .build();
            writer.start();
            writer.addBitmap(bmp);
            writer.stop(ENCODE_TIMEOUT_US);
            return out.length() > 0;
        } catch (Exception e) {
            return false;
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignore) {}
            }
            if (bmp != src) bmp.recycle();
        }
    }

    // ---- Metadata re-injection ---------------------------------------------

    private static final String[] COPY_TAGS = {
            ExifInterface.TAG_IMAGE_DESCRIPTION,
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_XMP,
    };

    /**
     * Copies EXIF/GPS/XMP from source into the output. WebP is written directly by ExifInterface;
     * HEIC/AVIF get an {@code Exif} item spliced into the HEIF container. Best effort throughout —
     * the recompressed pixels are valid even if metadata fails.
     */
    private void reinjectExif(Uri source, File out, CompressMode mode) {
        try {
            ExifInterface srcExif;
            try (InputStream in = repo.openOriginalForExif(source)) {
                srcExif = new ExifInterface(in);
            }
            if (mode == CompressMode.WEBP) {
                ExifInterface dst = new ExifInterface(out.getAbsolutePath());
                for (String tag : COPY_TAGS) {
                    String v = srcExif.getAttribute(tag);
                    if (v != null) dst.setAttribute(tag, v);
                }
                // Pixels were normalized upright on decode → record normal orientation.
                dst.setAttribute(ExifInterface.TAG_ORIENTATION,
                        String.valueOf(ExifInterface.ORIENTATION_NORMAL));
                dst.saveAttributes();
            } else if (mode == CompressMode.HEIC || mode == CompressMode.AVIF) {
                byte[] payload = buildHeifExifPayload(srcExif);
                if (payload != null) injectExifIntoHeif(out, payload);
            }
        } catch (Exception ignore) {
            // Best effort; the recompressed pixels are still valid without metadata.
        }
    }

    // ---- HEIF (HEIC/AVIF) EXIF item injection -------------------------------
    //
    // HEIC and AVIF share the ISO-BMFF/HEIF container, so the same surgery serves both: add an
    // 'Exif' item to the `meta` box (an `infe` entry in `iinf`, a `cdsc` link in `iref` from the
    // Exif item to the primary image, and an `iloc` extent) and append the payload in a new `mdat`
    // at end of file. Because `meta` grows, every file-offset (construction_method 0) extent that
    // sits after `meta` shifts by the growth delta and is patched. The parser is generic: it walks
    // whatever box layout the platform writer emits rather than assuming a fixed structure.

    /**
     * Builds the HEIF Exif item payload: a 4-byte tiff-header-offset (0) followed by the TIFF/Exif
     * block. We let ExifInterface write the tags into a throwaway 1x1 JPEG, then lift the APP1 TIFF
     * block out of it — reusing the exact tag set and orientation handling as the WebP path.
     * @return the payload, or null if there was nothing to write / extraction failed.
     */
    private byte[] buildHeifExifPayload(ExifInterface srcExif) {
        File tmp = null;
        try {
            tmp = File.createTempFile("exif_", ".jpg", ctx.getCacheDir());
            Bitmap one = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                one.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            } finally {
                one.recycle();
            }
            ExifInterface dst = new ExifInterface(tmp.getAbsolutePath());
            boolean any = false;
            for (String tag : COPY_TAGS) {
                if (ExifInterface.TAG_XMP.equals(tag)) continue; // XMP is not part of the Exif TIFF block.
                String v = srcExif.getAttribute(tag);
                if (v != null) {
                    dst.setAttribute(tag, v);
                    any = true;
                }
            }
            // Pixels were normalized upright on decode → record normal orientation.
            dst.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.saveAttributes();
            if (!any) return null;
            byte[] tiff = extractExifTiff(readAll(tmp));
            if (tiff == null) return null;
            byte[] payload = new byte[4 + tiff.length]; // leading uint32 tiff-header-offset stays 0.
            System.arraycopy(tiff, 0, payload, 4, tiff.length);
            return payload;
        } catch (Exception e) {
            return null;
        } finally {
            if (tmp != null) tmp.delete();
        }
    }

    /** Lifts the TIFF block out of the Exif APP1 segment of a JPEG (the bytes after "Exif\0\0"). */
    private static byte[] extractExifTiff(byte[] j) {
        if (j.length < 4 || (j[0] & 0xFF) != 0xFF || (j[1] & 0xFF) != 0xD8) return null;
        int i = 2;
        while (i + 4 <= j.length) {
            if ((j[i] & 0xFF) != 0xFF) { i++; continue; }
            int marker = j[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0xD9) { i += 2; continue; }
            if (marker == 0xDA) break; // start of scan: no more metadata segments
            int len = ((j[i + 2] & 0xFF) << 8) | (j[i + 3] & 0xFF);
            int segStart = i + 4;
            int segLen = len - 2;
            if (segLen < 0 || segStart + segLen > j.length) break;
            if (marker == 0xE1 && segLen >= 6
                    && j[segStart] == 'E' && j[segStart + 1] == 'x' && j[segStart + 2] == 'i'
                    && j[segStart + 3] == 'f' && j[segStart + 4] == 0 && j[segStart + 5] == 0) {
                byte[] tiff = new byte[segLen - 6];
                System.arraycopy(j, segStart + 6, tiff, 0, tiff.length);
                return tiff;
            }
            i = segStart + segLen;
        }
        return null;
    }

    /**
     * Splices {@code exifPayload} into the HEIF file as an Exif item. Returns true on success or if
     * the file already carries an Exif item; false (file left untouched) on any structural surprise.
     */
    private boolean injectExifIntoHeif(File file, byte[] exifPayload) {
        try {
            byte[] data = readAll(file);
            Box meta = find(parseBoxes(data, 0, data.length), "meta");
            if (meta == null) return false;
            int metaEnd = (int) (meta.start + meta.size);
            List<Box> children = parseBoxes(data, meta.contentStart + 4, metaEnd); // +4 skips meta ver/flags
            Box iinfB = find(children, "iinf");
            Box ilocB = find(children, "iloc");
            Box pitmB = find(children, "pitm");
            Box irefB = find(children, "iref");
            if (iinfB == null || ilocB == null || pitmB == null) return false;

            IinfInfo iinf = parseIinf(data, iinfB);
            if (iinf.hasExif) return true; // already present — don't duplicate

            int pv = data[pitmB.contentStart] & 0xFF;
            int primaryId = pv == 0 ? u16(data, pitmB.contentStart + 4)
                    : (int) u32(data, pitmB.contentStart + 4);

            List<IlocItem> items = parseIloc(data, ilocB);
            int maxId = Math.max(iinf.maxId, primaryId);
            for (IlocItem it : items) if (it.id > maxId) maxId = it.id;
            int newId = maxId + 1;

            byte[] newInfe = buildExifInfe(newId);
            byte[] newIinf = buildIinf(data, iinfB, iinf, newInfe);
            boolean irefExisted = irefB != null;
            byte[] newIref = buildIref(data, irefB, newId, primaryId);

            // Add the Exif item to iloc with a placeholder offset so we can size everything first.
            IlocItem exifItem = new IlocItem();
            exifItem.id = newId;
            exifItem.method = 0;
            exifItem.dataRefIndex = 0;
            exifItem.extents = new ArrayList<>();
            exifItem.extents.add(new long[]{0, exifPayload.length});
            items.add(exifItem);
            int newIlocLen = serializeIloc(items).length; // offset values don't affect length

            // New meta size, hence the byte delta that shifts everything after meta.
            long metaContent = 4; // meta ver/flags
            for (Box c : children) {
                if (c == iinfB) metaContent += newIinf.length;
                else if (c == ilocB) metaContent += newIlocLen;
                else if (c == irefB) metaContent += newIref.length;
                else metaContent += c.size;
            }
            if (!irefExisted) metaContent += newIref.length;
            long newMetaSize = 8 + metaContent;
            long delta = newMetaSize - meta.size;

            // Patch existing file-offset extents (only those located after meta move).
            for (IlocItem it : items) {
                if (it == exifItem || it.method != 0) continue;
                for (long[] ex : it.extents) if (ex[0] >= meta.start) ex[0] += delta;
            }
            exifItem.extents.get(0)[0] = (long) data.length + delta + 8; // +8 for the appended mdat header
            byte[] newIloc = serializeIloc(items);

            // Reassemble meta, preserving child order; insert iref after iinf if it didn't exist.
            ByteArrayOutputStream metaC = new ByteArrayOutputStream();
            metaC.write(data, meta.contentStart, 4);
            for (Box c : children) {
                if (c == iinfB) {
                    metaC.write(newIinf, 0, newIinf.length);
                    if (!irefExisted) metaC.write(newIref, 0, newIref.length);
                } else if (c == ilocB) {
                    metaC.write(newIloc, 0, newIloc.length);
                } else if (c == irefB) {
                    metaC.write(newIref, 0, newIref.length);
                } else {
                    metaC.write(data, c.start, (int) c.size);
                }
            }
            byte[] newMeta = box("meta", metaC.toByteArray());

            ByteArrayOutputStream outBuf = new ByteArrayOutputStream(
                    data.length + newMeta.length + exifPayload.length + 16);
            outBuf.write(data, 0, meta.start);
            outBuf.write(newMeta, 0, newMeta.length);
            outBuf.write(data, metaEnd, data.length - metaEnd);
            ByteArrayOutputStream mdat = new ByteArrayOutputStream();
            w32(mdat, 8L + exifPayload.length);
            writeType(mdat, "mdat");
            outBuf.write(mdat.toByteArray(), 0, 8);
            outBuf.write(exifPayload, 0, exifPayload.length);

            byte[] result = outBuf.toByteArray();
            File tmp = File.createTempFile("heifx_", ".tmp", ctx.getCacheDir());
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(result);
            }
            if (!tmp.renameTo(file)) {
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(result);
                }
                tmp.delete();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] buildExifInfe(int itemId) {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(2); c.write(0); c.write(0); c.write(0); // infe version 2, flags 0
        w16(c, itemId);
        w16(c, 0); // item_protection_index
        writeType(c, "Exif"); // item_type
        c.write(0); // empty, null-terminated item_name
        return box("infe", c.toByteArray());
    }

    private static byte[] buildIinf(byte[] data, Box iinfB, IinfInfo iinf, byte[] newInfe) {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(iinf.version); c.write(0); c.write(0); c.write(0);
        int newCount = iinf.count + 1;
        if (iinf.version == 0) w16(c, newCount); else w32(c, newCount);
        int entriesEnd = (int) (iinfB.start + iinfB.size);
        c.write(data, iinf.entriesStart, entriesEnd - iinf.entriesStart);
        c.write(newInfe, 0, newInfe.length);
        return box("iinf", c.toByteArray());
    }

    /** Builds (or extends) the iref box with a 'cdsc' reference from the Exif item to the image. */
    private static byte[] buildIref(byte[] data, Box irefB, int fromId, int toId) {
        boolean uint32 = irefB != null && (data[irefB.contentStart] & 0xFF) == 1;
        ByteArrayOutputStream cdsc = new ByteArrayOutputStream();
        if (uint32) { w32(cdsc, fromId); w16(cdsc, 1); w32(cdsc, toId); }
        else { w16(cdsc, fromId); w16(cdsc, 1); w16(cdsc, toId); }
        byte[] ref = box("cdsc", cdsc.toByteArray());

        ByteArrayOutputStream c = new ByteArrayOutputStream();
        if (irefB != null) {
            int childStart = irefB.contentStart + 4;
            int childEnd = (int) (irefB.start + irefB.size);
            c.write(data, irefB.contentStart, 4); // existing ver/flags
            c.write(data, childStart, childEnd - childStart); // existing references
        } else {
            c.write(0); c.write(0); c.write(0); c.write(0); // iref version 0, flags 0
        }
        c.write(ref, 0, ref.length);
        return box("iref", c.toByteArray());
    }

    /** Re-serializes iloc canonically: version 1, 4-byte offsets/lengths, no base offset. */
    private static byte[] serializeIloc(List<IlocItem> items) {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        c.write(1); c.write(0); c.write(0); c.write(0); // version 1, flags 0
        c.write(0x44); // offset_size = 4, length_size = 4
        c.write(0x00); // base_offset_size = 0, index_size = 0
        w16(c, items.size());
        for (IlocItem it : items) {
            w16(c, it.id);
            w16(c, it.method & 0xF); // reserved(12) + construction_method(4)
            w16(c, it.dataRefIndex);
            w16(c, it.extents.size());
            for (long[] ex : it.extents) {
                w32(c, ex[0]);
                w32(c, ex[1]);
            }
        }
        return box("iloc", c.toByteArray());
    }

    private static IinfInfo parseIinf(byte[] d, Box iinf) {
        IinfInfo r = new IinfInfo();
        int p = iinf.contentStart;
        r.version = d[p] & 0xFF;
        p += 4;
        if (r.version == 0) { r.count = u16(d, p); p += 2; }
        else { r.count = (int) u32(d, p); p += 4; }
        r.entriesStart = p;
        for (Box e : parseBoxes(d, p, (int) (iinf.start + iinf.size))) {
            if (!"infe".equals(e.type)) continue;
            int ev = d[e.contentStart] & 0xFF;
            int id;
            String type = "";
            if (ev >= 2) {
                if (ev == 2) { id = u16(d, e.contentStart + 4); type = str4(d, e.contentStart + 8); }
                else { id = (int) u32(d, e.contentStart + 4); type = str4(d, e.contentStart + 10); }
            } else {
                id = u16(d, e.contentStart + 4);
            }
            if (id > r.maxId) r.maxId = id;
            if ("Exif".equals(type)) r.hasExif = true;
        }
        return r;
    }

    private static List<IlocItem> parseIloc(byte[] d, Box iloc) {
        List<IlocItem> items = new ArrayList<>();
        int p = iloc.contentStart;
        int version = d[p] & 0xFF;
        p += 4;
        int b0 = d[p] & 0xFF, b1 = d[p + 1] & 0xFF;
        p += 2;
        int offsetSize = (b0 >> 4) & 0xF, lengthSize = b0 & 0xF, baseOffsetSize = (b1 >> 4) & 0xF;
        int indexSize = (version == 1 || version == 2) ? (b1 & 0xF) : 0;
        int itemCount;
        if (version < 2) { itemCount = u16(d, p); p += 2; }
        else { itemCount = (int) u32(d, p); p += 4; }
        for (int i = 0; i < itemCount; i++) {
            IlocItem it = new IlocItem();
            if (version < 2) { it.id = u16(d, p); p += 2; } else { it.id = (int) u32(d, p); p += 4; }
            if (version == 1 || version == 2) { it.method = u16(d, p) & 0xF; p += 2; }
            it.dataRefIndex = u16(d, p);
            p += 2;
            long base = readUint(d, p, baseOffsetSize);
            p += baseOffsetSize;
            int extentCount = u16(d, p);
            p += 2;
            it.extents = new ArrayList<>();
            for (int e = 0; e < extentCount; e++) {
                if (indexSize > 0) p += indexSize; // skip extent_index
                long off = readUint(d, p, offsetSize);
                p += offsetSize;
                long len = readUint(d, p, lengthSize);
                p += lengthSize;
                it.extents.add(new long[]{base + off, len}); // fold base into absolute/relative offset
            }
            items.add(it);
        }
        return items;
    }

    // ---- ISO-BMFF byte helpers ----------------------------------------------

    private static final class Box {
        String type;
        int start, contentStart;
        long size;
    }

    private static final class IinfInfo {
        int version, count, entriesStart, maxId;
        boolean hasExif;
    }

    private static final class IlocItem {
        int id, method, dataRefIndex;
        List<long[]> extents; // each: {offset, length}
    }

    private static List<Box> parseBoxes(byte[] d, int from, int to) {
        List<Box> out = new ArrayList<>();
        int p = from;
        while (p + 8 <= to) {
            long size = u32(d, p);
            int hs = 8;
            String type = str4(d, p + 4);
            if (size == 1) { size = readUint(d, p + 8, 8); hs = 16; }
            else if (size == 0) { size = to - p; }
            if (size < hs || p + size > to) break;
            Box b = new Box();
            b.type = type;
            b.start = p;
            b.size = size;
            b.contentStart = p + hs;
            out.add(b);
            p += (int) size;
        }
        return out;
    }

    private static Box find(List<Box> boxes, String type) {
        for (Box b : boxes) if (b.type.equals(type)) return b;
        return null;
    }

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        }
        return b;
    }

    private static byte[] box(String type, byte[] content) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        w32(o, 8L + content.length);
        writeType(o, type);
        o.write(content, 0, content.length);
        return o.toByteArray();
    }

    private static void writeType(ByteArrayOutputStream o, String type) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        o.write(t, 0, 4);
    }

    private static void w16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void w32(ByteArrayOutputStream o, long v) {
        o.write((int) ((v >>> 24) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) (v & 0xFF));
    }

    private static int u16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    private static long readUint(byte[] d, int p, int size) {
        long v = 0;
        for (int k = 0; k < size; k++) v = (v << 8) | (d[p + k] & 0xFF);
        return v;
    }

    private static String str4(byte[] d, int p) {
        return new String(d, p, 4, StandardCharsets.US_ASCII);
    }
}
