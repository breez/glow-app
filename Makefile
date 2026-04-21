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
SDK_WASM_TGZ = $(SDK_WASM_DIR)/breeztech-breez-sdk-spark-v0.1.0.tgz

ANDROID_HOME ?= $(HOME)/Library/Android/sdk

# Include a timestamp so every `make sdk-android` invocation publishes
# a UNIQUE coordinate to mavenLocal. Without this, gradle's
# `publishToMavenLocal` task can skip re-publishing if the AAR output
# file exists and the source mtime check doesn't detect a change
# through the dep graph — observed when a Kotlin edit on
# spark-sdk's `CredentialManagerPrfProvider.kt` silently kept yesterday's
# AAR in mavenLocal, so the glow-app build pulled stale code from
# the consumer side.
#
# Consumer side (plugins/capacitor-passkey-prf/android/build.gradle)
# uses the `0.1.0-local-+` wildcard to resolve to whatever latest
# timestamp exists in mavenLocal. `make sdk-android` publishes a new
# timestamped coord → gradle's dependency resolution picks it up next
# build.
#
# Devs who haven't changed spark-sdk source can SKIP `make sdk-android`;
# gradle resolves the wildcard to the last-published timestamp, which
# is already correct. This preserves the "cache hit is fast" property
# while fixing the "cache miss looks like a cache hit" bug.
SDK_MAVEN_VERSION = 0.1.0-local-$(shell date +%Y%m%d%H%M%S)

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
# Make counts parens inside $(shell ...) regardless of shell quoting, so a
# literal `(` in the grep pattern unbalances the call and errors with
# "unterminated call to function 'shell': missing ')'". Wrap the pattern
# in a var so Make sees balanced $(VAR) rather than a bare `(`.
IOS_DEVICE_GREP := iPhone.*(
IOS_DEVICE_ID ?= $(shell xcrun xctrace list devices 2>/dev/null | grep -m1 '$(IOS_DEVICE_GREP)' | sed 's/.*(\(.*\))/\1/')
ANDROID_DEVICE_ID ?= $(shell adb devices -l 2>/dev/null | grep 'device usb' | awk '{print $$1}')

# ---------- High-level targets ----------

.PHONY: setup resolve-sdk sdk sdk-ios sdk-android sdk-wasm web sync ios android deploy-ios deploy-android clean help assets

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

web: ## Build glow-web with local SDK WASM package
	cd glow-web && npm install $(SDK_WASM_TGZ) && npx vite build

sync: ## Copy web assets to native projects (without regenerating native configs)
	npx cap copy

assets: ## Regenerate native app icons and splash from glow-web/public/assets/Glow_Logo.png
	node scripts/prepare-native-assets.mjs
	npx capacitor-assets generate --ios --android \
		--iconBackgroundColor '#0a0a0f' \
		--iconBackgroundColorDark '#0a0a0f' \
		--splashBackgroundColor '#151520' \
		--splashBackgroundColorDark '#151520'

ios: web sync ## Build iOS app
	xcodebuild -project ios/App/App.xcodeproj -scheme App \
		-destination 'generic/platform=iOS' \
		-configuration Debug \
		-allowProvisioningUpdates \
		CODE_SIGN_STYLE=Automatic \
		build

android: web sync ## Build Android debug APK
	cd android && ANDROID_HOME=$(ANDROID_HOME) ./gradlew assembleDebug

deploy-ios: ios ## Build and install on connected iOS device
	ios-deploy --id $(IOS_DEVICE_ID) \
		--bundle $$(find ~/Library/Developer/Xcode/DerivedData/App-*/Build/Products/Debug-iphoneos/App.app -maxdepth 0 2>/dev/null | head -1) \
		--justlaunch

deploy-android: android ## Build and install on connected Android device
	adb -s $(ANDROID_DEVICE_ID) install -r android/app/build/outputs/apk/debug/app-debug.apk
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
	@# Generate UniFFI Kotlin bindings + copy them into the gradle lib
	@# module's sources. spark-sdk's gradle config does NOT do this
	@# automatically — devs usually have the generated files from a
	@# prior manual `make bindings-kotlin`, which is why local builds
	@# succeed on a clean gradle cache but CI's cold build fails with
	@# "Unresolved reference: breez_sdk_spark". Belongs upstream in
	@# spark-sdk's Android gradle build; until then, do it here.
	cd $(BINDINGS_DIR) && $(MAKE) bindings-kotlin
	mkdir -p $(SDK_ANDROID_DIR)/lib/src/main/kotlin
	cp -R $(BINDINGS_DIR)/ffi/kotlin/main/kotlin/. \
		$(SDK_ANDROID_DIR)/lib/src/main/kotlin/
	cd $(SDK_ANDROID_DIR) && ANDROID_HOME=$(ANDROID_HOME) \
		./gradlew :lib:publishToMavenLocal -PlibraryVersion=$(SDK_MAVEN_VERSION)
	@echo "Android SDK published to mavenLocal as breez_sdk_spark:bindings-android:$(SDK_MAVEN_VERSION)"

sdk-wasm: ## Build Spark SDK WASM package
	cd $(SDK_WASM_DIR) && $(MAKE) build
	@echo "WASM SDK built: $(SDK_WASM_TGZ)"
