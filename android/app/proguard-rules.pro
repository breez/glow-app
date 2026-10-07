# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Plugin classes come from @capacitor/android's consumerProguardFiles.

# UniFFI reaches the Rust core through JNA, which looks up native symbols,
# Structure field names and callbacks by name. Only that bridge is
# name-sensitive; `implements` means "assignable to", so this also covers
# Structure subclasses. Do NOT widen to breez_sdk_spark.** — that holds
# ~3,000 classes back and drops obfuscation under Play's 2027 floor.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }

# JNA's desktop AWT helpers reference java.awt.*, absent on Android.
# R8 fails the build without this.
-dontwarn java.awt.**

# Readable traces in Play vitals. AGP embeds the mapping in the AAB.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
