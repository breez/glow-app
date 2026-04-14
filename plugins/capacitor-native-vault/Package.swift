// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "CapacitorNativeVault",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "CapacitorNativeVault", targets: ["CapacitorNativeVault"])
    ],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", exact: "8.3.0"),
    ],
    targets: [
        .target(
            name: "CapacitorNativeVault",
            dependencies: [
                .product(name: "Capacitor", package: "capacitor-swift-pm"),
                .product(name: "Cordova", package: "capacitor-swift-pm"),
            ],
            path: "ios/Sources/CapacitorNativeVault"
        ),
    ]
)
