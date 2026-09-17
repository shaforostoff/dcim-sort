# DCIMSort ProGuard/R8 rules.
# Activities/Services are kept automatically via the manifest. The app uses no
# reflection-based serialization (geo cache uses android.util.JsonReader/Writer),
# so no additional keep rules are required.

# Strip debug/verbose logging from release builds. These sit on the per-photo organize path, so
# removing the calls also removes the string concatenation that builds their messages — R8 drops
# the now-unused StringBuilder chains once the call itself is gone. Log.w/Log.e are kept: those
# report real failures.
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static boolean isLoggable(...);
}
