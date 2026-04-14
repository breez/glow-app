// swift-tools-version: 5.9
import PackageDescription

// Local path to Spark SDK Swift package (pr/passkey-core branch).
// Replace with published package URL once SDK PR #781 merges.
let sdkPath = "../../../spark-sdk/crates/breez-sdk/bindings/langs/swift"

let package = Package(
    name: "CapacitorPasskeyPrf",
    platforms: [.iOS(.v15)],
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
            path: "ios/Sources/CapacitorPasskeyPrf"
        ),
    ]
)
