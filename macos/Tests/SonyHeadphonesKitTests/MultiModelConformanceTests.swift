import XCTest
@testable import SonyHeadphonesKit

/// Runs `shared/protocol-fixtures/{events,commands}-v1.json` (MDR v1: XM2-XM4) and `-xm5.json`
/// (WH-1000XM5), the same files as `android/core/.../MultiModelConformanceTest.kt`. These codecs
/// are not wired into `HeadphonesController` (macOS drives only the XM6); this is codec parity.
final class MultiModelConformanceTests: XCTestCase {

    private typealias Decode = ([UInt8], SonyMessageType) -> HeadphonesEvent?

    private func runEvents(_ file: String, _ decode: Decode) throws {
        let cases = try XCTUnwrap(ConformanceTests.fixture(file)["cases"] as? [[String: Any]])
        XCTAssertGreaterThanOrEqual(cases.count, 15, file)
        for c in cases {
            let name = "\(file): \(c["name"] as! String)"
            let type: SonyMessageType = (c["messageType"] as! String) == "command2" ? .command2 : .command1
            let event = decode(ConformanceTests.bytes(c["payload"] as! String), type)
            if let expected = c["expect"] as? NSDictionary {
                XCTAssertEqual(ConformanceTests.canonical(event), expected, name)
            } else {
                XCTAssertNil(event, name)
            }
        }
    }

    private func runCommands(_ file: String, _ build: (String, [String: Any]) -> [UInt8]?) throws {
        let cases = try XCTUnwrap(ConformanceTests.fixture(file)["cases"] as? [[String: Any]])
        for c in cases {
            let name = c["command"] as! String
            let payload = build(name, c["args"] as! [String: Any])
            XCTAssertEqual(payload.map(ConformanceTests.hex), c["payload"] as? String, "\(file): \(name) \(c["args"]!)")
            if file.contains("v1"), let first = payload?.first {
                XCTAssertNotEqual(first, MdrV1Commands.Op.powerOff, "v1 must never build opcode 0x22 (power off): \(name)")
            }
        }
    }

    private func mode(_ a: [String: Any]) -> AmbientSoundMode { AmbientSoundMode(rawValue: a["mode"] as! String)! }

    private func config(_ a: [String: Any]) -> SpeakToChatConfigState {
        SpeakToChatConfigState(
            sensitivity: SpeakToChatSensitivity.allCases.first { $0.label.lowercased() == a["sensitivity"] as! String }!,
            timeout: SpeakToChatTimeout.allCases.first { $0.label.lowercased() == a["timeout"] as! String }!,
            voiceFocus: a["voiceFocus"] as? Bool
        )
    }

    func testV1EventsDecodeIdenticallyToAndroid() throws {
        try runEvents("protocol-fixtures/events-v1.json") { MdrV1EventDecoder.decode(payload: $0, messageType: $1) }
    }

    func testV1CommandsEncodeIdenticallyToAndroid() throws {
        try runCommands("protocol-fixtures/commands-v1.json") { name, a in
            switch name {
            case "init": return SonyCommands.buildInit()
            case "modelNameGet": return SonyCommands.buildModelNameGet()
            case "firmwareVersionGet": return SonyCommands.buildFirmwareVersionGet()
            case "batteryGet": return MdrV1Commands.buildBatteryGet()
            case "ambientSoundGet": return MdrV1Commands.buildAmbientSoundGet()
            case "ambientSoundSet":
                return MdrV1Commands.buildAmbientSoundSet(mode: mode(a), focusOnVoice: a["focusOnVoice"] as! Bool, level: a["level"] as! Int)
            case "equalizerGet": return MdrV1Commands.buildEqualizerGet()
            case "equalizerCapabilityGet": return MdrV1Commands.buildEqualizerCapabilityGet()
            case "equalizerPresetSet": return MdrV1Commands.buildEqualizerPresetSet(code: UInt8(a["code"] as! Int))
            case "equalizerBandsSet":
                return MdrV1Commands.buildEqualizerBandsSet(bands: a["bands"] as! [Int], clearBass: a["clearBass"] as! Int)
            case "speakToChatEnabledGet": return MdrV1Commands.buildSpeakToChatEnabledGet()
            case "speakToChatEnabledSet": return MdrV1Commands.buildSpeakToChatEnabledSet(a["enabled"] as! Bool)
            case "speakToChatConfigGet": return MdrV1Commands.buildSpeakToChatConfigGet()
            case "speakToChatConfigSet": return MdrV1Commands.buildSpeakToChatConfigSet(config(a))
            default:
                XCTFail("v1 fixture command not mapped: \(name)")
                return nil
            }
        }
    }

    func testXm5EventsDecodeIdenticallyToAndroid() throws {
        try runEvents("protocol-fixtures/events-xm5.json") { Xm5EventDecoder.decode(payload: $0, messageType: $1) }
    }

    func testXm5CommandsEncodeIdenticallyToAndroid() throws {
        try runCommands("protocol-fixtures/commands-xm5.json") { name, a in
            switch name {
            case "ambientSoundGet": return Xm5Commands.buildAmbientSoundGet()
            case "ambientSoundSet":
                return Xm5Commands.buildAmbientSoundSet(mode: mode(a), focusOnVoice: a["focusOnVoice"] as! Bool, level: a["level"] as! Int)
            case "batteryGet": return Xm5Commands.buildBatteryGet()
            case "equalizerGet": return Xm5Commands.buildEqualizerGet()
            case "equalizerCapabilityGet": return Xm5Commands.buildEqualizerCapabilityGet()
            case "equalizerPresetSet": return Xm5Commands.buildEqualizerPresetSet(code: UInt8(a["code"] as! Int))
            case "equalizerBandsSet":
                return Xm5Commands.buildEqualizerBandsSet(bands: a["bands"] as! [Int], clearBass: a["clearBass"] as! Int)
            case "speakToChatEnabledGet": return Xm5Commands.buildSpeakToChatEnabledGet()
            case "speakToChatEnabledSet": return Xm5Commands.buildSpeakToChatEnabledSet(a["enabled"] as! Bool)
            case "speakToChatConfigGet": return Xm5Commands.buildSpeakToChatConfigGet()
            case "speakToChatConfigSet": return Xm5Commands.buildSpeakToChatConfigSet(config(a))
            default:
                XCTFail("xm5 fixture command not mapped: \(name)")
                return nil
            }
        }
    }

    func testEachDecoderOnlyAcceptsItsOwnFamily() {
        let v1Battery: [UInt8] = [0x11, 0x00, 0x64, 0x00]
        let v2Battery: [UInt8] = [0x23, 0x00, 0x64, 0x00]
        XCTAssertNil(MdrV1EventDecoder.decode(payload: v2Battery, messageType: .command1))
        XCTAssertNil(Xm5EventDecoder.decode(payload: v1Battery, messageType: .command1))
        XCTAssertNil(SonyEventDecoder.decode(payload: v1Battery, messageType: .command1))
    }
}
