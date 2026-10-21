import XCTest
@testable import SonyHeadphonesKit

/// Runs the shared protocol fixtures in `shared/protocol-fixtures/`. The Android suite
/// (`android/core/.../EventCommandConformanceTest.kt`, `FrameConformanceTest.kt`) runs the
/// same files, so both apps provably read and write the same Sony bytes.
final class ConformanceTests: XCTestCase {

    static let sharedDir = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // SonyHeadphonesKitTests
        .deletingLastPathComponent() // Tests
        .deletingLastPathComponent() // macos
        .deletingLastPathComponent() // repository root
        .appendingPathComponent("shared")

    static func fixture(_ path: String) throws -> [String: Any] {
        let data = try Data(contentsOf: sharedDir.appendingPathComponent(path))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    static func bytes(_ hex: String) -> [UInt8] {
        let clean = hex.filter { !$0.isWhitespace }
        return stride(from: 0, to: clean.count, by: 2).map {
            let start = clean.index(clean.startIndex, offsetBy: $0)
            return UInt8(clean[start..<clean.index(start, offsetBy: 2)], radix: 16)!
        }
    }

    static func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined(separator: " ")
    }

    // MARK: - Frames

    private func messageType(_ name: String) -> SonyMessageType {
        switch name {
        case "ack": return .ack
        case "command2": return .command2
        default: return .command1
        }
    }

    func testFramesEncodeAndDecode() throws {
        let fixtures = try Self.fixture("protocol-fixtures/frames.json")
        let valid = try XCTUnwrap(fixtures["valid"] as? [[String: Any]])
        XCTAssertFalse(valid.isEmpty)
        for c in valid {
            let name = c["name"] as! String
            let message = SonyMessage(
                type: messageType(c["type"] as! String),
                sequenceNumber: UInt8(c["seq"] as! Int),
                payload: Self.bytes(c["payload"] as! String)
            )
            XCTAssertEqual(Self.hex(message.encode()), c["frame"] as! String, name)
            XCTAssertEqual(SonyMessage.decode(rawFrame: Self.bytes(c["frame"] as! String)), message, name)
        }
        for c in try XCTUnwrap(fixtures["invalid"] as? [[String: Any]]) {
            XCTAssertNil(SonyMessage.decode(rawFrame: Self.bytes(c["frame"] as! String)), c["name"] as! String)
        }
        for c in try XCTUnwrap(fixtures["streams"] as? [[String: Any]]) {
            let parser = FrameParser()
            let payloads = (c["chunks"] as! [String]).flatMap { parser.feed(Self.bytes($0)) }.map { Self.hex($0.payload) }
            XCTAssertEqual(payloads, c["expectPayloads"] as! [String], c["name"] as! String)
        }
    }

    // MARK: - Events

    /// Canonical dictionary form of an event; must match the Kotlin test's `canonical()`.
    static func canonical(_ event: HeadphonesEvent?) -> NSDictionary? {
        guard let event else { return nil }
        let dict: [String: Any]
        switch event {
        case .protocolInfo(let v):
            dict = ["event": "protocolInfo", "version": v == .v1 ? "v1" : (v == .v2 ? "v2" : "unknown")]
        case .ambientSound(let s):
            dict = ["event": "ambientSound", "mode": s.mode.rawValue, "focusOnVoice": s.focusOnVoice, "level": s.level,
                    "subtype": Int(s.subtype), "isUpdate": s.isUpdate, "hasWindNoiseByte": s.hasWindNoiseByte]
        case .battery(let b):
            dict = ["event": "battery", "level": b.level, "charging": b.isCharging]
        case .equalizer(let e):
            var d: [String: Any] = ["event": "equalizer", "presetCode": Int(e.presetCode), "bands": e.bands, "subtype": Int(e.subtype)]
            if let clearBass = e.clearBass { d["clearBass"] = clearBass }
            dict = d
        case .equalizerCapability(let c):
            dict = ["event": "eqCapability", "subtype": Int(c.subtype), "bandCount": c.bandCount, "stepCount": c.stepCount,
                    "presetCodes": c.presetCodes.map(Int.init)]
        case .bgmMode(let enabled, let room):
            dict = ["event": "bgmMode", "enabled": enabled, "roomSize": ["small", "middle", "large"][Int(room.rawValue)]]
        case .upmixCinema(let enabled):
            dict = ["event": "upmixCinema", "enabled": enabled]
        case .speakToChatEnabled(let enabled):
            dict = ["event": "speakToChatEnabled", "enabled": enabled]
        case .speakToChatConfig(let c):
            var d: [String: Any] = ["event": "speakToChatConfig", "sensitivity": c.sensitivity.label.lowercased(), "timeout": c.timeout.label.lowercased()]
            if let voiceFocus = c.voiceFocus { d["voiceFocus"] = voiceFocus }
            dict = d
        case .automaticPowerOff(let m):
            dict = ["event": "automaticPowerOff", "mode": m == .off ? "off" : "whenTakenOff"]
        case .pauseWhenTakenOff(let enabled):
            dict = ["event": "pauseWhenTakenOff", "enabled": enabled]
        case .deviceList(let devices):
            dict = ["event": "deviceList", "devices": devices.map {
                ["mac": $0.macAddress, "name": $0.name, "connected": $0.isConnected, "playback": $0.isPlayback] as [String: Any]
            }]
        case .modelName(let name):
            dict = ["event": "modelName", "name": name]
        case .firmwareVersion(let version):
            dict = ["event": "firmwareVersion", "version": version]
        }
        return dict as NSDictionary
    }

    func testEventsDecodeIdenticallyToAndroid() throws {
        let cases = try XCTUnwrap(Self.fixture("protocol-fixtures/events.json")["cases"] as? [[String: Any]])
        XCTAssertGreaterThan(cases.count, 20)
        for c in cases {
            let name = c["name"] as! String
            let type: SonyMessageType = (c["messageType"] as! String) == "command2" ? .command2 : .command1
            let event = SonyEventDecoder.decode(payload: Self.bytes(c["payload"] as! String), messageType: type)
            if let expected = c["expect"] as? NSDictionary {
                XCTAssertEqual(Self.canonical(event), expected, name)
            } else {
                XCTAssertNil(event, name)
            }
        }
    }

    // MARK: - Commands

    private func build(_ name: String, _ a: [String: Any]) -> [UInt8]? {
        func int(_ key: String) -> Int { a[key] as! Int }
        func bool(_ key: String) -> Bool { a[key] as! Bool }
        switch name {
        case "init": return SonyCommands.buildInit()
        case "modelNameGet": return SonyCommands.buildModelNameGet()
        case "firmwareVersionGet": return SonyCommands.buildFirmwareVersionGet()
        case "ambientSoundGet": return SonyCommands.buildAmbientSoundGet(subtype: UInt8(int("subtype")))
        case "ambientSoundSet":
            return SonyCommands.buildAmbientSoundSet(AmbientSoundState(
                mode: AmbientSoundMode(rawValue: a["mode"] as! String)!,
                focusOnVoice: bool("focusOnVoice"),
                level: int("level"),
                subtype: UInt8(int("subtype")),
                hasWindNoiseByte: bool("hasWindNoiseByte")
            ))
        case "batteryGet": return SonyCommands.buildBatteryGet()
        case "equalizerGet": return SonyCommands.buildEqualizerGet(subtype: UInt8(int("subtype")))
        case "equalizerCapabilityGet": return SonyCommands.buildEqualizerCapabilityGet()
        case "equalizerPresetSet":
            return SonyCommands.buildEqualizerPresetSet(code: UInt8(int("code")), subtype: UInt8(int("subtype")))
        case "equalizerBandsSet":
            return SonyCommands.buildEqualizerBandsSet(bands: a["bands"] as! [Int], subtype: UInt8(int("subtype")))
        case "bgmModeSet":
            let room: BGMRoomSize = ["small": .small, "middle": .middle, "large": .large][a["roomSize"] as! String]!
            return SonyCommands.buildBGMModeSet(enabled: bool("enabled"), roomSize: room)
        case "upmixCinemaSet": return SonyCommands.buildUpmixCinemaSet(enabled: bool("enabled"))
        case "speakToChatEnabledSet": return SonyCommands.buildSpeakToChatEnabledSet(bool("enabled"))
        case "speakToChatConfigSet":
            let sensitivity = SpeakToChatSensitivity.allCases.first { $0.label.lowercased() == a["sensitivity"] as! String }!
            let timeout = SpeakToChatTimeout.allCases.first { $0.label.lowercased() == a["timeout"] as! String }!
            return SonyCommands.buildSpeakToChatConfigSet(SpeakToChatConfigState(sensitivity: sensitivity, timeout: timeout))
        case "automaticPowerOffSet":
            return SonyCommands.buildAutomaticPowerOffSet((a["mode"] as! String) == "off" ? .off : .whenTakenOff)
        case "pauseWhenTakenOffGet": return SonyCommands.buildPauseWhenTakenOffGet()
        case "pauseWhenTakenOffSet": return SonyCommands.buildPauseWhenTakenOffSet(bool("enabled"))
        case "deviceListGet": return SonyCommands.buildDeviceListGet()
        case "sourceSwitchSet": return SonyCommands.buildSourceSwitchSet(macAddress: a["mac"] as! String)
        default:
            XCTFail("fixture command not mapped: \(name)")
            return nil
        }
    }

    func testCommandsEncodeIdenticallyToAndroid() throws {
        let cases = try XCTUnwrap(Self.fixture("protocol-fixtures/commands.json")["cases"] as? [[String: Any]])
        for c in cases {
            let name = c["command"] as! String
            let payload = build(name, c["args"] as! [String: Any])
            XCTAssertEqual(payload.map(Self.hex), c["payload"] as? String, "\(name) \(c["args"]!)")
        }
    }
}
