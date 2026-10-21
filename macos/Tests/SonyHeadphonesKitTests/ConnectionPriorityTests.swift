import XCTest
@testable import SonyHeadphonesKit

/// Connection priority (AUDIO type 0x05, live XM6 fw 3.1.5 layouts from Anneo22/xm6-control)
/// and the codec indicator (`12 02`, mos9527 reference). Mirrors Android's ConnectionPriorityTest.
final class ConnectionPriorityTests: XCTestCase {
    // Captured on an XM6 fw 3.1.5, independent of the builders.
    private let capability: [UInt8] = [0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00]

    private func decode(_ payload: [UInt8], _ type: SonyMessageType = .command1) -> HeadphonesEvent? {
        SonyEventDecoder.decode(payload: payload, messageType: type)
    }

    func testDecodesTheCapturedCapability() {
        XCTAssertEqual(SonyCommands.decodeConnectionPriorityCapability(capability), [.soundQuality, .stableConnection, .lowLatency])
        guard case .connectionPriorityCapability(let priorities) = decode(capability) else {
            return XCTFail("captured capability did not produce its event")
        }
        XCTAssertEqual(priorities, [.soundQuality, .stableConnection, .lowLatency])
        // A valid empty list means "none offered", not invented defaults.
        XCTAssertEqual(SonyCommands.decodeConnectionPriorityCapability([0xe1, 0x05, 0x00, 0x00]), [])
        XCTAssertEqual(SonyCommands.decodeConnectionPriorityCapability([0xe1, 0x05, 0x01, 0x01, 0x00]), [.stableConnection])
    }

    func testRejectsEveryMalformedCapability() {
        for length in 0..<capability.count {
            XCTAssertNil(SonyCommands.decodeConnectionPriorityCapability(Array(capability.prefix(length))), "truncated to \(length)")
        }
        let invalid: [[UInt8]] = [
            capability + [0x00], // trailing byte
            [0xe1, 0x05, 0x04, 0x00, 0x01, 0x02, 0x00], // wrong count
            [0xe1, 0x05, 0x02, 0x00, 0x01, 0x02, 0x00], // wrong count (too small)
            [0xe1, 0x05, 0x03, 0x00, 0x01, 0x03, 0x00], // unknown mode
            [0xe1, 0x05, 0x03, 0x00, 0x01, 0x01, 0x00], // duplicate mode
            [0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x01], // exclusive count without its list
            [0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x01, 0x00], // non-empty exclusive list
            [0xe1, 0x04, 0x03, 0x00, 0x01, 0x02, 0x00], // other inquired type
            [0xe0, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00], // request opcode
        ]
        for payload in invalid {
            XCTAssertNil(SonyCommands.decodeConnectionPriorityCapability(payload), "\(payload)")
            XCTAssertNil(decode(payload), "\(payload)")
        }
        XCTAssertNil(decode(capability, .command2))
    }

    func testDecodesRepliesAndNotifications() {
        for priority in ConnectionPriority.allCases {
            let reply = SonyCommands.decodeConnectionPriority([0xe7, 0x05, priority.rawValue])
            XCTAssertEqual(reply?.priority, priority)
            XCTAssertNil(reply?.stream ?? nil)
            guard case .connectionPriority(let decoded, let noStream) = decode([0xe7, 0x05, priority.rawValue]) else {
                return XCTFail("reply did not decode")
            }
            XCTAssertEqual(decoded, priority)
            XCTAssertNil(noStream)
            for stream in AudioStreamType.allCases {
                let payload: [UInt8] = [0xe9, 0x05, priority.rawValue, stream.rawValue]
                let notify = SonyCommands.decodeConnectionPriority(payload)
                XCTAssertEqual(notify?.priority, priority)
                XCTAssertEqual(notify?.stream, stream)
                guard case .connectionPriority(let p, let s) = decode(payload) else { return XCTFail("notify did not decode") }
                XCTAssertEqual(p, priority)
                XCTAssertEqual(s, stream)
            }
        }
        // Live captures: stable and quality notifications with no stream switch.
        XCTAssertEqual(SonyCommands.decodeConnectionPriority([0xe9, 0x05, 0x01, 0x00])?.priority, .stableConnection)
        XCTAssertEqual(SonyCommands.decodeConnectionPriority([0xe9, 0x05, 0x00, 0x00])?.stream, .unspecified)
    }

    func testRejectsEveryMalformedReport() {
        let invalid: [[UInt8]] = [
            [0xe7], [0xe7, 0x05],
            [0xe7, 0x05, 0x00, 0x00], // reply with a stream byte
            [0xe7, 0x05, 0x03], // unknown mode
            [0xe7, 0x06, 0x00], // other inquired type
            [0xe9, 0x05, 0x01], // notify without stream
            [0xe9, 0x05, 0x01, 0x03], // unknown stream
            [0xe9, 0x05, 0x03, 0x00], // unknown mode
            [0xe9, 0x05, 0x01, 0x00, 0x00], // too long
            [0xe8, 0x05, 0x00], // our own write
            [0xe3, 0x05, 0x00, 0x00], // other opcode
        ]
        for payload in invalid {
            XCTAssertNil(SonyCommands.decodeConnectionPriority(payload), "\(payload)")
            XCTAssertNil(decode(payload), "\(payload)")
        }
        XCTAssertNil(decode([0xe7, 0x05, 0x00], .command2))
        // BGM / cinema still decode on the same opcodes.
        guard case .bgmMode = decode([0xe7, 0x09, 0x01, 0x01]) else { return XCTFail("BGM no longer decodes") }
        guard case .upmixCinema = decode([0xe9, 0x04, 0x00]) else { return XCTFail("cinema no longer decodes") }
    }

    func testBuildsQueriesAndOnlyTheLiveVerifiedWrites() {
        XCTAssertEqual(SonyCommands.buildConnectionPriorityCapabilityGet(), [0xe0, 0x05])
        XCTAssertEqual(SonyCommands.buildConnectionPriorityGet(), [0xe6, 0x05])
        XCTAssertEqual(SonyCommands.buildConnectionPrioritySet(.soundQuality), [0xe8, 0x05, 0x00, 0x01])
        XCTAssertEqual(SonyCommands.buildConnectionPrioritySet(.stableConnection), [0xe8, 0x05, 0x01, 0x01])
        // Advertised by the XM6 but rejected live: never built.
        XCTAssertNil(SonyCommands.buildConnectionPrioritySet(.lowLatency))
        XCTAssertEqual(ConnectionPriority.allCases.filter(\.isWritable), [.soundQuality, .stableConnection])
        XCTAssertEqual(SonyCommands.buildAudioCodecGet(), [0x12, 0x02])
    }

    func testDecodesCodecsAndKeepsUnknownBytes() {
        let expected: [(UInt8, AudioCodec, String)] = [
            (0x01, .sbc, "SBC"), (0x02, .aac, "AAC"), (0x10, .ldac, "LDAC"),
            (0x20, .aptX, "aptX"), (0x21, .aptXHD, "aptX HD"), (0x30, .lc3, "LC3"),
        ]
        for (code, codec, name) in expected {
            for opcode: UInt8 in [0x13, 0x15] {
                guard case .audioCodec(let decoded) = decode([opcode, 0x02, code]) else { return XCTFail("codec \(code)") }
                XCTAssertEqual(decoded, codec)
                XCTAssertEqual(decoded.code, code)
                XCTAssertEqual(decoded.displayName, name)
            }
        }
        XCTAssertEqual(SonyCommands.decodeAudioCodec([0x13, 0x02, 0x99]), .unknown(0x99))
        XCTAssertNil(AudioCodec.unknown(0x99).displayName)
        for payload: [UInt8] in [[0x13, 0x02], [0x13, 0x02, 0x10, 0x00], [0x13, 0x01, 0x10], [0x14, 0x02, 0x10], [0x12, 0x02, 0x10]] {
            XCTAssertNil(SonyCommands.decodeAudioCodec(payload), "\(payload)")
            XCTAssertNil(decode(payload), "\(payload)")
        }
        XCTAssertNil(decode([0x13, 0x02, 0x10], .command2))
    }
}
