import Foundation

/// Bluetooth connection priority (AUDIO family, inquired type 0x05), as the official app
/// labels it. A preference, not a report of the active codec. Layout verified live on an
/// XM6 (fw 3.1.5) by Anneo22/xm6-control. Mirrors Android's `ConnectionPriority`.
public enum ConnectionPriority: UInt8, CaseIterable, Sendable, Identifiable {
    case soundQuality = 0x00
    case stableConnection = 0x01
    case lowLatency = 0x02

    public var id: UInt8 { rawValue }

    /// Only these two writes were accepted live. The XM6 advertises low latency but ignores
    /// a write of it, so it is shown read-only when reported and never sent.
    public var isWritable: Bool { self != .lowLatency }

    public var label: String {
        switch self {
        case .soundQuality: return "Prioritize Sound Quality"
        case .stableConnection: return "Prioritize Stable Connection"
        case .lowLatency: return "Prioritize Low Latency"
        }
    }
}

/// The `switchingStream` byte of a connection-priority notification (`e9 05 <mode> <stream>`).
public enum AudioStreamType: UInt8, CaseIterable, Sendable {
    /// No stream switch reported.
    case unspecified = 0x00
    case leAudio = 0x01
    case classic = 0x02
}

/// Codec byte of `13 02 <codec>` (mos9527 reference tables; not captured on an XM6 yet).
/// A byte outside the table is kept as `.unknown` and shown as such, never guessed.
public enum AudioCodec: Equatable, Hashable, Sendable {
    case sbc, aac, ldac, aptX, aptXHD, lc3
    case unknown(UInt8)

    public init(code: UInt8) {
        switch code {
        case 0x01: self = .sbc
        case 0x02: self = .aac
        case 0x10: self = .ldac
        case 0x20: self = .aptX
        case 0x21: self = .aptXHD
        case 0x30: self = .lc3
        default: self = .unknown(code)
        }
    }

    public var code: UInt8 {
        switch self {
        case .sbc: return 0x01
        case .aac: return 0x02
        case .ldac: return 0x10
        case .aptX: return 0x20
        case .aptXHD: return 0x21
        case .lc3: return 0x30
        case .unknown(let code): return code
        }
    }

    /// Product name; nil for a byte outside the reference table.
    public var displayName: String? {
        switch self {
        case .sbc: return "SBC"
        case .aac: return "AAC"
        case .ldac: return "LDAC"
        case .aptX: return "aptX"
        case .aptXHD: return "aptX HD"
        case .lc3: return "LC3"
        case .unknown: return nil
        }
    }
}
