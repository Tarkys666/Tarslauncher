# Security-focused ProGuard rules for TarsLauncher
# Aggressive obfuscation and shrinking for ultra-minimal APK footprint

# Multi-pass optimization
-optimizationpasses 5
-optimizations *
-allowaccessmodification

# Repackage to default package for minimal class names
-repackageclasses ''

# Remove debugging information & strip non-essential attributes
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep main components
-keep class com.tarslauncher.app.MainActivity

# Keep data model classes and their fields for Gson serialization
-keep class com.tarslauncher.app.data.model.** { *; }
-keepclassmembers class com.tarslauncher.app.data.model.** { *; }
-keep class com.tarslauncher.app.data.updater.** { *; }
-keepclassmembers class com.tarslauncher.app.data.updater.** { *; }

# Gson serialization optimization: allow R8 to shrink unused Gson code
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Remove logging in release builds
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int i(...);
    public static int w(...);
    public static int d(...);
    public static int e(...);
}

# Strip Kotlin runtime assertion overhead in release builds
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNullParameter(...);
    public static void checkParameterIsNotNull(...);
    public static void checkExpressionValueIsNotNull(...);
}

# Anti-tampering: Make it harder to hook methods
-keepclassmembers class * {
    native <methods>;
}