// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "XMAudioStudio",
    platforms: [
        .macOS(.v15)
    ],
    // Library products exist so the Xcode project (project.yml, which adds the WidgetKit
    // extension) links the same modules that `swift build` compiles.
    products: [
        .library(name: "SonyHeadphonesKit", targets: ["SonyHeadphonesKit"]),
        .library(name: "AudioStudioKit", targets: ["AudioStudioKit"]),
        .library(name: "AudioDSPKit", targets: ["AudioDSPKit"]),
        .library(name: "WidgetBridgeKit", targets: ["WidgetBridgeKit"]),
        .executable(name: "XMAudioStudio", targets: ["XMAudioStudio"]),
        .executable(name: "XMAudioProbe", targets: ["XMAudioProbe"])
    ],
    targets: [
        // Sony MDR protocol, RFCOMM transport and the session controller.
        .target(
            name: "SonyHeadphonesKit",
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                .linkedFramework("IOBluetooth")
            ]
        ),
        // Audio maths and profiles: biquads, AutoEq import and fitting, the shared profile
        // format. No UI and no transport code; it only reads the device catalogue from
        // SonyHeadphonesKit. Mirrored by android/core (eq, presets).
        .target(
            name: "AudioStudioKit",
            dependencies: ["SonyHeadphonesKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // App <-> widget IPC: the state snapshot and the command mailbox in the App Group
        // container, plus the Darwin notification. Foundation only, so the sandboxed widget
        // extension links it without pulling in IOBluetooth or the protocol code.
        .target(
            name: "WidgetBridgeKit",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // Local DSP ("EQ avanzado"): realtime parametric biquad chain, limiter and the
        // Core Audio process-tap engine that runs it on this Mac's output (ADR-0009).
        // Optional and off by default; no UI and no Bluetooth code.
        .target(
            name: "AudioDSPKit",
            dependencies: ["AudioStudioKit"],
            swiftSettings: [
                .swiftLanguageMode(.v5),
                // Always optimized: unoptimized Swift allocates inside plain `for` loops
                // (unspecialized generic iterators), which is not acceptable on the audio
                // IO thread even in a debug build. RealtimeAllocationTests checks this.
                .unsafeFlags(["-O"], .when(configuration: .debug))
            ],
            linkerSettings: [
                .linkedFramework("Accelerate"),
                .linkedFramework("CoreAudio")
            ]
        ),
        .executableTarget(
            name: "XMAudioStudio",
            dependencies: ["SonyHeadphonesKit", "AudioStudioKit", "AudioDSPKit", "WidgetBridgeKit"],
            exclude: ["Resources"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // Developer tool: connects to the headphones and sends raw hex payloads,
        // printing every reply. Used to verify command layouts on real hardware.
        .executableTarget(
            name: "XMAudioProbe",
            dependencies: ["SonyHeadphonesKit"],
            exclude: ["Info.plist"],
            swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [
                // Embed Info.plist into the binary so the Bluetooth privacy check
                // (NSBluetoothAlwaysUsageDescription) passes for a bare CLI tool.
                .unsafeFlags([
                    "-Xlinker", "-sectcreate",
                    "-Xlinker", "__TEXT",
                    "-Xlinker", "__info_plist",
                    "-Xlinker", "Sources/XMAudioProbe/Info.plist"
                ])
            ]
        ),
        .testTarget(
            name: "SonyHeadphonesKitTests",
            dependencies: ["SonyHeadphonesKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "AudioStudioKitTests",
            dependencies: ["AudioStudioKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "AudioDSPKitTests",
            dependencies: ["AudioDSPKit", "AudioStudioKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "WidgetBridgeKitTests",
            dependencies: ["WidgetBridgeKit", "SonyHeadphonesKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        // The app-side half of the widget bridge: commands executed through
        // HeadphonesController (demo transport), snapshots, throttled reloads.
        .testTarget(
            name: "XMAudioStudioTests",
            dependencies: ["XMAudioStudio", "SonyHeadphonesKit", "WidgetBridgeKit"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        )
    ]
)
