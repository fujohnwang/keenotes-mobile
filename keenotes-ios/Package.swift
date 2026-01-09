// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "KeeNotes",
    platforms: [
        .iOS(.v15)
    ],
    products: [
        .library(name: "KeeNotes", targets: ["KeeNotes"])
    ],
    dependencies: [
        .package(url: "https://github.com/groue/GRDB.swift.git", from: "6.24.0"),
        .package(url: "https://github.com/nicklockwood/SwiftFormat.git", from: "0.53.0")
    ],
    targets: [
        .target(
            name: "KeeNotes",
            dependencies: [
                .product(name: "GRDB", package: "GRDB.swift")
            ],
            path: "KeeNotes"
        )
    ]
)
