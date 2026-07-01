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

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Decodes, re-encodes (WebP/HEIC) and re-injects metadata. The output is written to a temp file in
 * the cache dir; callers publish it (MediaStore insert or legacy file write). Honest limitations:
 * ICC/wide-gamut profiles are lost (pixels normalize to sRGB) and HEIC/AVIF cannot carry re-injected
 * EXIF via ExifInterface (read-only for HEIF/AVIF), so only WebP gets full EXIF/GPS re-injection.
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

    /** Copies EXIF/GPS/XMP from source into the output. No-op for HEIC (ExifInterface can't write HEIF). */
    private void reinjectExif(Uri source, File out, CompressMode mode) {
        if (mode != CompressMode.WEBP) return;
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
}
