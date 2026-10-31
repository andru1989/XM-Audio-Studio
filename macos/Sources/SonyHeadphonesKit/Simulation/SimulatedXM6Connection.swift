import Foundation

/// A WH-1000XM6 emulated at the byte level, for demo mode. Mirrors Android's
/// `SimulatedXm6Channel`: it answers with the documented payload layouts, including the
/// firmware 3.x quirks (zero-update 0x17 placeholder, real state on 0x19, classic EQ query
/// ignored). Everything it reports is invented; the UI labels demo mode as a simulation.
final class SimulatedXM6Connection: HeadphonesConnection {
    var onEvent: ((RFCOMMConnectionEvent) -> Void)?

    private var isOpen = false
    private let parser = FrameParser()
    private var deviceSeq: UInt8 = 0
    private let latency: TimeInterval

    private var ancOn = true
    private var ambient = false
    private var focusOnVoice = false
    private var ambientLevel: UInt8 = 12
    private var battery: UInt8 = 72
    private var eqPreset: UInt8 = 0xa0
    private var eqBands: [Int] = [1, 1, 0, 0, 0, 0, 0, 1, 1, 0]
    private var bgmEnabled = false
    private var bgmRoom: UInt8 = 0x01
    private var cinemaEnabled = false
    private var speakToChat = false
    private var stcSensitivity: UInt8 = 0
    private var stcTimeout: UInt8 = 1
    private var autoPowerOff: UInt8 = 0x11
    private var pauseWhenTakenOff = true
    private var playbackIndex = 0
    /// 0x00 sound quality, 0x01 stable connection (0x02 low latency is advertised, not writable).
    private(set) var connectionPriority: UInt8 = 0x00
    /// LDAC.
    private var audioCodec: UInt8 = 0x10
    private let devices = [("AA:BB:CC:DD:EE:01", "MacBook Pro"), ("AA:BB:CC:DD:EE:02", "Pixel")]
    /// PLAYBACK_CONTROLLER status of the source: 0x01 playing, 0x02 paused, 0x03 stopped.
    private(set) var playbackState: UInt8 = 0x01
    private var trackIndex = 0
    /// Invented track names for demo mode (same as Android's simulator).
    private let tracks = [
        ("Amanecer en la ciudad", "Demostración", "XM Audio Studio"),
        ("Ruido blanco", "Demostración", "XM Audio Studio"),
        ("Café de madrugada", "Demostración", "XM Audio Studio"),
    ]
    /// Result byte of `3d 01` for the next switch (0x00 success, 0x02 refused during a call, ...).
    var sourceSwitchResult: UInt8 = 0x00
    var playbackDeviceIndex: Int { playbackIndex }

    init(latency: TimeInterval = 0.02) {
        self.latency = latency
    }

    static let pairedDevice = PairedDeviceInfo(id: "00-00-00-00-de-40", name: "WH-1000XM6 (demo)")
    /// The demo Mac is the first entry of the simulated multipoint list.
    static let localAddress = "aa-bb-cc-dd-ee-01"

    /// Another device takes the playback right on its own, as when the phone starts playing.
    func simulateSourceTakeover(index: Int, notify: Bool, playbackState: UInt8? = nil) {
        if let playbackState { self.playbackState = playbackState }
        playbackIndex = index
        if notify { emitDevice(.command2, deviceList(0x39)) }
    }

    private func emitDevice(_ type: SonyMessageType, _ payload: [UInt8]) {
        emit(SonyMessage(type: type, sequenceNumber: deviceSeq, payload: payload))
        deviceSeq ^= 0x01
    }

    private func playbackReport(_ opcode: UInt8) -> [UInt8] {
        [opcode, 0x01, 0x00, playbackState, 0x00]
    }

    private func trackReport(_ opcode: UInt8) -> [UInt8] {
        let track = tracks[trackIndex % tracks.count]
        var out: [UInt8] = [opcode, 0x01]
        for name in [track.0, track.1, track.2, ""] {
            let utf8 = Array(name.utf8)
            out += [name.isEmpty ? 0x01 : 0x02, UInt8(utf8.count)] + utf8
        }
        return out
    }

    func connect(toDeviceAddress address: String) {
        parser.reset()
        after { [weak self] in
            self?.isOpen = true
            self?.onEvent?(.opened)
        }
    }

    func disconnect() {
        isOpen = false
    }

    func write(_ bytes: [UInt8]) {
        guard isOpen else { return }
        for message in parser.feed(bytes) where message.type != .ack {
            emit(SonyMessage(type: .ack, sequenceNumber: message.sequenceNumber ^ 0x01, payload: []))
            if let reply = respond(message.type, message.payload) {
                emit(SonyMessage(type: message.type, sequenceNumber: deviceSeq, payload: reply))
                deviceSeq ^= 0x01
            }
        }
    }

    private func after(_ block: @escaping () -> Void) {
        DispatchQueue.main.asyncAfter(deadline: .now() + latency, execute: block)
    }

    private func emit(_ message: SonyMessage) {
        let frame = message.encode()
        after { [weak self] in
            guard let self, self.isOpen else { return }
            self.onEvent?(.dataReceived(frame))
        }
    }

    private func ambientReport(_ opcode: UInt8) -> [UInt8] {
        [opcode, 0x19, 0x01, ancOn ? 1 : 0, ambient ? 1 : 0, focusOnVoice ? 1 : 0, ambientLevel, 0, 0]
    }

    private func eqReport(_ opcode: UInt8) -> [UInt8] {
        [opcode, 0x04, eqPreset, UInt8(eqBands.count)] + eqBands.map { UInt8($0 + 6) }
    }

    private func deviceList(_ opcode: UInt8) -> [UInt8] {
        var out: [UInt8] = [opcode, 0x02, UInt8(devices.count)]
        for (i, d) in devices.enumerated() {
            out += Array(d.0.utf8) + [i == playbackIndex ? 0x02 : 0x01, 0, 0, 0, UInt8(d.1.utf8.count)] + Array(d.1.utf8)
        }
        return out + [0x02]
    }

    private func respond(_ type: SonyMessageType, _ p: [UInt8]) -> [UInt8]? {
        guard p.count >= 2 else { return p.first == 0x00 ? [0x01, 0x00, 0x03, 0, 0, 0, 0, 0] : nil }
        if type == .command2 {
            if p[0] == 0x36 && p[1] == 0x02 { return deviceList(0x37) }
            if p[0] == 0x3c && p[1] == 0x01 && p.count == 19 {
                let mac = String(decoding: p[2..<19], as: UTF8.self)
                let index = devices.firstIndex(where: { $0.0 == mac })
                let result: UInt8 = index == nil ? 0x03 : sourceSwitchResult
                if result == 0x00, let index { playbackIndex = index }
                emitDevice(type, [0x3d, 0x01, result] + Array(p[2..<19]))
                return result == 0x00 ? deviceList(0x39) : nil
            }
            return nil
        }
        switch (p[0], p[1]) {
        case (0x00, _): return [0x01, 0x00, 0x03, 0, 0, 0, 0, 0]
        case (0x22, _): return [0x23, 0x00, battery, 0]
        case (0x66, 0x17): return [0x67, 0x17, 0, 0, 0, 0, 0, 0] // firmware 3.x placeholder
        case (0x66, 0x19): return ambientReport(0x67)
        case (0x68, _):
            ancOn = p[3] == 1
            ambient = p[4] == 1
            focusOnVoice = p[p.count - 2] == 1
            ambientLevel = p[p.count - 1]
            return ambientReport(0x69)
        case (0x56, 0x04): return eqReport(0x57)
        case (0x58, _):
            eqPreset = p[2]
            if p[2] == 0xa0, p.count >= 4 + Int(p[3]) { eqBands = p[4..<(4 + Int(p[3]))].map { Int($0) - 6 } }
            return eqReport(0x59)
        case (0x12, 0x02): return [0x13, 0x02, audioCodec]
        case (0xa2, 0x01): return playbackReport(0xa3)
        case (0xa6, 0x01): return trackReport(0xa7)
        case (0xa4, 0x01):
            guard p.count == 4, p[2] == 0x00 else { return nil }
            switch p[3] {
            case 0x01: playbackState = 0x02
            case 0x07: playbackState = 0x01
            case 0x02: trackIndex += 1
            case 0x03: trackIndex = max(0, trackIndex - 1)
            default: break
            }
            if p[3] == 0x02 || p[3] == 0x03 { emitDevice(type, trackReport(0xa9)) }
            return playbackReport(0xa5)
        // Capability as captured on an XM6 fw 3.1.5: quality, stable, low latency; no exclusive list.
        case (0xe0, 0x05): return [0xe1, 0x05, 0x03, 0x00, 0x01, 0x02, 0x00]
        case (0xe6, 0x05): return [0xe7, 0x05, connectionPriority]
        case (0xe8, 0x05):
            // Like the real XM6, only `e8 05 <00|01> 01` is taken; anything else is ignored.
            guard p.count == 4, p[3] == 0x01, p[2] <= 0x01 else { return nil }
            connectionPriority = p[2]
            return [0xe9, 0x05, connectionPriority, 0x02] // stream: classic
        case (0xe6, 0x09): return [0xe7, 0x09, bgmEnabled ? 0 : 1, bgmRoom]
        case (0xe6, 0x04): return [0xe7, 0x04, cinemaEnabled ? 0 : 1]
        case (0xe8, 0x09):
            bgmEnabled = p[2] == 0
            bgmRoom = p[3]
            return [0xe9, 0x09, p[2], bgmRoom]
        case (0xe8, 0x04):
            cinemaEnabled = p[2] == 0
            return [0xe9, 0x04, p[2]]
        case (0xf6, 0x0c): return [0xf7, 0x0c, speakToChat ? 0 : 1, 1]
        case (0xf6, 0x01): return [0xf7, 0x01, pauseWhenTakenOff ? 0 : 1]
        case (0xf8, 0x0c):
            speakToChat = p[2] == 0
            return [0xf9, 0x0c, p[2], 1]
        case (0xf8, 0x01):
            pauseWhenTakenOff = p[2] == 0
            return [0xf9, 0x01, p[2]]
        case (0xfa, 0x0c): return [0xfb, 0x0c, stcSensitivity, stcTimeout]
        case (0xfc, _):
            stcSensitivity = p[2]
            stcTimeout = p[3]
            return [0xfd, 0x0c, stcSensitivity, stcTimeout]
        case (0x26, 0x05): return [0x27, 0x05, autoPowerOff, 0]
        case (0x28, _):
            // XM6 only accepts 0x10/0x11; anything else is re-announced unchanged.
            if p[2] == 0x10 || p[2] == 0x11 { autoPowerOff = p[2] }
            return [0x29, 0x05, autoPowerOff, 0]
        default:
            return nil
        }
    }
}

extension HeadphonesController {
    /// A controller wired to the byte-level XM6 simulator instead of IOBluetooth.
    public static func demo() -> HeadphonesController {
        HeadphonesController(connection: SimulatedXM6Connection(), pairedDevices: { [SimulatedXM6Connection.pairedDevice] },
                             localAddress: { SimulatedXM6Connection.localAddress })
    }
}
