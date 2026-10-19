# Security-focused ProGuard rules for TarsLauncher
# Aggressive obfuscation to prevent reverse engineering

# Enable all optimizations
-optimizations *
-allowaccessmodification

# Obfuscation settings
# -obfuscationdictionary dictionary.txt
# -classobfuscationdictionary dictionary.txt
# -packageobfuscationdictionary dictionary.txt
-repackageclasses ''

# Remove debugging information
-renamesourcefileattribute SourceFile
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod

# Keep main components
-keep public class com.tarslauncher.app.MainActivity {
    public *;
}

# Keep data model classes and their fields for Gson serialization
-keep class com.tarslauncher.app.data.model.** { *; }
-keepclassmembers class com.tarslauncher.app.data.model.** { *; }
-keep class com.tarslauncher.app.data.updater.** { *; }
-keepclassmembers class com.tarslauncher.app.data.updater.** { *; }

# Gson serialization
-keepattributes Signature
-keep class com.google.gson.** { *; }
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

# Anti-tampering: Make it harder to hook methods
-keepclassmembers class * {
    native <methods>;
}