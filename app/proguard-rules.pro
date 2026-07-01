# DCIMSort ProGuard/R8 rules.
# Activities/Services are kept automatically via the manifest. The app uses no
# reflection-based serialization (geo cache uses android.util.JsonReader/Writer),
# so no additional keep rules are required.
