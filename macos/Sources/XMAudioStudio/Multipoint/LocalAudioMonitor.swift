import Foundation
import CoreAudio
import SonyHeadphonesKit

/// Whether this Mac is playing audio to the headphones right now, from CoreAudio.
///
/// The headphones' output device is found by its Bluetooth address in the device UID (macOS
/// names Bluetooth outputs `AA-BB-CC-DD-EE-FF:output`), else by its name. While that device is
/// running (`kAudioDevicePropertyDeviceIsRunningSomewhere`), the CoreAudio process objects say
/// which apps have output streams on it; system sounds, notifications, speech and processes
/// without a bundle are ignored, so a "ding" never counts as "this Mac started playing". The
/// stream can still be silent (a paused video that keeps the output open). Purely local: nothing
/// is sent to the headphones from here.
@MainActor
final class LocalAudioMonitor: ObservableObject {
    @Published private(set) var isSendingAudio = false
    /// When the current stream started; nil when not sending.
    @Published private(set) var sendingSince: Date?
    /// The headphones are a CoreAudio output on this Mac (audio connected here, not only control).
    @Published private(set) var outputFound = false

    private var target: (address: String?, name: String?) = (nil, nil)
    private var deviceID = AudioObjectID(kAudioObjectUnknown)
    private var runningListener: AudioObjectPropertyListenerBlock?
    private var devicesListener: AudioObjectPropertyListenerBlock?

    private static var runningAddress = AudioObjectPropertyAddress(
        mSelector: kAudioDevicePropertyDeviceIsRunningSomewhere,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)
    private static var devicesAddress = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDevices,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain)

    init() {
        let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
            Task { @MainActor in self?.resolve() }
        }
        devicesListener = listener
        AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &Self.devicesAddress, .main, listener)
    }

    /// Follows the headphones the controller talks to; call again when that changes.
    func track(address: String?, name: String?) {
        guard target.address != address || target.name != name else { return }
        target = (address, name)
        resolve()
    }

    private func resolve() {
        let found = Self.findOutput(address: target.address, name: target.name)
        if found != deviceID {
            detachRunningListener()
            deviceID = found
            if found != kAudioObjectUnknown {
                let listener: AudioObjectPropertyListenerBlock = { [weak self] _, _ in
                    Task { @MainActor in self?.readRunning() }
                }
                runningListener = listener
                AudioObjectAddPropertyListenerBlock(found, &Self.runningAddress, .main, listener)
            }
        }
        outputFound = deviceID != kAudioObjectUnknown
        readRunning()
    }

    private var processPoll: Timer?

    private func readRunning() {
        var running: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        let deviceRunning = deviceID != kAudioObjectUnknown
            && AudioObjectGetPropertyData(deviceID, &Self.runningAddress, 0, nil, &size, &running) == noErr
            && running != 0
        // Which apps are behind the stream changes without the device stopping (a ding while music
        // plays, music starting while a call app holds the output): look again every second.
        if deviceRunning, processPoll == nil {
            processPoll = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.readRunning() }
            }
        } else if !deviceRunning {
            processPoll?.invalidate()
            processPoll = nil
        }
        let sending = deviceRunning && Self.mediaIsPlaying(on: deviceID)
        guard sending != isSendingAudio else { return }
        isSendingAudio = sending
        sendingSince = sending ? Date() : nil
    }

    /// Bundle IDs whose output is never "the user started playing" (prefix match).
    private static let ignoredBundlePrefixes = [
        "com.apple.audio.SystemSoundServer", "com.apple.systemsound", "com.apple.NotificationCenter",
        "com.apple.UserNotificationCenter", "com.apple.notificationcenterui", "com.apple.coreaudiod",
        "com.apple.speech", "com.apple.VoiceOver", "com.apple.accessibility", "com.apple.Siri",
        "com.apple.assistant", "com.apple.CoreSpeech", "com.apple.loginwindow", "com.apple.controlcenter",
    ]

    /// Some app other than ours, and not a system sound, has an output stream on `device`.
    private static func mediaIsPlaying(on device: AudioObjectID) -> Bool {
        let ownPID = ProcessInfo.processInfo.processIdentifier
        for process in processObjects() {
            guard uint32(process, kAudioProcessPropertyIsRunningOutput) == 1,
                  int32(process, kAudioProcessPropertyPID) != ownPID,
                  outputDevices(of: process).contains(device),
                  let bundle = string(process, kAudioProcessPropertyBundleID), !bundle.isEmpty,
                  !ignoredBundlePrefixes.contains(where: { bundle.hasPrefix($0) }) else { continue }
            return true
        }
        return false
    }

    private static func processObjects() -> [AudioObjectID] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyProcessObjectList,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        let system = AudioObjectID(kAudioObjectSystemObject)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(system, &address, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(system, &address, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func outputDevices(of process: AudioObjectID) -> [AudioObjectID] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioProcessPropertyDevices,
                                                 mScope: kAudioObjectPropertyScopeOutput,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(process, &address, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(process, &address, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func uint32(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> UInt32? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var value: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        return AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr ? value : nil
    }

    private static func int32(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> Int32? {
        uint32(id, selector).map { Int32(bitPattern: $0) }
    }

    private func detachRunningListener() {
        if let runningListener, deviceID != kAudioObjectUnknown {
            AudioObjectRemovePropertyListenerBlock(deviceID, &Self.runningAddress, .main, runningListener)
        }
        runningListener = nil
    }

    // MARK: - CoreAudio lookups

    private static func findOutput(address: String?, name: String?) -> AudioObjectID {
        let wanted = Multipoint.normalizeAddress(address)
        var candidatesByName: [AudioObjectID] = []
        for id in allDevices() where hasOutput(id) && isBluetooth(id) {
            if let wanted, let uid = string(id, kAudioDevicePropertyDeviceUID),
               uid.uppercased().filter(\.isHexDigit).contains(wanted) {
                return id
            }
            if let name, let deviceName = string(id, kAudioObjectPropertyName),
               deviceName.caseInsensitiveCompare(name) == .orderedSame {
                candidatesByName.append(id)
            }
        }
        return candidatesByName.count == 1 ? candidatesByName[0] : AudioObjectID(kAudioObjectUnknown)
    }

    private static func allDevices() -> [AudioObjectID] {
        var size: UInt32 = 0
        let system = AudioObjectID(kAudioObjectSystemObject)
        guard AudioObjectGetPropertyDataSize(system, &devicesAddress, 0, nil, &size) == noErr, size > 0 else { return [] }
        var ids = [AudioObjectID](repeating: 0, count: Int(size) / MemoryLayout<AudioObjectID>.size)
        guard AudioObjectGetPropertyData(system, &devicesAddress, 0, nil, &size, &ids) == noErr else { return [] }
        return ids
    }

    private static func hasOutput(_ id: AudioObjectID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreams,
                                                 mScope: kAudioObjectPropertyScopeOutput,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(id, &address, 0, nil, &size) == noErr && size > 0
    }

    private static func isBluetooth(_ id: AudioObjectID) -> Bool {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyTransportType,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var transport: UInt32 = 0
        var size = UInt32(MemoryLayout<UInt32>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &transport) == noErr else { return false }
        return transport == kAudioDeviceTransportTypeBluetooth || transport == kAudioDeviceTransportTypeBluetoothLE
    }

    private static func string(_ id: AudioObjectID, _ selector: AudioObjectPropertySelector) -> String? {
        var address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(id, &address, 0, nil, &size, &value) == noErr, let value else { return nil }
        return value.takeRetainedValue() as String
    }
}
