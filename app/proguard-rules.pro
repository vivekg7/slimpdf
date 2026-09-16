# The app has no reflection, no serialization library and no JNI, so the defaults in
# proguard-android-optimize.txt are enough. Views inflated from XML are referenced by
# name, so keep their (Context, AttributeSet) constructors.
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Size: flatten everything into the default package and let R8 widen access so it can
# merge and inline more aggressively. Safe here because nothing is looked up by name.
-repackageclasses ''
-allowaccessmodification
