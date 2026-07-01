cmake_minimum_required(VERSION 3.22)
project(dcimsort_codecs LANGUAGES C CXX)

# This module is only meant to build for the `full` product flavor. The Gradle script passes
# -DEDITION=full; for any other configuration we produce nothing so the lite APK stays native-free.
if(NOT DEFINED EDITION OR NOT EDITION STREQUAL "full")
    message(STATUS "dcimsort_codecs: EDITION!=full, skipping native build")
    return()
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# --- size optimization, applied to every target below ----------------------------------------
# -Oz: optimize aggressively for size; gc-sections + exclude-libs strips everything unreferenced;
# -s strips the symbol table from the final .so. The encoders are large, so this matters a lot.
# (Exceptions/RTTI left enabled — libjxl/highway rely on them in places.)
add_compile_options(-O3 -ffast-math -ffunction-sections -fdata-sections -fvisibility=hidden)
add_link_options(-Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-s)

set(THIRD_PARTY ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

set(BUILD_TESTING "OFF" CACHE STRING "" FORCE)

# --- libavif (+ aom encoder) -----------------------------------------------------------------
# Encode-only, aom codec only. AVIF_CODEC_AOM=LOCAL lets libavif build aom from source (via its
# own FetchContent at configure time) so we don't vendor aom separately. We disable the decoder,
# tests, examples and apps to keep the binary small.
set(AVIF_CODEC_AOM "LOCAL" CACHE STRING "" FORCE)       # build aom from source via FetchContent
set(AVIF_CODEC_AOM_ENCODE ON CACHE BOOL "" FORCE)
set(AVIF_CODEC_AOM_DECODE OFF CACHE BOOL "" FORCE)      # encoder only — we never decode AVIF natively
set(AVIF_LIBYUV "OFF" CACHE STRING "" FORCE)            # skip libyuv (extra dep; libavif falls back)
set(AVIF_LIBSHARPYUV "OFF" CACHE STRING "" FORCE)       # skip libwebp/sharpyuv fetch
set(AVIF_BUILD_APPS OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(AVIF_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(AVIF_ENABLE_WERROR OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# aom: drop the decoder and everything non-essential for encode-only.
set(CONFIG_AV1_DECODER 0 CACHE STRING "" FORCE)
set(CONFIG_AV1_ENCODER 1 CACHE STRING "" FORCE)
set(ENABLE_TESTS 0 CACHE BOOL "" FORCE)
set(ENABLE_TOOLS 0 CACHE BOOL "" FORCE)
set(ENABLE_EXAMPLES 0 CACHE BOOL "" FORCE)
set(ENABLE_DOCS 0 CACHE BOOL "" FORCE)
add_subdirectory(${THIRD_PARTY}/libavif libavif EXCLUDE_FROM_ALL)

# --- jpegli (standalone github.com/google/jpegli; moved out of libjxl) -----------------------
# We only need the `jpegli-static` target, which links highway + threads and exposes the
# libjpeg-compatible jpegli_* API (lib/jpegli/encode.h). Turn off tools/benchmark/JNI/extras and
# the optional codecs (sjpeg/skcms/lcms/png/openexr) so nothing but the encoder is built.
set(JPEGLI_ENABLE_TOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_MANPAGES OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JNI OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SJPEG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_SKCMS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_OPENEXR OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(JPEGLI_BUNDLE_LIBPNG OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_FUZZERS OFF CACHE BOOL "" FORCE)
set(JPEGLI_ENABLE_JPEGLI_LIBJPEG ON CACHE BOOL "" FORCE) # provides the libjpeg-compatible API
add_subdirectory(${THIRD_PARTY}/jpegli jpegli EXCLUDE_FROM_ALL)

# --- our JNI bridge --------------------------------------------------------------------------
add_library(dcimsort_codecs SHARED native-codecs.cpp)

# avif/* headers; jpegli's headers (lib/jpegli/encode.h + generated <jpeglib.h>) propagate from
# the jpegli-static target's PUBLIC include dirs, but add the repo root too for robustness.
target_include_directories(dcimsort_codecs PRIVATE
    ${THIRD_PARTY}/libavif/include
    ${THIRD_PARTY}/jpegli)

# `avif` and `jpegli-static` are the static targets exported by the two projects above.
target_link_libraries(dcimsort_codecs
    avif
    jpegli-static
    jnigraphics
    log)
