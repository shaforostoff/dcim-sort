// JNI bridge for the `full` flavor: encodes an Android Bitmap to JPEG (jpegli) and optionally to
// AVIF (libavif + aom) when built with -DENABLE_AVIF=ON. Mirrors the Java methods declared in
// app/src/full/java/com/shaforostoff/dcimsort/codec/NativeCodecs.java.
//
// All entry points are best-effort: any failure returns JNI_FALSE and the Java side falls back or
// reports the image as failed. Pixel buffers are always unlocked before returning.

#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <malloc.h>
#include <dlfcn.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
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

#ifdef ENABLE_AVIF

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

// ---- JPEG-to-memory encoder (used by nativeEncodeJpegR) --------------------

struct MemDest {
    jpeg_destination_mgr pub;
    std::vector<uint8_t> *buf;
};

static constexpr size_t kMemBlock = 65536;

static void memInitDest(j_compress_ptr cinfo) {
    auto *d = reinterpret_cast<MemDest *>(cinfo->dest);
    d->buf->resize(kMemBlock);
    cinfo->dest->next_output_byte = d->buf->data();
    cinfo->dest->free_in_buffer = kMemBlock;
}

static boolean memEmptyBuffer(j_compress_ptr cinfo) {
    auto *d = reinterpret_cast<MemDest *>(cinfo->dest);
    size_t old = d->buf->size();
    d->buf->resize(old + kMemBlock);
    cinfo->dest->next_output_byte = d->buf->data() + old;
    cinfo->dest->free_in_buffer = kMemBlock;
    return TRUE;
}

static void memTermDest(j_compress_ptr cinfo) {
    auto *d = reinterpret_cast<MemDest *>(cinfo->dest);
    d->buf->resize(d->buf->size() - cinfo->dest->free_in_buffer);
}

static void setMemDest(j_compress_ptr cinfo, std::vector<uint8_t> &buf) {
    auto *d = reinterpret_cast<MemDest *>(
            (*cinfo->mem->alloc_small)(reinterpret_cast<j_common_ptr>(cinfo),
                                       JPOOL_PERMANENT, sizeof(MemDest)));
    d->pub.init_destination    = memInitDest;
    d->pub.empty_output_buffer = memEmptyBuffer;
    d->pub.term_destination    = memTermDest;
    d->buf = &buf;
    cinfo->dest = &d->pub;
}

// Encodes pixels to a JPEG buffer. isRgba: source is RGBA_8888 (else ALPHA_8).
// grayscale: encode as JCS_GRAYSCALE using the R channel (RGBA) or the single byte (A_8).
static std::vector<uint8_t> encodeToJpegBuffer(
        const void *pixels, uint32_t w, uint32_t h, uint32_t stride,
        bool isRgba, int quality, bool grayscale) {
    std::vector<uint8_t> buf;
    jpeg_compress_struct cinfo;
    jpeg_error_mgr jerr;
    cinfo.err = jpegli_std_error(&jerr);
    jpegli_create_compress(&cinfo);
    setMemDest(&cinfo, buf);
    cinfo.image_width      = w;
    cinfo.image_height     = h;
    cinfo.input_components = grayscale ? 1 : 3;
    cinfo.in_color_space   = grayscale ? JCS_GRAYSCALE : JCS_RGB;
    jpegli_set_defaults(&cinfo);
    jpegli_set_quality(&cinfo, quality, TRUE);
    jpegli_start_compress(&cinfo, TRUE);
    auto *src = reinterpret_cast<const uint8_t *>(pixels);
    if (grayscale) {
        std::vector<uint8_t> row(w);
        while (cinfo.next_scanline < h) {
            const uint8_t *s = src + (size_t) cinfo.next_scanline * stride;
            for (uint32_t x = 0; x < w; ++x)
                row[x] = isRgba ? s[x * 4] : s[x];
            JSAMPROW r = row.data();
            jpegli_write_scanlines(&cinfo, &r, 1);
        }
    } else {
        std::vector<uint8_t> row(w * 3);
        while (cinfo.next_scanline < h) {
            const uint8_t *s = src + (size_t) cinfo.next_scanline * stride;
            for (uint32_t x = 0; x < w; ++x) {
                row[x * 3]     = s[x * 4];
                row[x * 3 + 1] = s[x * 4 + 1];
                row[x * 3 + 2] = s[x * 4 + 2];
            }
            JSAMPROW r = row.data();
            jpegli_write_scanlines(&cinfo, &r, 1);
        }
    }
    jpegli_finish_compress(&cinfo);
    jpegli_destroy_compress(&cinfo);
    return buf;
}

// ---- JPEG_R (UltraHDR JPEG) segment builders --------------------------------

static bool allEqual3(const float v[3]) { return v[0] == v[1] && v[1] == v[2]; }

static std::string fmtF(float v) {
    char tmp[32];
    std::snprintf(tmp, sizeof(tmp), "%.6g", v);
    return tmp;
}

static std::string rdfSeq3(const float v[3]) {
    return std::string("<rdf:Seq><rdf:li>") + fmtF(v[0])
           + "</rdf:li><rdf:li>" + fmtF(v[1])
           + "</rdf:li><rdf:li>" + fmtF(v[2])
           + "</rdf:li></rdf:Seq>";
}

// Builds the XMP APP1 segment carrying JPEG Gainmap Metadata (hdrgm namespace, ISO 21496-1).
// Single-channel gainmaps use scalar attribute values; multi-channel uses rdf:Seq elements.
static std::vector<uint8_t> buildXmpApp1(
        const float ratioMin[3], const float ratioMax[3],
        const float gamma[3], const float epsSdr[3], const float epsHdr[3],
        float displayRatioSdr, float displayRatioHdr) {
    auto log2s = [](float v) { return std::log2(v > 0.f ? v : 1.f); };
    float mapMin[3] = {log2s(ratioMin[0]), log2s(ratioMin[1]), log2s(ratioMin[2])};
    float mapMax[3] = {log2s(ratioMax[0]), log2s(ratioMax[1]), log2s(ratioMax[2])};
    float capMin = log2s(displayRatioSdr), capMax = log2s(displayRatioHdr);
    bool single = allEqual3(mapMin) && allEqual3(mapMax)
                  && allEqual3(gamma) && allEqual3(epsSdr) && allEqual3(epsHdr);

    std::string xmp;
    xmp += "<?xpacket begin=\"\xef\xbb\xbf\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>";
    xmp += "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">";
    xmp += "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">";
    xmp += "<rdf:Description rdf:about=\"\""
           " xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\""
           " hdrgm:Version=\"1.0\"";
    if (single) {
        xmp += " hdrgm:GainMapMin=\"";    xmp += fmtF(mapMin[0]);  xmp += "\"";
        xmp += " hdrgm:GainMapMax=\"";    xmp += fmtF(mapMax[0]);  xmp += "\"";
        xmp += " hdrgm:Gamma=\"";         xmp += fmtF(gamma[0]);   xmp += "\"";
        xmp += " hdrgm:OffsetSdr=\"";     xmp += fmtF(epsSdr[0]);  xmp += "\"";
        xmp += " hdrgm:OffsetHdr=\"";     xmp += fmtF(epsHdr[0]);  xmp += "\"";
        xmp += " hdrgm:HDRCapacityMin=\""; xmp += fmtF(capMin);    xmp += "\"";
        xmp += " hdrgm:HDRCapacityMax=\""; xmp += fmtF(capMax);    xmp += "\"";
        xmp += "/>";
    } else {
        xmp += " hdrgm:HDRCapacityMin=\""; xmp += fmtF(capMin); xmp += "\"";
        xmp += " hdrgm:HDRCapacityMax=\""; xmp += fmtF(capMax); xmp += "\">";
        auto elem = [&](const char *name, const float v[3]) {
            xmp += "<hdrgm:"; xmp += name; xmp += ">";
            xmp += rdfSeq3(v);
            xmp += "</hdrgm:"; xmp += name; xmp += ">";
        };
        elem("GainMapMin", mapMin);
        elem("GainMapMax", mapMax);
        elem("Gamma",      gamma);
        elem("OffsetSdr",  epsSdr);
        elem("OffsetHdr",  epsHdr);
        xmp += "</rdf:Description>";
    }
    xmp += "</rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>";

    // APP1 payload = "http://ns.adobe.com/xap/1.0/\0" + xmp content
    static const char kNs[] = "http://ns.adobe.com/xap/1.0/";
    constexpr size_t kNsLen = sizeof(kNs); // includes '\0'
    if (kNsLen + xmp.size() + 2 > 0xFFFF)
        xmp.resize(0xFFFF - 2 - kNsLen);
    auto segLen = static_cast<uint16_t>(kNsLen + xmp.size() + 2);
    std::vector<uint8_t> seg;
    seg.reserve(2 + segLen);
    seg.push_back(0xFF); seg.push_back(0xE1);
    seg.push_back(segLen >> 8); seg.push_back(segLen & 0xFF);
    for (size_t i = 0; i < kNsLen; ++i) seg.push_back(static_cast<uint8_t>(kNs[i]));
    for (char c : xmp) seg.push_back(static_cast<uint8_t>(c));
    return seg;
}

// Wraps a raw TIFF block as a JPEG Exif APP1 segment (FF E1 + "Exif\0\0" + tiff).
static std::vector<uint8_t> buildExifApp1(const uint8_t *tiff, size_t tiffLen) {
    if (tiffLen + 8 > 0xFFFF) tiffLen = 0xFFFF - 8;
    auto segLen = static_cast<uint16_t>(tiffLen + 8); // 2(len) + 6("Exif\0\0")
    std::vector<uint8_t> seg;
    seg.reserve(2 + segLen);
    seg.push_back(0xFF); seg.push_back(0xE1);
    seg.push_back(segLen >> 8); seg.push_back(segLen & 0xFF);
    seg.push_back('E'); seg.push_back('x'); seg.push_back('i');
    seg.push_back('f'); seg.push_back(0);  seg.push_back(0);
    seg.insert(seg.end(), tiff, tiff + tiffLen);
    return seg;
}

// Builds the 90-byte MPF APP2 segment (CIPA DC-007). baseJpegSize is the raw jpegli output size;
// the gainmap data offset from the end of this segment equals baseJpegSize - 2 (strips SOI).
static std::vector<uint8_t> buildMpfApp2(uint32_t baseJpegSize, uint32_t gainmapSize) {
    auto le32 = [](uint8_t *p, uint32_t v) {
        p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24;
    };
    std::vector<uint8_t> s(90, 0);
    // APP2 marker + length (88 bytes)
    s[0] = 0xFF; s[1] = 0xE2; s[2] = 0x00; s[3] = 0x58;
    s[4] = 'M';  s[5] = 'P';  s[6] = 'F';  s[7] = 0;
    // TIFF header (little-endian), IFD0 at TIFF offset 8
    s[8] = 'I'; s[9] = 'I'; s[10] = 0x2A; // TIFF magic
    s[12] = 0x08;                           // IFD0 offset
    // IFD0: 3 entries
    s[16] = 0x03;
    // Entry 1: MPFVersion (tag 0xB000, UNDEFINED, count=4, value "0100")
    s[18] = 0x00; s[19] = 0xB0; s[20] = 0x07;
    s[22] = 0x04;
    s[26] = '0'; s[27] = '1'; s[28] = '0'; s[29] = '0';
    // Entry 2: NumberOfImages (tag 0xB001, LONG, count=1, value=2)
    s[30] = 0x01; s[31] = 0xB0; s[32] = 0x04;
    s[34] = 0x01;
    s[38] = 0x02;
    // Entry 3: MPEntry (tag 0xB002, UNDEFINED, count=32, TIFF offset=50→seg pos 58)
    s[42] = 0x02; s[43] = 0xB0; s[44] = 0x07;
    s[46] = 0x20; // count=32
    s[50] = 0x32; // TIFF offset 50 (= segment position 58)
    // Primary MP entry at seg pos 58: attributes 0x20030000 (Baseline MP Primary JPEG)
    s[60] = 0x03; s[61] = 0x20; // LE: [0x00,0x00,0x03,0x20]
    // size=0, offset=0 for primary (already zeroed)
    // Gainmap MP entry at seg pos 74
    le32(&s[78], gainmapSize);
    le32(&s[82], baseJpegSize - 2); // data offset from end of MPF = base size minus SOI
    return s;
}

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

// Forces bionic's allocator (Scudo on API 30+, jemalloc before) to return decayed/cached free
// pages to the OS immediately, rather than waiting for its background decay. Meant to be called
// once after a compression batch, when a burst of large encode buffers has just been freed.
//
// mallopt() is only exposed by the NDK headers (and only present in libc) from API 26 on, while
// our minSdk is 24. Resolve it at runtime so we simply skip the purge on 24/25 devices instead of
// failing to load. M_PURGE (-101) is a plain macro available at every API level.
extern "C" JNIEXPORT void JNICALL
Java_com_shaforostoff_dcimsort_codec_NativeCodecs_nativePurgeMemory(
        JNIEnv *, jclass) {
    using MalloptFn = int (*)(int, int);
    static auto mallopt_fn = reinterpret_cast<MalloptFn>(dlsym(RTLD_DEFAULT, "mallopt"));
    if (mallopt_fn) mallopt_fn(M_PURGE, 0);
}

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

// Encodes a UltraHDR JPEG (JPEG_R) preserving the gain map from an Android Bitmap.
// Output is a self-contained JPEG file with an XMP APP1 (hdrgm gainmap metadata) and
// MPF APP2 linking the primary JPEG to an appended gainmap JPEG (CIPA DC-007 / ISO 21496-1).
// EXIF is embedded via an APP1 segment so MPF offsets remain stable (no post-encode patching).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_shaforostoff_dcimsort_codec_NativeCodecs_nativeEncodeJpegR(
        JNIEnv *env, jclass, jobject base, jobject gainmapBitmap,
        jobject meta, jint quality, jbyteArray exifTiff, jstring outPath) {
    LockedBitmap baseBmp, gmBmp;
    if (!baseBmp.lock(env, base)) return JNI_FALSE;
    if (baseBmp.info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;
    if (!gmBmp.lock(env, gainmapBitmap)) return JNI_FALSE;

    // Gainmap bitmap must be RGBA_8888 or ALPHA_8 to lock pixels.
    bool gmIsAlpha = gmBmp.info.format == ANDROID_BITMAP_FORMAT_A_8;
    if (!gmIsAlpha && gmBmp.info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return JNI_FALSE;

    // Read gainmap metadata fields via JNI reflection (same as AVIF path).
    float ratioMin[3] = {1, 1, 1}, ratioMax[3] = {2, 2, 2}, gamma_[3] = {1, 1, 1};
    float epsSdr[3] = {0, 0, 0}, epsHdr[3] = {0, 0, 0};
    float displayRatioSdr = 1.f, displayRatioHdr = 2.f;
    if (meta) {
        jclass cls = env->GetObjectClass(meta);
        readFloat3(env, meta, cls, "ratioMin",   ratioMin);
        readFloat3(env, meta, cls, "ratioMax",   ratioMax);
        readFloat3(env, meta, cls, "gamma",      gamma_);
        readFloat3(env, meta, cls, "epsilonSdr", epsSdr);
        readFloat3(env, meta, cls, "epsilonHdr", epsHdr);
        displayRatioSdr = readFloat(env, meta, cls, "displayRatioSdr");
        displayRatioHdr = readFloat(env, meta, cls, "displayRatioHdr");
    }

    // Encode gainmap as grayscale when all per-channel metadata values are equal (common case).
    bool single = allEqual3(ratioMin) && allEqual3(ratioMax) && allEqual3(gamma_)
                  && allEqual3(epsSdr) && allEqual3(epsHdr);
    bool gmGrayscale = single || gmIsAlpha;

    std::vector<uint8_t> baseJpeg = encodeToJpegBuffer(
            baseBmp.pixels, baseBmp.info.width, baseBmp.info.height, baseBmp.info.stride,
            /*isRgba=*/true, (int) quality, /*grayscale=*/false);
    if (baseJpeg.size() < 4) return JNI_FALSE;

    std::vector<uint8_t> gmJpeg = encodeToJpegBuffer(
            gmBmp.pixels, gmBmp.info.width, gmBmp.info.height, gmBmp.info.stride,
            /*isRgba=*/!gmIsAlpha, (int) quality, gmGrayscale);
    if (gmJpeg.empty()) return JNI_FALSE;

    // Build metadata segments. EXIF is embedded here so MPF offsets stay stable.
    std::vector<uint8_t> exifSeg;
    if (exifTiff) {
        jsize n = env->GetArrayLength(exifTiff);
        if (n > 0) {
            std::vector<uint8_t> tiff((size_t) n);
            env->GetByteArrayRegion(exifTiff, 0, n, (jbyte *) tiff.data());
            exifSeg = buildExifApp1(tiff.data(), tiff.size());
        }
    }
    std::vector<uint8_t> xmpSeg = buildXmpApp1(
            ratioMin, ratioMax, gamma_, epsSdr, epsHdr, displayRatioSdr, displayRatioHdr);
    // MPF gainmap offset = baseJpeg.size() - 2 regardless of how many APP segments we prepend.
    std::vector<uint8_t> mpfSeg = buildMpfApp2(
            (uint32_t) baseJpeg.size(), (uint32_t) gmJpeg.size());

    // Assemble: SOI | [exif] | xmp | mpf | base_rest | gainmap
    const char *path = env->GetStringUTFChars(outPath, nullptr);
    if (!path) return JNI_FALSE;
    bool ok = false;
    FILE *f = std::fopen(path, "wb");
    if (f) {
        const uint8_t soi[2] = {0xFF, 0xD8};
        ok = std::fwrite(soi, 1, 2, f) == 2;
        auto w = [&](const std::vector<uint8_t> &v) {
            if (ok && !v.empty()) ok = std::fwrite(v.data(), 1, v.size(), f) == v.size();
        };
        w(exifSeg);
        w(xmpSeg);
        w(mpfSeg);
        // base JPEG without its SOI (the rest already contains APP0, DQT, SOF, image data, EOI)
        if (ok && baseJpeg.size() > 2)
            ok = std::fwrite(baseJpeg.data() + 2, 1, baseJpeg.size() - 2, f) == baseJpeg.size() - 2;
        w(gmJpeg);
        std::fclose(f);
    }
    env->ReleaseStringUTFChars(outPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}
