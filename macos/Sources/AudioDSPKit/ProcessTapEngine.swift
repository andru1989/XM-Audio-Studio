// Engine lifecycle modelled on ParaEQ `Sources/AudioEngine.swift` and
// `Prototypes/TapProto/main.swift` (tap + private aggregate + single IOProc, listener
// queue, start watchdog), https://github.com/wabsto1/ParaEQ at 51d6f6d.
//
// MIT License
//
// Copyright (c) 2026 wabsto1
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.
//
// Differences from ParaEQ: by default the tap only takes the audio apps send to the
// *selected* output device (`CATapDescription(excludingProcesses:deviceUID:stream:)`)
// instead of every app's audio; the engine never follows the system default on its
// own; persistence, presets and UI state live in the app, not here.

import Foundation
import CoreAudio

/// What the tap captures.
public enum DSPTapScope: String, Sendable, CaseIterable {
    /// Only audio that apps send to the selected output. Audio going to any other
    /// device is never touched. Default.
    case selectedDevice
    /// ParaEQ's design: every app's audio, mixed to stereo, whatever device it was
    /// heading for. Only runs while the selected output is the system default output,
    /// otherwise it would reroute other devices' audio. Fallback for troubleshooting.
    case systemWide
}

public struct DSPRunningInfo: Equatable, Sendable {
    public var deviceName: String
    public var deviceUID: String
    public var isBluetooth: Bool
    public var sampleRate: Double
    public var channelCount: Int
    public var latencyFrames: Int

    public var latencyMs: Double { Double(latencyFrames) / sampleRate * 1000 }
}

public enum DSPWaitReason: Equatable, Sendable {
    /// The selected output is not connected; processing resumes when it returns.
    case deviceUnavailable(uid: String)
    /// `.systemWide` only: the selected output is not the system output.
    case notSystemOutput(deviceName: String)
}

public enum DSPEngineError: Error, Equatable, Sendable {
    /// `AudioHardwareCreateProcessTap` failed; usually the "System Audio Recording"
    /// permission was denied, or the app is not signed with the audio-input entitlement.
    case tapCreationFailed(OSStatus)
    case unsupportedFormat(String)
    case coreAudio(step: String, status: OSStatus)
    /// The aggregate never called the IOProc, even after one restart.
    case stalled
}

public enum DSPEngineState: Equatable, Sendable {
    case off
    case running(DSPRunningInfo)
    case waiting(DSPWaitReason)
    case failed(DSPEngineError)
}

/// Runs a `DSPProcessor` on what this Mac plays to one output device, using a Core
/// Audio process tap (macOS 14.2+) and a private aggregate device whose main
/// sub-device is that output. No virtual driver; the system default output is never
/// read-modified-written; our own process is excluded from the tap (no feedback);
/// everything is torn down on `stop()`, and coreaudiod tears it down if we crash, so
/// audio falls back to unprocessed.
///
/// All methods must be called on the main thread. Core Audio property listeners run
/// on a private queue that is never blocked (ParaEQ gotcha 1) and hop to the main
/// thread; the IOProc runs on its own serial queue (required on macOS 26).
@MainActor
public final class ProcessTapEngine {
    public let scope: DSPTapScope
    public private(set) var state: DSPEngineState = .off {
        didSet { if state != oldValue { onStateChange?(state) } }
    }
    public var onStateChange: ((DSPEngineState) -> Void)?
    /// Diagnostics hook (start/stop/route changes); never called from the IO thread.
    public var log: ((String) -> Void)?

    public private(set) var settings = DSPSettings.flat
    /// The output the user asked for; nil while off.
    public private(set) var outputUID: String?

    private var context: TapIOContext?
    private var unmanagedContext: Unmanaged<TapIOContext>?
    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?
    private var runningDevice: AudioOutputDevice?
    private var runningSampleRate = 0.0
    private var runningChannels = 0
    private var activity: NSObjectProtocol?

    private let ioQueue = DispatchQueue(label: "io.github.andru1989.xmaudiostudio.dsp.io", qos: .userInteractive)
    private let listenerQueue = DispatchQueue(label: "io.github.andru1989.xmaudiostudio.dsp.listeners")
    private var listeners: [(object: AudioObjectID, address: AudioObjectPropertyAddress, block: AudioObjectPropertyListenerBlock)] = []
    private var deviceListeners: [(object: AudioObjectID, address: AudioObjectPropertyAddress, block: AudioObjectPropertyListenerBlock)] = []
    private var routeGeneration = 0
    private var watchdogGeneration = 0
    private var watchdogRetried = false

    public init(scope: DSPTapScope = .selectedDevice) {
        self.scope = scope
    }

    // MARK: - Public API

    /// Starts processing what is played to `outputUID`. If the device is not connected
    /// the engine waits for it. Calling it again with another device switches over.
    public func start(outputUID: String, settings: DSPSettings) {
        self.settings = settings.sanitized()
        if self.outputUID == outputUID, case .running = state {
            context?.processor.apply(self.settings)
            return
        }
        teardown()
        self.outputUID = outputUID
        watchdogRetried = false
        installSystemListeners()
        startIfPossible()
    }

    /// Stops processing and removes the tap and the aggregate. Audio goes back to
    /// the apps' normal, unprocessed path.
    public func stop() {
        outputUID = nil
        removeSystemListeners()
        teardown()
        state = .off
    }

    /// Band, preamp or bypass changes; applied glitch-free while running.
    public func update(settings: DSPSettings) {
        self.settings = settings.sanitized()
        context?.processor.apply(self.settings)
    }

    /// The coefficients actually running (nil while not running).
    public var runningPlan: DSPCoefficientPlan? { context?.processor.plan }

    /// IOProc callbacks since the last (re)start.
    public var callbackCount: UInt64 { context?.callbacks.load(ordering: .relaxed) ?? 0 }

    /// Largest input |sample| since the previous call; nil while not running.
    public func takeInputPeak() -> Float? {
        guard let context else { return nil }
        return Float(bitPattern: context.inputPeakBits.exchange(0, ordering: .relaxed))
    }

    // MARK: - Start / teardown

    private func startIfPossible() {
        guard let uid = outputUID else { return }
        guard let device = AudioOutputDevices.device(uid: uid) else {
            log?("DSP: output \(uid) not available; waiting")
            state = .waiting(.deviceUnavailable(uid: uid))
            return
        }
        if scope == .systemWide, AudioOutputDevices.defaultOutputDevice()?.uid != uid {
            state = .waiting(.notSystemOutput(deviceName: device.name))
            return
        }
        do {
            try build(device)
            let info = DSPRunningInfo(deviceName: device.name, deviceUID: device.uid, isBluetooth: device.isBluetooth,
                                      sampleRate: runningSampleRate, channelCount: runningChannels,
                                      latencyFrames: context?.processor.latencyFrames ?? 0)
            log?(String(format: "DSP: running on %@ (%@), %.0f Hz, %d ch, tap #%u, aggregate #%u",
                        device.name, scope.rawValue, runningSampleRate, runningChannels, tapID, aggregateID))
            state = .running(info)
            scheduleWatchdog()
        } catch {
            teardown()
            let engineError = error as? DSPEngineError ?? .coreAudio(step: "start", status: -1)
            log?("DSP: start failed: \(engineError)")
            outputUID = nil
            removeSystemListeners()
            state = .failed(engineError)
        }
    }

    private func build(_ device: AudioOutputDevice) throws {
        // 1. Our own process object, excluded so our output is never captured again.
        var pid = ProcessInfo.processInfo.processIdentifier
        var selfObject = AudioObjectID(kAudioObjectUnknown)
        var address = AudioObjectPropertyAddress(mSelector: kAudioHardwarePropertyTranslatePIDToProcessObject,
                                                 mScope: kAudioObjectPropertyScopeGlobal,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        try check(AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address,
                                             UInt32(MemoryLayout<pid_t>.size), &pid, &size, &selfObject), "translate PID")
        let exclude: [AudioObjectID] = selfObject == kAudioObjectUnknown ? [] : [selfObject]

        // 2. The tap. Never touch `isExclusive` after init (ParaEQ gotcha 6).
        let description: CATapDescription
        switch scope {
        case .selectedDevice:
            description = CATapDescription(excludingProcesses: exclude, deviceUID: device.uid, stream: 0)
        case .systemWide:
            description = CATapDescription(stereoGlobalTapButExcludeProcesses: exclude)
        }
        description.uuid = UUID()
        description.name = "XM Audio Studio DSP"
        description.isPrivate = true
        description.muteBehavior = .mutedWhenTapped
        let tapStatus = AudioHardwareCreateProcessTap(description, &tapID)
        guard tapStatus == noErr else {
            tapID = AudioObjectID(kAudioObjectUnknown)
            throw DSPEngineError.tapCreationFailed(tapStatus)
        }

        // 3. Its format decides the processor's rate and channel count.
        var format = AudioStreamBasicDescription()
        address = AudioObjectPropertyAddress(mSelector: kAudioTapPropertyFormat, mScope: kAudioObjectPropertyScopeGlobal,
                                             mElement: kAudioObjectPropertyElementMain)
        size = UInt32(MemoryLayout<AudioStreamBasicDescription>.size)
        try check(AudioObjectGetPropertyData(tapID, &address, 0, nil, &size, &format), "read tap format")
        let channels = Int(format.mChannelsPerFrame)
        guard format.mFormatID == kAudioFormatLinearPCM, format.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              format.mBitsPerChannel == 32 else {
            throw DSPEngineError.unsupportedFormat("tap format \(format.mFormatID) flags \(format.mFormatFlags) \(format.mBitsPerChannel) bit")
        }
        guard let processor = DSPProcessor(settings: settings, sampleRate: format.mSampleRate, channelCount: channels) else {
            throw DSPEngineError.unsupportedFormat("\(channels) channels at \(format.mSampleRate) Hz")
        }

        // 4. Private aggregate: the real output MUST be the main sub-device (a tap-only
        //    aggregate silently produces zeros, ParaEQ gotcha 6).
        let aggregate: [String: Any] = [
            kAudioAggregateDeviceNameKey: "XM Audio Studio DSP",
            kAudioAggregateDeviceUIDKey: AudioOutputDevices.ownAggregatePrefix + UUID().uuidString,
            kAudioAggregateDeviceMainSubDeviceKey: device.uid,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: device.uid]],
            kAudioAggregateDeviceTapListKey: [[
                kAudioSubTapUIDKey: description.uuid.uuidString,
                kAudioSubTapDriftCompensationKey: true,
            ]],
        ]
        try check(AudioHardwareCreateAggregateDevice(aggregate as CFDictionary, &aggregateID), "create aggregate")

        // 5. Input layout: the output device's own input streams (if any) come first,
        //    then the tap. Refuse anything else rather than play the wrong signal.
        let inputLayout = Self.inputBufferChannels(aggregateID)
        let skip = device.inputStreams
        guard inputLayout.count == skip + 1, inputLayout[skip] == channels else {
            throw DSPEngineError.unsupportedFormat("aggregate input layout \(inputLayout), expected \(skip) device stream(s) + \(channels)-channel tap")
        }

        // 6. One IOProc on a dedicated queue (a nil queue registers nothing on macOS 26).
        let context = TapIOContext(processor: processor, skipInputBuffers: skip)
        let unmanaged = Unmanaged.passRetained(context)
        self.context = context
        unmanagedContext = unmanaged
        try check(AudioDeviceCreateIOProcIDWithBlock(&procID, aggregateID, ioQueue, Self.ioBlock(unmanaged)), "create IOProc")
        if skip > 0, let procID {
            // Best effort: tell the HAL we never read the device's own inputs (a
            // Bluetooth headset microphone could otherwise switch it to HFP).
            let status = Self.disableInputStreams(aggregateID, procID: procID, count: skip)
            if status != noErr { log?("DSP: could not disable device input streams (\(status))") }
        }
        try check(AudioDeviceStart(aggregateID, procID), "start IO")

        runningDevice = device
        runningSampleRate = format.mSampleRate
        runningChannels = channels
        installDeviceListeners(device)
        activity = ProcessInfo.processInfo.beginActivity(
            options: [.userInitiatedAllowingIdleSystemSleep, .latencyCritical],
            reason: "XM Audio Studio local DSP")
    }

    private func teardown() {
        removeDeviceListeners()
        if aggregateID != kAudioObjectUnknown {
            if let procID {
                AudioDeviceStop(aggregateID, procID)
                AudioDeviceDestroyIOProcID(aggregateID, procID)
            }
            AudioHardwareDestroyAggregateDevice(aggregateID)
        }
        procID = nil
        aggregateID = AudioObjectID(kAudioObjectUnknown)
        if tapID != kAudioObjectUnknown {
            AudioHardwareDestroyProcessTap(tapID)
            tapID = AudioObjectID(kAudioObjectUnknown)
        }
        if let unmanaged = unmanagedContext {
            ioQueue.sync {}   // let an in-flight IOProc block finish before releasing its context
            unmanaged.release()
        }
        unmanagedContext = nil
        context = nil
        runningDevice = nil
        runningSampleRate = 0
        runningChannels = 0
        watchdogGeneration += 1
        if let activity { ProcessInfo.processInfo.endActivity(activity) }
        activity = nil
    }

    private func restart(reason: String) {
        guard outputUID != nil else { return }
        log?("DSP: restarting (\(reason))")
        teardown()
        startIfPossible()
    }

    // MARK: - Watchdog (ParaEQ gotcha 8: an aggregate that never calls the IOProc)

    private func scheduleWatchdog() {
        watchdogGeneration += 1
        let generation = watchdogGeneration
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 5_000_000_000)
            guard let self, generation == self.watchdogGeneration, case .running = self.state else { return }
            if self.callbackCount > 0 {
                self.watchdogRetried = false
                return
            }
            if self.watchdogRetried {
                self.log?("DSP: still no IO callbacks after a restart; giving up")
                self.teardown()
                self.outputUID = nil
                self.removeSystemListeners()
                self.state = .failed(.stalled)
            } else {
                self.watchdogRetried = true
                self.restart(reason: "no IO callbacks 5 s after start")
            }
        }
    }

    // MARK: - Device and route changes

    /// System-wide: device list and default output. Debounced; our own aggregate also
    /// fires `kAudioHardwarePropertyDevices` (ParaEQ gotcha 2), so only a real change of
    /// the selected device acts.
    private func installSystemListeners() {
        guard listeners.isEmpty else { return }
        for selector in [kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultOutputDevice] {
            let address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                     mElement: kAudioObjectPropertyElementMain)
            let block = Self.listenerBlock { [weak self] in self?.scheduleRouteCheck() }
            var a = address
            if AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &a, listenerQueue, block) == noErr {
                listeners.append((AudioObjectID(kAudioObjectSystemObject), address, block))
            }
        }
    }

    private func removeSystemListeners() {
        for l in listeners {
            var a = l.address
            AudioObjectRemovePropertyListenerBlock(l.object, &a, listenerQueue, l.block)
        }
        listeners = []
    }

    /// Per run: the aggregate's sample rate and the output's stream layout. A change
    /// (e.g. a Bluetooth headset dropping to HFP) rebuilds everything at the new format.
    private func installDeviceListeners(_ device: AudioOutputDevice) {
        let watched: [(AudioObjectID, AudioObjectPropertySelector, AudioObjectPropertyScope)] = [
            (aggregateID, kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal),
            (device.id, kAudioDevicePropertyNominalSampleRate, kAudioObjectPropertyScopeGlobal),
            (device.id, kAudioDevicePropertyStreamConfiguration, kAudioObjectPropertyScopeOutput),
            (device.id, kAudioDevicePropertyDeviceIsAlive, kAudioObjectPropertyScopeGlobal),
        ]
        for (object, selector, scope) in watched {
            let address = AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
            let block = Self.listenerBlock { [weak self] in self?.scheduleFormatCheck() }
            var a = address
            if AudioObjectAddPropertyListenerBlock(object, &a, listenerQueue, block) == noErr {
                deviceListeners.append((object, address, block))
            }
        }
    }

    private func removeDeviceListeners() {
        for l in deviceListeners {
            var a = l.address
            AudioObjectRemovePropertyListenerBlock(l.object, &a, listenerQueue, l.block)
        }
        deviceListeners = []
    }

    private func scheduleRouteCheck() {
        routeGeneration += 1
        let generation = routeGeneration
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard let self, generation == self.routeGeneration else { return }
            self.evaluateRoute()
        }
    }

    private func scheduleFormatCheck() {
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            self?.evaluateFormat()
        }
    }

    private func evaluateRoute() {
        guard let uid = outputUID else { return }
        let device = AudioOutputDevices.device(uid: uid)
        switch state {
        case .running:
            if device == nil {
                log?("DSP: \(runningDevice?.name ?? uid) disconnected; pausing")
                teardown()
                state = .waiting(.deviceUnavailable(uid: uid))
            } else if device?.id != runningDevice?.id {
                restart(reason: "output device re-enumerated")
            } else if scope == .systemWide, AudioOutputDevices.defaultOutputDevice()?.uid != uid {
                teardown()
                state = .waiting(.notSystemOutput(deviceName: device?.name ?? uid))
            }
        case .waiting:
            if device != nil { startIfPossible() }
        case .off, .failed:
            break
        }
    }

    private func evaluateFormat() {
        guard case .running = state, let device = runningDevice else { return }
        guard let current = AudioOutputDevices.device(uid: device.uid) else {
            evaluateRoute()
            return
        }
        let rate = AudioOutputDevices.nominalSampleRate(aggregateID) ?? AudioOutputDevices.nominalSampleRate(current.id) ?? runningSampleRate
        if abs(rate - runningSampleRate) > 0.5 || current.outputChannels != device.outputChannels || current.inputStreams != device.inputStreams {
            restart(reason: String(format: "format changed: %.0f Hz → %.0f Hz, %d → %d output channels",
                                   runningSampleRate, rate, device.outputChannels, current.outputChannels))
        }
    }

    // MARK: - Helpers (nonisolated: these closures run on Core Audio's queues)

    private nonisolated static func ioBlock(_ context: Unmanaged<TapIOContext>) -> AudioDeviceIOBlock {
        return { _, input, _, output, _ in
            context._withUnsafeGuaranteedRef { $0.render(input: input, output: output) }
        }
    }

    private nonisolated static func listenerBlock(_ action: @escaping @MainActor () -> Void) -> AudioObjectPropertyListenerBlock {
        return { _, _ in
            Task { @MainActor in action() }
        }
    }

    private func check(_ status: OSStatus, _ step: String) throws {
        guard status == noErr else { throw DSPEngineError.coreAudio(step: step, status: status) }
    }

    /// Channel count of each input buffer of a device, in IOProc order.
    static func inputBufferChannels(_ device: AudioObjectID) -> [Int] {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyStreamConfiguration,
                                                 mScope: kAudioObjectPropertyScopeInput,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size) == noErr, size > 0 else { return [] }
        let bytes = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { bytes.deallocate() }
        guard AudioObjectGetPropertyData(device, &address, 0, nil, &size, bytes) == noErr else { return [] }
        return UnsafeMutableAudioBufferListPointer(bytes.assumingMemoryBound(to: AudioBufferList.self)).map { Int($0.mNumberChannels) }
    }

    /// Marks the first `count` input streams as unused by our IOProc.
    static func disableInputStreams(_ device: AudioObjectID, procID: AudioDeviceIOProcID, count: Int) -> OSStatus {
        var address = AudioObjectPropertyAddress(mSelector: kAudioDevicePropertyIOProcStreamUsage,
                                                 mScope: kAudioObjectPropertyScopeInput,
                                                 mElement: kAudioObjectPropertyElementMain)
        var size: UInt32 = 0
        var status = AudioObjectGetPropertyDataSize(device, &address, 0, nil, &size)
        guard status == noErr, Int(size) >= MemoryLayout<AudioHardwareIOProcStreamUsage>.size else { return status }
        let bytes = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: 16)
        defer { bytes.deallocate() }
        memset(bytes, 0, Int(size))
        let usage = bytes.assumingMemoryBound(to: AudioHardwareIOProcStreamUsage.self)
        usage.pointee.mIOProc = unsafeBitCast(procID, to: UnsafeMutableRawPointer.self)
        status = AudioObjectGetPropertyData(device, &address, 0, nil, &size, bytes)
        guard status == noErr else { return status }
        let streams = Int(usage.pointee.mNumberStreams)
        guard let offset = MemoryLayout<AudioHardwareIOProcStreamUsage>.offset(of: \.mStreamIsOn) else { return -1 }
        let flags = (bytes + offset).assumingMemoryBound(to: UInt32.self)
        for i in 0..<min(count, streams) { flags[i] = 0 }
        return AudioObjectSetPropertyData(device, &address, 0, nil, size, bytes)
    }
}
