// swift-tools-version: 6.0
import PackageDescription

// Local path to the Spark SDK Swift package. Pinned to the SHA in
// glow-app's `.spark-sdk-ref`; rebuild via `make sdk-ios`.
let sdkPath = "../../../spark-sdk/crates/breez-sdk/bindings/langs/swift"

let package = Package(
    name: "CapacitorPasskeyPrf",
    platforms: [.iOS(.v15), .macOS(.v15)],
    products: [
        .library(name: "CapacitorPasskeyPrf", targets: ["CapacitorPasskeyPrf"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.3.0"),
        .package(path: sdkPath),
    ],
    targets: [
        .target(
            name: "CapacitorPasskeyPrf",
            dependencies: [
                .product(name: "BreezSdkSpark", package: "swift"),
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
            ],
            path: "ios/Sources/CapacitorPasskeyPrf",
            // Capacitor's CAPPluginCall isn't Sendable, so the standard
            // `Task { call.resolve() }` bridge pattern trips Swift 6
            // strict concurrency. Pin the language mode to v5 (matching
            // the capacitor-native-vault plugin); tools 6.0 stays only
            // for the .macOS(.v15) platform API.
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
