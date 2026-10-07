# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Capacitor plugin classes (@CapacitorPlugin / extends Plugin) are kept by
# @capacitor/android's own consumerProguardFiles, which covers the bundled
# plugins AND the two in-house ones. Nothing to add here for those.

# Spark SDK: the AAR ships no consumer rules and its UniFFI bindings reach
# the Rust core through JNA, which resolves native functions, Structure
# fields and callback interfaces by NAME at runtime. Renaming any of those
# breaks every SDK call with a runtime lookup failure, never a build error.
#
# Scope matters here. Only the generated FFI scaffolding is name-sensitive:
# the UniffiLib interface (its method names ARE the native symbols), the
# Structure subclasses (JNA reads field names reflectively, and
# getFieldOrder() returns them as strings) and the Callback interfaces
# (invoked reflectively). That is 219 classes. The other ~2,900 are
# ordinary Kotlin data classes, enums and converters that Kotlin calls
# directly, so R8 renames both sides consistently and nothing looks them
# up by name — verified: no Class.forName, no getDeclaredField /
# getDeclaredMethod and no kotlinx.serialization anywhere in the AAR.
#
# Keeping the whole breez_sdk_spark.** and technology.breez.spark.**
# packages, which is where this started, held 3,269 classes back and left
# DEX obfuscation at 31.9% — under the 25% floor once Play measures it by
# bytes rather than class count. Matching the JNA surface instead takes it
# to 80.2% and halves the DEX. ProGuard treats `implements` as "assignable
# to", so the one rule below covers extends-Structure as well.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
# JNA is a desktop-JVM library; its AWT helpers reference java.awt.*, which
# does not exist on Android. That code is unreachable here (Native$AWT is
# only entered from desktop window handles), so the dangling refs are safe.
-dontwarn java.awt.**

# Readable stack traces in Play vitals. AGP embeds the mapping inside the
# AAB at BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map
# and Play reads it on upload, so nothing has to send it separately.
# gradle-play-publisher only uploads a mapping on the APK publish path
# (PublishApk); PublishBundle has no mapping handling at all, and we
# publish a bundle.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
