# Native codecs (full flavor only)

`libdcimsort_codecs.so` provides the `full` flavor's AVIF (libavif + aom, encode-only, HDR + EXIF)
and JPEG (jpegli) encoders.

CMake runs for **every** variant, but `CMakeLists.txt` returns early unless it is passed
`-DEDITION=full`, so the `lite` flavor produces **no** `.so` (its APK stays native-free) while the
`full` flavor builds the codecs. This EDITION gate (rather than detecting the variant from Gradle
task names) is what guarantees the native lib is built whenever a `full` APK/bundle is produced,
however the build is invoked. Because CMake runs for all variants, the NDK + CMake must be installed
even to build lite (it just compiles nothing).

## One-time setup

```bash
# Fetch the codec sources (libavif, jpegli) and jpegli's own deps (highway, …):
git submodule update --init --recursive
```

Install, via Android Studio SDK Manager (or `sdkmanager`):
- **NDK** (r26+; pin matches `android.ndkVersion` if set)
- **CMake** 3.22.1+

aom (the AV1 encoder) and any other libavif `LOCAL` deps are pulled by libavif's own CMake
`FetchContent` at configure time, so the **first full build needs network access** and is slow.

## Build

```bash
./gradlew :app:assembleFullDebug      # or assembleFullRelease / bundleFullRelease
```

`lite` builds (`assembleLiteDebug`, …) skip all of the above.

## Layout

- `CMakeLists.txt` — gated on `-DEDITION=full`; builds libavif (encode-only aom, no decoder/tests)
  and jpegli (`jpegli-static`, tools/JNI/extras off), then links them into the JNI bridge.
- `native-codecs.cpp` — JNI bridge implementing `NativeCodecs.nativeEncodeAvif/nativeEncodeJpeg`.
- `third_party/libavif` — submodule, pinned in the superproject. Gain-map + EXIF APIs (libavif ≥1.4).
- `third_party/jpegli` — submodule (github.com/google/jpegli; moved out of libjxl).

## Notes / tuning

- Size is controlled in `CMakeLists.txt` via `-Oz`, `--gc-sections`, `--exclude-libs,ALL`, `-s`,
  encode-only aom (`AVIF_CODEC_AOM_DECODE=OFF`), and disabling jpegli's optional codecs.
- Only `arm64-v8a` is built (`abiFilters` in `app/build.gradle`); ship as an AAB.
- Gain-map metadata mapping in `native-codecs.cpp` (`applyGainMapMeta`) targets libavif's
  `avifGainMap` fields; revisit if the pinned libavif version changes them.
