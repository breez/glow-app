# glow-app development build automation
#
# Requires: Node.js 22+, Rust (stable), Xcode 16+, Android Studio + NDK
# See DEVELOPMENT.md for full prerequisites.

# ---------- Configuration ----------

# Path to local spark-sdk checkout (pr/passkey-core branch).
#
# MUST be absolute: the `web` target does `cd glow-web && npm install $(SDK_WASM_TGZ)`,
# and a relative path stops resolving from glow-web/ (it would look up
# glow-app/glow-web/../spark-sdk/... which is glow-app/spark-sdk — wrong,
# spark-sdk is a sibling of glow-app, not a child). `$(abspath ...)` resolves
# to an absolute path without requiring the target to exist, so overriding
# via `make SPARK_SDK_DIR=/path/to/spark-sdk` still works.
SPARK_SDK_DIR ?= $(abspath ../spark-sdk)
BINDINGS_DIR = $(SPARK_SDK_DIR)/crates/breez-sdk/bindings
SDK_SWIFT_DIR = $(BINDINGS_DIR)/langs/swift
SDK_ANDROID_DIR = $(BINDINGS_DIR)/langs/android
SDK_WASM_DIR = $(SPARK_SDK_DIR)/packages/wasm
# glow-web vendors its own SDK tarball at `glow-web/vendor/...` and references
# it from package.json (introduced in glow-web 8ccdb71 to defeat Vercel's stale
# `node_modules` cache for `file:` deps). The Makefile no longer pins a single
# expected output filename: a fresh `make sdk-wasm` may emit
# `breeztech-breez-sdk-spark-<version>.tgz` for whatever version is currently
# in `packages/wasm/package.json` (with or without a prerelease tag).
#
# The `web` target installs from glow-web's package.json directly. To flow a
# fresh local SDK build into glow-web, copy the tarball produced by
# `make sdk-wasm` over `glow-web/vendor/<file>.tgz` and update glow-web's
# package.json `file:` reference if the version changed.
SDK_WASM_TGZ = $(firstword $(wildcard $(SDK_WASM_DIR)/breeztech-breez-sdk-spark-*.tgz))

ANDROID_HOME ?= $(HOME)/Library/Android/sdk

# spark-sdk's `package-android` (cargo-ndk) requires ANDROID_NDK_HOME and
# errors out if it is unset. Default to the highest NDK installed under
# $(ANDROID_HOME)/ndk; override with `make sdk-android ANDROID_NDK_HOME=...`
# if a specific NDK is needed.
ANDROID_NDK_HOME ?= $(lastword $(sort $(wildcard $(ANDROID_HOME)/ndk/*)))

# Stamp the published mavenLocal coordinate with the pinned spark-sdk SHA
# from .spark-sdk-ref so the plugin can depend on this EXACT version
# instead of a floating `0.1.0-local-+` wildcard. The wildcard resolved
# to whatever timestamped coord was published LAST by any local SDK
# build, so a stale artifact from an unrelated branch could be linked
# silently (issue #72). The plugin build.gradle derives the same string
# from the same file. Freshness on re-publish comes from `make
# sdk-android` rebuilding the native libs (issue #71): the AAR inputs
# change, so gradle re-publishes the coordinate.
SPARK_SDK_SHA = $(shell grep -vE '^[[:space:]]*\#' .spark-sdk-ref | grep -oiE '[0-9a-f]{40}' | head -n1 | cut -c1-12)
SDK_MAVEN_VERSION = 0.1.0-local-$(SPARK_SDK_SHA)

# Capacitor 8 + AGP require JDK 21. Default to Android Studio's bundled
# JBR (which is 21+) if JAVA_HOME isn't already set. Override with
# `make JAVA_HOME=/path/to/jdk21` if you have JDK 21 from Homebrew or
# another source.
ANDROID_STUDIO_JBR = /Applications/Android Studio.app/Contents/jbr/Contents/Home
ifeq ($(JAVA_HOME),)
ifneq ($(wildcard $(ANDROID_STUDIO_JBR)),)
JAVA_HOME := $(ANDROID_STUDIO_JBR)
export JAVA_HOME
endif
endif

# Device IDs (override with: make deploy-ios IOS_DEVICE_ID=xxx)
#
# `xcrun xctrace list devices` (the previous picker source) doesn't
# distinguish online from offline devices and orders Apple Watch
# entries before iPhone entries on Xcode 26, so the old `iPhone.*(`
# grep happily picked an unplugged secondary iPhone or the watch.
# Switch to `devicectl --json-output` and filter on
#   productType startswith "iPhone"  (excludes Apple Watch)
#   transportType == "wired"          (excludes paired-but-unplugged)
# which uniquely picks the USB-tethered iPhone.
IOS_DEVICECTL_JSON := /tmp/devicectl-devices.json
IOS_DEVICE_ID ?= $(shell xcrun devicectl list devices --json-output $(IOS_DEVICECTL_JSON) >/dev/null 2>&1 && python3 -c "import json,sys; d=json.load(open('$(IOS_DEVICECTL_JSON)'))['result']['devices']; print(next((x['hardwareProperties']['udid'] for x in d if x.get('hardwareProperties',{}).get('productType','').startswith('iPhone') and x.get('connectionProperties',{}).get('transportType')=='wired'), ''))" 2>/dev/null)
ANDROID_DEVICE_ID ?= $(shell adb devices -l 2>/dev/null | grep 'device usb' | awk '{print $$1}')

# ---------- High-level targets ----------

.PHONY: setup resolve-sdk sdk sdk-ios sdk-android sdk-wasm strip-xcframework-dsyms web sync ios android deploy-ios deploy-android clean help assets

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-18s %s\n", $$1, $$2}'

setup: ## Full first-time setup
	git submodule update --init --recursive
	npm install
	cd glow-web && npm install
	$(MAKE) sdk
	$(MAKE) web
	npx cap sync

resolve-sdk: ## Clone spark-sdk at the pinned SHA (or verify existing checkout matches)
	./scripts/resolve-spark-sdk.sh

sdk: resolve-sdk sdk-ios sdk-android sdk-wasm ## Build Spark SDK for all platforms

web: ## Build glow-web (uses glow-web's vendored SDK tarball; see SDK_WASM_TGZ comment)
	cd glow-web && npm install && npx vite build

sync: ## Copy web assets to native projects (without regenerating native configs)
	npx cap copy

assets: ## Regenerate native app icons and splash from glow-web/public/assets/Glow_Logo.png
	node scripts/prepare-native-assets.mjs
	npx capacitor-assets generate --ios --android \
		--iconBackgroundColor '#0a0a0f' \
		--iconBackgroundColorDark '#0a0a0f' \
		--splashBackgroundColor '#0f0f18' \
		--splashBackgroundColorDark '#0f0f18'
	@# capacitor-assets emits adaptive-icon XML that references
	@# `@mipmap/ic_launcher_background` (a PNG drawable that the tool
	@# does NOT actually generate) and wraps the foreground in a
	@# 16.7% inset that shrinks the safe-zone-baked logo. Restore the
	@# project's existing pattern: solid `@color/ic_launcher_background`
	@# (defined in values/ic_launcher_background.xml) + un-inset
	@# foreground sized via prepare-native-assets.mjs. Without this
	@# overwrite, `gradle :app:assembleDebug` fails resource linking
	@# with "resource mipmap/ic_launcher_background not found".
	@for f in android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml \
	          android/app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml; do \
		printf '%s\n' \
			'<?xml version="1.0" encoding="utf-8"?>' \
			'<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">' \
			'    <background android:drawable="@color/ic_launcher_background"/>' \
			'    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>' \
			'</adaptive-icon>' > "$$f"; \
	done
	@# Android cold-launch splash: copy the script-emitted splash_logo.png
	@# into drawable-xhdpi/ and write the layer-list drawable that
	@# styles.xml's launch theme points at (@drawable/splash_window).
	@# Without this layer-list wrapper, a raw bitmap as the window
	@# background gets stretched to fill the (typically tall portrait)
	@# window — which is what the user previously saw as a vertically-
	@# stretched logo for ~50ms before the @capacitor/splash-screen
	@# plugin took over.
	@mkdir -p android/app/src/main/res/drawable-xhdpi
	@cp resources/splash_logo.png android/app/src/main/res/drawable-xhdpi/splash_logo.png
	@printf '%s\n' \
		'<?xml version="1.0" encoding="utf-8"?>' \
		'<layer-list xmlns:android="http://schemas.android.com/apk/res/android">' \
		'    <item android:drawable="@color/spark_dark" />' \
		'    <item>' \
		'        <bitmap android:src="@drawable/splash_logo" android:gravity="center" />' \
		'    </item>' \
		'</layer-list>' > android/app/src/main/res/drawable/splash_window.xml

strip-xcframework-dsyms: ## Strip DebugSymbolsPath from spark-sdk's xcframework Info.plist
	@# spark-sdk's xcframework Info.plist declares `DebugSymbolsPath=dSYMs`
	@# for each AvailableLibraries slice, expecting per-slice dSYM bundles
	@# from spark-sdk's CI publish workflow. Local `make sdk-ios` doesn't
	@# run dsymutil, so the declared paths don't exist and Xcode 26 fails
	@# with "Missing path (...) from XCFramework ... as defined by
	@# DebugSymbolsPath". `make sdk-ios` strips these at the tail of its
	@# build, but a fresh spark-sdk checkout (or `git restore` against the
	@# Info.plist) reverts the strip. Apply it on every iOS build so the
	@# build is robust to an unstripped Info.plist without needing a full
	@# SDK rebuild.
	@for i in 0 1 2; do \
		plutil -remove "AvailableLibraries.$$i.DebugSymbolsPath" \
			$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/Info.plist 2>/dev/null || true; \
	done

ios: resolve-sdk web sync strip-xcframework-dsyms ## Build iOS app
	xcodebuild -project ios/App/App.xcodeproj -scheme App \
		-destination 'generic/platform=iOS' \
		-configuration Debug \
		-allowProvisioningUpdates \
		CODE_SIGN_STYLE=Automatic \
		build

android: resolve-sdk web sync ## Build Android debug APK
	cd android && ANDROID_HOME=$(ANDROID_HOME) ./gradlew assembleDebug

deploy-ios: ios ## Build and install on connected iOS device
	@# Switched from `ios-deploy --justlaunch` to Apple's `xcrun devicectl`
	@# in Xcode 16+. ios-deploy looks up `DeveloperDiskImage.dmg` under
	@# .../iPhoneOS.platform/DeviceSupport/<ver>/, but Apple replaced
	@# that flow with on-demand Personalized DDI mounted via CoreDevice.
	@# The .dmg files no longer ship in Xcode 26, so ios-deploy falls
	@# back to "no logging, no automatic launch" mode. devicectl uses
	@# CoreDevice's auto-DDI-mount and is what Xcode itself drives.
	@APP=$$(find ~/Library/Developer/Xcode/DerivedData/App-*/Build/Products/Debug-iphoneos/App.app -maxdepth 0 2>/dev/null | head -1); \
		xcrun devicectl device install app --device $(IOS_DEVICE_ID) "$$APP"
	xcrun devicectl device process launch --device $(IOS_DEVICE_ID) technology.breez.glow.dev

deploy-android: android ## Build and install on connected Android device
	@# `-d` allows installing over a higher versionCode (debuggable APKs only),
	@# which lets a local debug build replace a FAD preview install without
	@# forcing the developer to uninstall first. Skipping `-d` produces
	@# `INSTALL_FAILED_VERSION_DOWNGRADE` whenever the device has a CI-signed
	@# build (CI bumps versionCode per run; local builds use the static
	@# value in build.gradle).
	adb -s $(ANDROID_DEVICE_ID) install -r -d android/app/build/outputs/apk/debug/app-debug.apk
	@# The Debug build's applicationId is `technology.breez.glow.dev` (from app/build.gradle)
	@# and the MainActivity class lives in the `technology.breez.glow` namespace, so
	@# `am start -n technology.breez.glow.dev/.MainActivity` resolves to the wrong FQCN and
	@# fails with "Activity class does not exist". `monkey` takes just the package
	@# name and resolves the default launcher activity automatically — resilient to
	@# applicationId suffixes and namespace changes.
	adb -s $(ANDROID_DEVICE_ID) shell monkey -p technology.breez.glow.dev -c android.intent.category.LAUNCHER 1

clean: ## Remove build artifacts
	rm -rf glow-web/dist
	rm -rf android/app/build
	rm -rf android/build
	cd plugins/capacitor-passkey-prf && rm -rf dist ios/.build android/build
	cd plugins/capacitor-native-vault && rm -rf dist ios/.build android/build

# ---------- SDK build targets ----------

sdk-ios: _sdk-ios-compile _sdk-ios-bindings _sdk-ios-package ## Build Spark SDK for iOS

_sdk-ios-compile:
	cd $(BINDINGS_DIR) && $(MAKE) build-release-target-aarch64-apple-ios

_sdk-ios-bindings:
	cd $(BINDINGS_DIR) && $(MAKE) build-release
	cd $(BINDINGS_DIR) && $(MAKE) bindings-swift

_sdk-ios-package:
	@# Copy generated Swift files into SDK package
	mkdir -p $(SDK_SWIFT_DIR)/Sources/BreezSdkSpark
	cp $(BINDINGS_DIR)/ffi/swift/*.swift $(SDK_SWIFT_DIR)/Sources/BreezSdkSpark/
	@# Copy headers into xcframework
	cp $(BINDINGS_DIR)/ffi/swift/breez_sdk_*FFI.h \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/ios-arm64/breez_sdk_sparkFFI.framework/Headers/
	cp $(BINDINGS_DIR)/ffi/swift/breez_sdk_*FFI.h \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/ios-arm64_x86_64-simulator/breez_sdk_sparkFFI.framework/Headers/
	cp $(BINDINGS_DIR)/ffi/swift/breez_sdk_*FFI.h \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/macos-arm64_x86_64/breez_sdk_sparkFFI.framework/Versions/A/Headers/
	@# Copy iOS binary into xcframework
	cp $(SPARK_SDK_DIR)/target/aarch64-apple-ios/release/libbreez_sdk_spark_bindings.dylib \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/ios-arm64/breez_sdk_sparkFFI.framework/breez_sdk_sparkFFI
	install_name_tool -id @rpath/breez_sdk_sparkFFI.framework/breez_sdk_sparkFFI \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/ios-arm64/breez_sdk_sparkFFI.framework/breez_sdk_sparkFFI
	@# Strip `DebugSymbolsPath` from the xcframework's Info.plist. The
	@# checked-in Info.plist declares `DebugSymbolsPath=dSYMs` because
	@# spark-sdk's PUBLISHING workflows (.github/workflows/build-bindings-*.yml)
	@# invoke dsymutil to generate per-slice dSYMs before packaging. The
	@# local `make package-xcframework` target we use here does NOT
	@# produce dSYMs, so the declared path doesn't exist — and Xcode 26
	@# fails the build with: "Missing path (...) from XCFramework
	@# 'breez_sdk_sparkFFI.xcframework' as defined by 'DebugSymbolsPath'".
	@#
	@# glow-app doesn't need symbolicated Rust stack frames in local /
	@# unsigned CI builds (line-tables-only DWARF in our Rust binaries is
	@# enough for `lldb` attaches), so stripping the declared path keeps
	@# the xcframework consistent with what we actually produce. If we
	@# ever need dSYMs here (e.g., to symbolicate TestFlight crash logs
	@# on a CI-built release IPA), port dsymutil invocation from spark-sdk's
	@# publish workflow into this target and drop this strip.
	plutil -remove 'AvailableLibraries.0.DebugSymbolsPath' \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/Info.plist 2>/dev/null || true
	plutil -remove 'AvailableLibraries.1.DebugSymbolsPath' \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/Info.plist 2>/dev/null || true
	plutil -remove 'AvailableLibraries.2.DebugSymbolsPath' \
		$(SDK_SWIFT_DIR)/breez_sdk_sparkFFI.xcframework/Info.plist 2>/dev/null || true
	@echo "iOS SDK ready"

sdk-android: ## Build Spark SDK for Android and publish to mavenLocal
	@# Rebuild the native libraries for all four Android ABIs AND
	@# regenerate the UniFFI Kotlin bindings from the SAME checkout, then
	@# copy both into the gradle lib module's sources. package-android
	@# runs cargo-ndk + gobley-uniffi-bindgen and refreshes
	@# langs/.../jniLibs. Using bindings-kotlin alone regenerated the
	@# Kotlin but left whatever stale `.so`s already sat in jniLibs, so
	@# the published AAR mismatched its own bindings and crashed at
	@# runtime with missing UniFFI symbols (issue #71). Belongs upstream
	@# in spark-sdk's Android gradle build; until then, do it here.
	@# Wipe previously-generated bindings + gradle outputs, then RE-CREATE the
	@# kotlin source dir BEFORE package-android runs. The generated .kt are
	@# git-ignored, so a fresh CI checkout has no lib/src/main/kotlin dir. With
	@# the dir absent, package-android's `cp -r breez_sdk_spark .../kotlin/`
	@# copies the CONTENTS flat (kotlin/breez_sdk_spark.jvm.kt) instead of
	@# nesting under kotlin/breez_sdk_spark/. glow-app's later `cp -R . kotlin/`
	@# then adds the nested copy too, so both land in package breez_sdk_spark
	@# and collide as ~3400 duplicate declarations at compileReleaseKotlin.
	@# Local trees already have kotlin/ from prior builds, so they never hit it.
	@# Pre-creating the dir makes the SDK's cp nest, leaving a single copy.
	rm -rf $(BINDINGS_DIR)/ffi/kotlin $(SDK_ANDROID_DIR)/lib/src/main/kotlin $(SDK_ANDROID_DIR)/lib/build
	mkdir -p $(SDK_ANDROID_DIR)/lib/src/main/kotlin
	cd $(BINDINGS_DIR) && ANDROID_HOME=$(ANDROID_HOME) \
		ANDROID_NDK_HOME=$(ANDROID_NDK_HOME) $(MAKE) package-android
	cp -R $(BINDINGS_DIR)/ffi/kotlin/main/kotlin/. \
		$(SDK_ANDROID_DIR)/lib/src/main/kotlin/
	cd $(SDK_ANDROID_DIR) && ANDROID_HOME=$(ANDROID_HOME) \
		./gradlew :lib:publishToMavenLocal -PlibraryVersion=$(SDK_MAVEN_VERSION)
	@echo "Android SDK published to mavenLocal as breez_sdk_spark:bindings-android:$(SDK_MAVEN_VERSION)"

sdk-wasm: ## Build Spark SDK WASM package
	cd $(SDK_WASM_DIR) && $(MAKE) build
	@echo "WASM SDK built. To flow into glow-web, copy the tarball at"
	@echo "  $(SDK_WASM_DIR)/breeztech-breez-sdk-spark-*.tgz"
	@echo "into glow-web/vendor/ and align glow-web/package.json's file: ref."
