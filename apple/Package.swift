// swift-tools-version:5.9
import PackageDescription

// MangoProtocol is plain Swift (Foundation only) so its tests also run on Linux.
// MangoKit holds the Apple-only parts (VideoToolbox, Network.framework) and is
// only declared when building on an Apple platform.

var products: [Product] = [
    .library(name: "MangoProtocol", targets: ["MangoProtocol"]),
]
var targets: [Target] = [
    .target(name: "MangoProtocol"),
    .testTarget(name: "MangoProtocolTests", dependencies: ["MangoProtocol"]),
]

#if canImport(Darwin)
products.append(.library(name: "MangoKit", targets: ["MangoKit"]))
targets.append(.target(name: "MangoKit", dependencies: ["MangoProtocol"]))
#endif

let package = Package(
    name: "MangoKit",
    platforms: [.macOS(.v13), .iOS(.v16)],
    products: products,
    targets: targets
)
