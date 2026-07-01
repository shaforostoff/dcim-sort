// JNI bridge for the `full` flavor: encodes an Android Bitmap to JPEG (jpegli) and optionally to
// AVIF (libavif + aom) when built with -DENABLE_AVIF=ON. Mirrors the Java methods declared in
// app/src/full/java/com/shaforostoff/dcimsort/codec/NativeCodecs.java.
//
// All entry points are best-effort: any failure returns JNI_FALSE and the Java side falls back or
// reports the image as failed. Pixel buffers are always unlocked before returning.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <vector>

#ifdef ENABLE_AVIF
#include "avif/avif.h"
#endif

// jpegli's libjpeg-compatible API (github.com/google/jpegli). encode.h pulls in the generated
// <jpeglib.h> types (jpeg_compress_struct, j_compress_ptr, JSAMPROW, ...).
#include "lib/jpegli/encode.h"

#define LOG_TAG "NativeCodecs"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct LockedBitmap {
    AndroidBitmapInfo info{};
    void *pixels = nullptr;
    JNIEnv *env = nullptr;
    jobject bitmap = nullptr;

    bool lock(JNIEnv *e, jobject bmp) {
        env = e;
        bitmap = bmp;
        if (AndroidBitmap_getInfo(e, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS) return false;
        if (AndroidBitmap_lockPixels(e, bmp, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
            pixels = nullptr;
            return false;
        }
        return true;
    }

    ~LockedBitmap() {
        if (pixels && env && bitmap) AndroidBitmap_unlockPixels(env, bitmap);
    }
};

bool writeFile(const char *path, const uint8_t *data, size_t size) {
    FILE *f = std::fopen(path, "wb");
    if (!f) return false;
    size_t wrote = std::fwrite(data, 1, size, f);
    std::fclose(f);
    return wrote == size;
}

#ifdef ENABLE_AVIF

// Reads a float[3] field (R,G,B) from a GainmapMeta instance into out[3].
void readFloat3(JNIEnv *env, jobject meta, jclass cls, const char *field, float out[3]) {
    jfieldID fid = env->GetFieldID(cls, field, "[F");
    if (!fid) return;
    auto arr = (jfloatArray) env->GetObjectField(meta, fid);
    if (!arr) return;
    jsize n = env->GetArrayLength(arr);
    if (n >= 3) env->GetFloatArrayRegion(arr, 0, 3, out);
    env->DeleteLocalRef(arr);
}

float readFloat(JNIEnv *env, jobject meta, jclass cls, const char *field) {
    jfieldID fid = env->GetFieldID(cls, field, "F");
    return fid ? env->GetFloatField(meta, fid) : 0.f;
}

// Fills an avifImage's pixels from an ARGB_8888 Android bitmap (RGBA byte order in memory).
bool fillYuvFromRgba(avifImage *image, const LockedBitmap &bmp) {
    if (bmp.info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return false;
    avifRGBImage rgb;
    avifRGBImageSetDefaults(&rgb, image);
    rgb.format = AVIF_RGB_FORMAT_RGBA;
    rgb.depth = 8;
    rgb.pixels = (uint8_t *) bmp.pixels;
    rgb.rowBytes = bmp.info.stride;
    return avifImageRGBToYUV(image, &rgb) == AVIF_RESULT_OK;
}

// Builds a monochrome (YUV400) gain-map image from the gain-map contents bitmap. Android exposes
// the contents as either ALPHA_8 (single channel) or RGBA_8888; we read the luma either way.
avifImage *buildGainMapImage(const LockedBitmap &gm) {
    int w = (int) gm.info.width;
    int h = (int) gm.info.height;
    avifImage *img = avifImageCreate(w, h, 8, AVIF_PIXEL_FORMAT_YUV400);
    if (!img) return nullptr;
    if (avifImageAllocatePlanes(img, AVIF_PLANES_YUV) != AVIF_RESULT_OK) {
        avifImageDestroy(img);
        return nullptr;
    }
    auto *src = (const uint8_t *) gm.pixels;
    uint8_t *dstPlane = img->yuvPlanes[AVIF_CHAN_Y];
    uint32_t dstStride = img->yuvRowBytes[AVIF_CHAN_Y];
    bool rgba = gm.info.format == ANDROID_BITMAP_FORMAT_RGBA_8888;
    for (int y = 0; y < h; ++y) {
        const uint8_t *srow = src + (size_t) y * gm.info.stride;
        uint8_t *drow = dstPlane + (size_t) y * dstStride;
        for (int x = 0; x < w; ++x) {
            drow[x] = rgba ? srow[(size_t) x * 4] : srow[x]; // R channel, or the single A_8 byte
        }
    }
    return img;
}

// Maps GainmapMeta (android.graphics.Gainmap semantics) onto libavif's gain-map metadata.
void applyGainMapMeta(JNIEnv *env, avifGainMap *gainMap, jobject meta) {
    if (!meta) return;
    jclass cls = env->GetObjectClass(meta);
    float ratioMin[3] = {1, 1, 1}, ratioMax[3] = {2, 2, 2}, gamma[3] = {1, 1, 1};
    float epsSdr[3] = {0, 0, 0}, epsHdr[3] = {0, 0, 0};
    readFloat3(env, meta, cls, "ratioMin", ratioMin);
    readFloat3(env, meta, cls, "ratioMax", ratioMax);
    readFloat3(env, meta, cls, "gamma", gamma);
    readFloat3(env, meta, cls, "epsilonSdr", epsSdr);
    readFloat3(env, meta, cls, "epsilonHdr", epsHdr);
    float displayRatioSdr = readFloat(env, meta, cls, "displayRatioSdr");
    float displayRatioHdr = readFloat(env, meta, cls, "displayRatioHdr");

    // libavif stores gain-map min/max as log2 ratios and gamma/offsets per channel as fractions.
    // android.graphics.Gainmap getRatioMin/Max are linear ratios → take log2.
    const int32_t denom = 1000000;
    for (int c = 0; c < 3; ++c) {
        gainMap->gainMapMin[c].n = (int32_t) (std::log2(ratioMin[c] <= 0 ? 1.f : ratioMin[c]) * denom);
        gainMap->gainMapMin[c].d = denom;
        gainMap->gainMapMax[c].n = (int32_t) (std::log2(ratioMax[c] <= 0 ? 1.f : ratioMax[c]) * denom);
        gainMap->gainMapMax[c].d = denom;
        gainMap->gainMapGamma[c].n = (uint32_t) (gamma[c] * denom);
        gainMap->gainMapGamma[c].d = denom;
        gainMap->baseOffset[c].n = (int32_t) (epsSdr[c] * denom);
        gainMap->baseOffset[c].d = denom;
        gainMap->alternateOffset[c].n = (int32_t) (epsHdr[c] * denom);
        gainMap->alternateOffset[c].d = denom;
    }
    gainMap->baseHdrHeadroom.n = (uint32_t) (std::log2(displayRatioSdr <= 0 ? 1.f : displayRatioSdr) * denom);
    gainMap->baseHdrHeadroom.d = denom;
    gainMap->alternateHdrHeadroom.n = (uint32_t) (std::log2(displayRatioHdr <= 0 ? 1.f : displayRatioHdr) * denom);
    gainMap->alternateHdrHeadroom.d = denom;
}

#endif // ENABLE_AVIF

} // namespace

#ifdef ENABLE_AVIF
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_dcimsort_codec_NativeCodecs_nativeEncodeAvif(
        JNIEnv *env, jclass, jobject base, jobject gainmapContents, jobject meta,
        jint quality, jbyteArray exifTiff, jstring outPath) {
    LockedBitmap baseBmp;
    if (!baseBmp.lock(env, base)) return JNI_FALSE;

    int w = (int) baseBmp.info.width;
    int h = (int) baseBmp.info.height;
    avifImage *image = avifImageCreate(w, h, 8, AVIF_PIXEL_FORMAT_YUV420);
    if (!image) return JNI_FALSE;

    bool ok = fillYuvFromRgba(image, baseBmp);

    // Optional EXIF (raw TIFF block) embedded directly by libavif.
    if (ok && exifTiff) {
        jsize n = env->GetArrayLength(exifTiff);
        if (n > 0) {
            std::vector<uint8_t> buf((size_t) n);
            env->GetByteArrayRegion(exifTiff, 0, n, (jbyte *) buf.data());
            avifImageSetMetadataExif(image, buf.data(), buf.size());
        }
    }

    // Optional UltraHDR gain map.
    if (ok && gainmapContents) {
        LockedBitmap gmBmp;
        if (gmBmp.lock(env, gainmapContents)) {
            avifImage *gmImage = buildGainMapImage(gmBmp);
            if (gmImage) {
                avifGainMap *gainMap = avifGainMapCreate();
                if (gainMap) {
                    gainMap->image = gmImage;
                    applyGainMapMeta(env, gainMap, meta);
                    image->gainMap = gainMap; // ownership transferred to image
                } else {
                    avifImageDestroy(gmImage);
                }
            }
        }
    }

    avifRWData output = AVIF_DATA_EMPTY;
    if (ok) {
        avifEncoder *encoder = avifEncoderCreate();
        if (!encoder) {
            ok = false;
        } else {
            encoder->quality = (int) quality;        // 0..100 in libavif 1.x
            encoder->qualityAlpha = (int) quality;
            encoder->qualityGainMap = (int) quality; // used only when image->gainMap is set
            encoder->speed = 6;                       // balance size/time on mobile
            ok = avifEncoderWrite(encoder, image, &output) == AVIF_RESULT_OK;
            avifEncoderDestroy(encoder);
        }
    }

    if (ok) {
        const char *path = env->GetStringUTFChars(outPath, nullptr);
        ok = path && writeFile(path, output.data, output.size);
        if (path) env->ReleaseStringUTFChars(outPath, path);
    }

    avifRWDataFree(&output);
    avifImageDestroy(image); // also frees attached gainMap + its image
    return ok ? JNI_TRUE : JNI_FALSE;
}

#endif // ENABLE_AVIF

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_dcimsort_codec_NativeCodecs_nativeAvifAvailable(
        JNIEnv *, jclass) {
#ifdef ENABLE_AVIF
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_dcimsort_codec_NativeCodecs_nativeEncodeJpeg(
        JNIEnv *env, jclass, jobject base, jint quality, jstring outPath) {
    LockedBitmap bmp;
    if (!bmp.lock(env, base)) return JNI_FALSE;
    if (bmp.info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    const char *path = env->GetStringUTFChars(outPath, nullptr);
    if (!path) return JNI_FALSE;
    FILE *out = std::fopen(path, "wb");
    env->ReleaseStringUTFChars(outPath, path);
    if (!out) return JNI_FALSE;

    int w = (int) bmp.info.width;
    int h = (int) bmp.info.height;

    // jpegli expects packed RGB; drop the alpha from the RGBA rows.
    std::vector<uint8_t> rgb((size_t) w * h * 3);
    auto *src = (const uint8_t *) bmp.pixels;
    for (int y = 0; y < h; ++y) {
        const uint8_t *srow = src + (size_t) y * bmp.info.stride;
        uint8_t *drow = rgb.data() + (size_t) y * w * 3;
        for (int x = 0; x < w; ++x) {
            drow[x * 3 + 0] = srow[x * 4 + 0];
            drow[x * 3 + 1] = srow[x * 4 + 1];
            drow[x * 3 + 2] = srow[x * 4 + 2];
        }
    }

    jpeg_compress_struct cinfo;
    jpeg_error_mgr jerr;
    cinfo.err = jpegli_std_error(&jerr);
    jpegli_create_compress(&cinfo);
    jpegli_stdio_dest(&cinfo, out);
    cinfo.image_width = w;
    cinfo.image_height = h;
    cinfo.input_components = 3;
    cinfo.in_color_space = JCS_RGB;
    jpegli_set_defaults(&cinfo);
    jpegli_set_quality(&cinfo, (int) quality, TRUE);
    jpegli_start_compress(&cinfo, TRUE);
    while (cinfo.next_scanline < cinfo.image_height) {
        JSAMPROW row = rgb.data() + (size_t) cinfo.next_scanline * w * 3;
        jpegli_write_scanlines(&cinfo, &row, 1);
    }
    jpegli_finish_compress(&cinfo);
    jpegli_destroy_compress(&cinfo);
    std::fclose(out);
    return JNI_TRUE;
}
