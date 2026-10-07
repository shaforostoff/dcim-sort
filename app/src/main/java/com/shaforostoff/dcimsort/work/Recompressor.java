package com.shaforostoff.dcimsort.work;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Gainmap;
import android.graphics.ImageDecoder;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.util.Size;

import androidx.annotation.RequiresApi;
import androidx.exifinterface.media.ExifInterface;
import androidx.heifwriter.AvifWriter;
import androidx.heifwriter.HeifWriter;

import com.shaforostoff.dcimsort.codec.GainmapMeta;
import com.shaforostoff.dcimsort.codec.NativeCodecs;
import com.shaforostoff.dcimsort.data.CompressMode;
import com.shaforostoff.dcimsort.data.MediaRepository;
import com.shaforostoff.dcimsort.util.Sdk;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Decodes, re-encodes (WebP/HEIC/AVIF) and re-injects metadata. The output is written to a temp file
 * in the cache dir; callers publish it (MediaStore insert or legacy file write). Honest limitation:
 * ICC/wide-gamut profiles are lost (pixels normalize to sRGB). EXIF/GPS is re-injected for WebP via
 * ExifInterface; for HEIC/AVIF (which ExifInterface can only read) the Exif block is handed to the
 * HEIF/AVIF writer, whose muxer stores it as the image's {@code Exif} item. XMP is not carried into
 * HEIC/AVIF (it would need a separate {@code mime} item).
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
            case JPEG: return ".jpg";
            default: return ".webp";
        }
    }

    public static String mimeFor(CompressMode mode) {
        switch (mode) {
            case HEIC: return "image/heic";
            case AVIF: return "image/avif";
            case JPEG: return "image/jpeg";
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

    /**
     * True if AVIF can be encoded: the full flavor's bundled libavif works on every Android version;
     * otherwise the platform encoder requires Android 16+ and an AV1 encoder.
     */
    public static synchronized boolean hasAvifEncoder() {
        if (NativeCodecs.avifAvailable()) return true;
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

    /** True if the full flavor's bundled jpegli JPEG encoder is available (never in lite). */
    public static boolean hasJpegliEncoder() {
        return NativeCodecs.jpegliAvailable();
    }

    /**
     * Produces a compressed temp file (in cacheDir) with metadata re-injected. Caller deletes it.
     * @return the temp file, or null on failure.
     */
    public File compressToTemp(Uri source, CompressMode mode, int quality) {
        return compressToTemp(source, mode, quality, MAX_LONG_SIDE, true);
    }

    /**
     * @param embedExif when false, all EXIF/metadata handling is skipped — used by size estimation,
     *                  which only needs the encoded byte count and not a faithful output file.
     */
    private File compressToTemp(Uri source, CompressMode mode, int quality, int maxLongSide,
                                boolean embedExif) {
        // Full flavor: AVIF goes through libavif (HDR-preserving, EXIF embedded during encode).
        if (mode == CompressMode.AVIF && NativeCodecs.avifAvailable()) {
            return compressAvifNative(source, quality, maxLongSide, embedExif);
        }
        // Full flavor: JPEG goes through jpegli; UltraHDR gain map is preserved when present.
        if (mode == CompressMode.JPEG && NativeCodecs.jpegliAvailable()) {
            return compressJpegNative(source, quality, maxLongSide, embedExif);
        }
        Bitmap bmp = decodeOriented(source, maxLongSide);
        if (bmp == null) return null;
        File out = null;
        try {
            out = File.createTempFile("cmp_", extensionFor(mode), ctx.getCacheDir());
            // HEIF containers take their Exif item from the writer during encode.
            boolean heif = mode == CompressMode.HEIC || mode == CompressMode.AVIF;
            byte[] heifExif = embedExif && heif ? buildHeifExif(readSourceExif(source)) : null;
            boolean ok = encodeBitmap(bmp, mode, quality, heifExif, out);
            if (!ok) {
                out.delete();
                return null;
            }
            if (embedExif && !heif) reinjectExif(source, out);
            return out;
        } catch (IOException e) {
            if (out != null) out.delete();
            return null;
        } finally {
            bmp.recycle();
        }
    }

    /**
     * Full-flavor AVIF path: decodes the source (keeping its UltraHDR gain map when present), then
     * hands the base bitmap, gain map and a raw EXIF/TIFF block to libavif, which embeds HDR and
     * metadata during encode — so no ISO-BMFF EXIF surgery is needed afterward.
     */
    private File compressAvifNative(Uri source, int quality, int maxLongSide, boolean embedExif) {
        Bitmap base = decodeOriented(source, maxLongSide);
        if (base == null) return null;
        Bitmap gainmapContents = null;
        GainmapMeta meta = null;
        // Gain map in AVIF (ISO 21496-1) requires Android 16+ to decode; skip on older devices.
        if (Sdk.atLeastBaklava() && base.hasGainmap()) {
            Gainmap g = base.getGainmap();
            if (g != null) {
                gainmapContents = g.getGainmapContents();
                meta = toGainmapMeta(g);
            }
        }
        File out = null;
        try {
            out = File.createTempFile("cmp_", ".avif", ctx.getCacheDir());
            byte[] exifTiff = embedExif ? buildExifTiffBlock(readSourceExif(source)) : null;
            boolean ok = NativeCodecs.encodeAvif(base, gainmapContents, meta, quality, exifTiff, out);
            if (!ok) {
                out.delete();
                return null;
            }
            return out;
        } catch (IOException e) {
            if (out != null) out.delete();
            return null;
        } finally {
            base.recycle();
        }
    }

    /**
     * Full-flavor JPEG path: encodes with jpegli and, when the source carries a UltraHDR gain map,
     * produces a JPEG_R (JPEG + gainmap stitched via MPF) with EXIF embedded during encode so the
     * MPF offsets are not disturbed by post-encode ExifInterface writes. Falls back to plain JPEG
     * with normal EXIF re-injection when no gain map is present.
     */
    private File compressJpegNative(Uri source, int quality, int maxLongSide, boolean embedExif) {
        Bitmap base = decodeOriented(source, maxLongSide);
        if (base == null) return null;
        File out = null;
        try {
            out = File.createTempFile("cmp_", ".jpg", ctx.getCacheDir());
            // Attempt JPEG_R when the decoded bitmap carries a UltraHDR gain map.
            if (Sdk.atLeastBaklava() && base.hasGainmap()) {
                Gainmap g = base.getGainmap();
                if (g != null) {
                    Bitmap gainmapContents = g.getGainmapContents();
                    if (gainmapContents != null) {
                        GainmapMeta meta = toGainmapMeta(g);
                        byte[] exifTiff = embedExif ? buildExifTiffBlock(readSourceExif(source)) : null;
                        boolean ok = NativeCodecs.encodeJpegR(base, gainmapContents, meta,
                                quality, exifTiff, out);
                        if (ok) return out;
                        // Fall through to plain JPEG if JPEG_R encoding fails.
                    }
                }
            }
            // Plain JPEG: encode then re-inject EXIF via ExifInterface.
            boolean ok = NativeCodecs.encodeJpeg(base, quality, out);
            if (!ok) {
                out.delete();
                return null;
            }
            if (embedExif) reinjectExif(source, out);
            return out;
        } catch (IOException e) {
            if (out != null) out.delete();
            return null;
        } finally {
            base.recycle();
        }
    }

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static GainmapMeta toGainmapMeta(Gainmap g) {
        GainmapMeta m = new GainmapMeta();
        m.ratioMin = g.getRatioMin();
        m.ratioMax = g.getRatioMax();
        m.gamma = g.getGamma();
        m.epsilonSdr = g.getEpsilonSdr();
        m.epsilonHdr = g.getEpsilonHdr();
        m.displayRatioSdr = g.getMinDisplayRatioForHdrTransition();
        m.displayRatioHdr = g.getDisplayRatioForFullHdr();
        return m;
    }

    /** Reads the source's EXIF, or null if it can't be opened. */
    private ExifInterface readSourceExif(Uri source) {
        try (InputStream in = repo.openOriginalForExif(source)) {
            return new ExifInterface(in);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Size in bytes the image would occupy after compression, for size estimation only. Skips all
     * EXIF/metadata work (the count doesn't need it), and for WEBP encodes straight to a counting
     * sink so no temp file touches disk. Other formats route through native/MediaCodec encoders that
     * require a file path, so they still write (and immediately delete) a temp file.
     */
    public long encodedSize(Uri source, CompressMode mode, int quality, int maxLongSide) {
        if (mode == CompressMode.WEBP) {
            Bitmap bmp = decodeOriented(source, maxLongSide);
            if (bmp == null) return -1;
            try {
                CountingOutputStream cos = new CountingOutputStream();
                return encodeWebp(bmp, quality, cos) ? cos.count() : -1;
            } finally {
                bmp.recycle();
            }
        }
        File f = compressToTemp(source, mode, quality, maxLongSide, false);
        if (f == null) return -1;
        long len = f.length();
        f.delete();
        return len;
    }

    /** Discards everything written and just tallies the byte count — for size-only encoding. */
    private static final class CountingOutputStream extends OutputStream {
        private long count;
        long count() { return count; }
        @Override public void write(int b) { count++; }
        @Override public void write(byte[] b, int off, int len) { count += len; }
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

    /** @param heifExif Exif block ("Exif\0\0" + TIFF) for HEIC/AVIF, or null; ignored otherwise. */
    private boolean encodeBitmap(Bitmap bmp, CompressMode mode, int quality, byte[] heifExif,
                                 File out) {
        if (mode == CompressMode.WEBP) {
            try (FileOutputStream fos = new FileOutputStream(out)) {
                return encodeWebp(bmp, quality, fos);
            } catch (IOException e) {
                return false;
            }
        } else if (mode == CompressMode.HEIC) {
            return encodeHeic(bmp, quality, heifExif, out);
        } else if (mode == CompressMode.AVIF) {
            // Reached only when libavif isn't bundled (lite, Android 16+ platform encoder); the
            // full flavor routes AVIF through compressAvifNative before this point.
            return encodeAvif(bmp, quality, heifExif, out);
        } else if (mode == CompressMode.JPEG) {
            return NativeCodecs.encodeJpeg(bmp, quality, out);
        }
        return false;
    }

    /** WEBP encode to any sink — used for both temp-file output and size-only counting. */
    private boolean encodeWebp(Bitmap bmp, int quality, OutputStream os) {
        Bitmap.CompressFormat fmt = Sdk.atLeastR()
                ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        return bmp.compress(fmt, quality, os);
    }

    private boolean encodeHeic(Bitmap src, int quality, byte[] exif, File out) {
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
            if (exif != null) {
                try { writer.addExifData(0, exif, 0, exif.length); } catch (Exception ignore) {}
            }
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

    private boolean encodeAvif(Bitmap src, int quality, byte[] exif, File out) {
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
            if (exif != null) {
                try { writer.addExifData(0, exif, 0, exif.length); } catch (Exception ignore) {}
            }
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
     * Copies EXIF/GPS/XMP from source into a WebP or JPEG output, which ExifInterface writes
     * directly. Best effort — the recompressed pixels are valid even if metadata fails.
     */
    private void reinjectExif(Uri source, File out) {
        try {
            ExifInterface srcExif;
            try (InputStream in = repo.openOriginalForExif(source)) {
                srcExif = new ExifInterface(in);
            }
            ExifInterface dst = new ExifInterface(out.getAbsolutePath());
            for (String tag : COPY_TAGS) {
                String v = srcExif.getAttribute(tag);
                if (v != null) dst.setAttribute(tag, v);
            }
            // Pixels were normalized upright on decode → record normal orientation.
            dst.setAttribute(ExifInterface.TAG_ORIENTATION,
                    String.valueOf(ExifInterface.ORIENTATION_NORMAL));
            dst.saveAttributes();
        } catch (Exception ignore) {
            // Best effort; the recompressed pixels are still valid without metadata.
        }
    }

    /**
     * Exif block in the form HeifWriter/AvifWriter.addExifData expects: "Exif\0\0" followed by the
     * TIFF header. Null if there was nothing to write or extraction failed.
     */
    private byte[] buildHeifExif(ExifInterface srcExif) {
        byte[] tiff = buildExifTiffBlock(srcExif);
        if (tiff == null) return null;
        byte[] block = new byte[6 + tiff.length];
        block[0] = 'E'; block[1] = 'x'; block[2] = 'i'; block[3] = 'f'; // block[4..5] stay 0
        System.arraycopy(tiff, 0, block, 6, tiff.length);
        return block;
    }

    /**
     * Produces the raw TIFF/Exif block (starting with the "II"/"MM" byte order marker) carrying the
     * copied tags with orientation normalized. libavif and JPEG_R embed this directly; HEIF writers
     * get it with an "Exif\0\0" prefix. Returns null if there was nothing to write or extraction failed.
     */
    private byte[] buildExifTiffBlock(ExifInterface srcExif) {
        if (srcExif == null) return null;
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
            return extractExifTiff(readAll(tmp));
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

    private static byte[] readAll(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
        }
        return b;
    }
}
