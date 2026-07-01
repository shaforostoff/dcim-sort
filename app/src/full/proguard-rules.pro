# Keep the JNI entry points and the types the native code reflects on. R8 must not rename or remove
# these, or the .so will fail to bind / read fields at runtime.
-keepclasseswithmembernames,includedescriptorclasses class com.shaforostoff.dcimsort.codec.NativeCodecs {
    native <methods>;
}

# native-codecs.cpp reads GainmapMeta's float fields by name via JNI reflection.
-keep class com.shaforostoff.dcimsort.codec.GainmapMeta { *; }
