// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "XMAudioStudio",
    platforms: [
        .macOS(.v15)
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
            dependencies: ["SonyHeadphonesKit", "AudioStudioKit", "AudioDSPKit"],
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
        )
    ]
)
