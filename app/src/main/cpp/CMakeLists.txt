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

# Set OFF to skip libavif + aom (faster build, smaller .so); set ON to re-enable native AVIF encoding.
option(ENABLE_AVIF "Build and link libavif + aom AVIF encoder" OFF)

# --- libavif (+ aom encoder) -----------------------------------------------------------------
# Encode-only, aom codec only. AVIF_CODEC_AOM=LOCAL lets libavif build aom from source (via its
# own FetchContent at configure time) so we don't vendor aom separately. We disable the decoder,
# tests, examples and apps to keep the binary small.
if(ENABLE_AVIF)
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
endif()

# --- jpegli (standalone github.com/google/jpegli; moved out of libjxl) -----------------------
# We only need the `jpegli-static` target, which links highway + threads and exposes the
# libjpeg-compatible jpegli_* API (lib/jpegli/encode.h). Turn off tools/benchmark/JNI/extras and
# the optional codecs (sjpeg/skcms/lcms/png/openexr) so nothing but the encoder is built.
#
# Patch: jpegli's tools/CMakeLists.txt unconditionally defines jpegli_dec_fuzzer_runner even when
# JPEGLI_ENABLE_FUZZERS=OFF, and the Android Gradle CMake build compiles it despite EXCLUDE_FROM_ALL.
# Apply a patch that wraps the fuzzer-runner block in if(NOT ANDROID) before add_subdirectory.
file(READ ${THIRD_PARTY}/jpegli/tools/CMakeLists.txt _jpegli_tools_cmake)
if(_jpegli_tools_cmake MATCHES "NOT ANDROID")
    message(STATUS "jpegli patch already applied")
else()
    execute_process(
        COMMAND patch -p1 --input=${CMAKE_CURRENT_SOURCE_DIR}/patches/jpegli-no-fuzzer-runner-on-android.patch
        WORKING_DIRECTORY ${THIRD_PARTY}/jpegli
        RESULT_VARIABLE _patch_result
    )
    if(NOT _patch_result EQUAL 0)
        message(FATAL_ERROR "jpegli patch failed (exit code ${_patch_result})")
    endif()
    message(STATUS "jpegli patch applied")
endif()
unset(_jpegli_tools_cmake)
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

target_compile_definitions(dcimsort_codecs PRIVATE
    $<$<BOOL:${ENABLE_AVIF}>:ENABLE_AVIF>)

# avif/avif.h is only needed when ENABLE_AVIF; jpegli headers propagate via jpegli-static.
target_include_directories(dcimsort_codecs PRIVATE
    $<$<BOOL:${ENABLE_AVIF}>:${THIRD_PARTY}/libavif/include>
    ${THIRD_PARTY}/jpegli)

target_link_libraries(dcimsort_codecs
    $<$<BOOL:${ENABLE_AVIF}>:avif>
    jpegli-static
    jnigraphics
    log)
