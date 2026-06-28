# Add project specific ProGuard rules here.
# By default, the noise of code shrinker (R8) is sufficient.
# You can add custom rules below if needed.

# Keep Kotlin standard library and coroutines metadata if needed
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep View constructors used by layout inflater
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
# Keep View constructors used by layout inflater
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep JSch classes which are loaded via reflection for SSH
-keep class com.jcraft.jsch.** { *; }

# Ignore warnings for optional JSch dependencies not present on Android
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.bouncycastle.**
-dontwarn org.ietf.jgss.**
-dontwarn org.newsclub.net.unix.**
-dontwarn org.slf4j.**
