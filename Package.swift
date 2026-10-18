// swift-tools-version:6.0
// Swift package for iOS apps. CompressGps.xcframework is built from the Kotlin
// sources by Kotlin Multiplatform and attached to each GitHub release. The
// release workflow fills in the url and checksum below on the release's tag;
// on main they are placeholders, so only use tagged versions of this package.
import PackageDescription

let package = Package(
    name: "CompressGps",
    platforms: [
        .iOS(.v18),
    ],
    products: [
        .library(name: "CompressGps", targets: ["CompressGps"]),
    ],
    targets: [
        .binaryTarget(
            name: "CompressGps",
            url: "https://github.com/sprintf/compress-gps/releases/download/v0.0.0/CompressGps.xcframework.zip",
            checksum: "0000000000000000000000000000000000000000000000000000000000000000"
        ),
    ]
)
