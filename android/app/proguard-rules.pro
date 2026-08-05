# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Keep line numbers so Play vitals stacks stay readable, and drop the
# original file names (the mapping file ships inside the AAB, so Play
# de-obfuscates uploaded crashes on its own).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Capacitor bridge ---
# Capacitor's own consumer rules cover plugin classes and @PluginMethod,
# but not this: the WebView calls MessageHandler.postMessage by name
# through @JavascriptInterface, so renaming it silently kills every
# JS-to-native call at run time.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- JNA + UniFFI (Spark SDK bindings) ---
# JNA ships no consumer rules of its own. It binds Java types onto native
# memory reflectively: Structure field names and their declared order are
# the native struct layout, and Library interfaces resolve by method name.
# Renaming either corrupts the FFI at run time rather than at build time,
# so these packages are kept whole instead of trimmed rule by rule.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-keepclassmembers class * implements com.sun.jna.Callback { *; }
-keep class breez_sdk_spark.** { *; }
-keep class technology.breez.spark.** { *; }

# JNA's desktop code paths reference AWT, which does not exist on Android.
# Nothing reaches them here; without this R8 fails on the dangling refs.
-dontwarn java.awt.**
