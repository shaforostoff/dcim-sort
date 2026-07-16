// Stub native library for non-arm64 (e.g. armeabi-v7a) ABIs.
//
// The `full` flavor's codecs (jpegli, libavif + aom) are only built for arm64-v8a. To keep the
// build small and fast on 32-bit we compile this stub instead of the real codec stack. The
// full-flavor Java (NativeCodecs) only calls System.loadLibrary on 64-bit devices, so this stub
// is never loaded or invoked at runtime — it exists purely so the app bundle carries a .so for
// this ABI, which makes Google Play treat 32-bit devices as compatible and lets the app install.
// On those devices the app runs the pure-Java code paths, matching the lite flavor's behavior.
extern "C" int dcimsort_codecs_stub(void) { return 0; }
