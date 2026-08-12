# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Capacitor plugin classes (@CapacitorPlugin / extends Plugin) are kept by
# @capacitor/android's own consumerProguardFiles, which covers the bundled
# plugins AND the two in-house ones. Nothing to add here for those.

# Spark SDK: the AAR ships no consumer rules and its UniFFI bindings talk to
# the Rust core through JNA, which resolves native functions, Structure
# fields and callback interfaces by name at runtime. Obfuscating any of it
# breaks every SDK call with a runtime lookup failure, not a build error.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class breez_sdk_spark.** { *; }
-keep class technology.breez.spark.** { *; }
# JNA is a desktop-JVM library; its AWT helpers reference java.awt.*, which
# does not exist on Android. That code is unreachable here (Native$AWT is
# only entered from desktop window handles), so the dangling refs are safe.
-dontwarn java.awt.**

# Readable stack traces in Play vitals. gradle-play-publisher uploads
# mapping.txt alongside the AAB, so Play deobfuscates automatically.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
