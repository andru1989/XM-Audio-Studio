import Foundation
import CoreAudio

/// Tells the UI when output devices appear or disappear or the system output changes,
/// so the device picker stays current. Listeners run on a private queue, never the
/// main queue (registering them on the main queue can deadlock against a teardown
/// that runs there, ParaEQ gotcha 1); `onChange` is debounced and called on the main
/// thread.
@MainActor
public final class AudioDeviceWatcher {
    public var onChange: (() -> Void)?

    private let queue = DispatchQueue(label: "io.github.andru1989.xmaudiostudio.dsp.devices")
    private var installed: [(address: AudioObjectPropertyAddress, block: AudioObjectPropertyListenerBlock)] = []
    private var generation = 0

    public init() {
        for selector in [kAudioHardwarePropertyDevices, kAudioHardwarePropertyDefaultOutputDevice] {
            let address = AudioObjectPropertyAddress(mSelector: selector, mScope: kAudioObjectPropertyScopeGlobal,
                                                     mElement: kAudioObjectPropertyElementMain)
            let block = Self.block { [weak self] in self?.schedule() }
            var a = address
            if AudioObjectAddPropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &a, queue, block) == noErr {
                installed.append((address, block))
            }
        }
    }

    public func invalidate() {
        for entry in installed {
            var a = entry.address
            AudioObjectRemovePropertyListenerBlock(AudioObjectID(kAudioObjectSystemObject), &a, queue, entry.block)
        }
        installed = []
        onChange = nil
    }

    private func schedule() {
        generation += 1
        let current = generation
        Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 300_000_000)
            guard let self, current == self.generation else { return }
            self.onChange?()
        }
    }

    private nonisolated static func block(_ action: @escaping @MainActor () -> Void) -> AudioObjectPropertyListenerBlock {
        return { _, _ in Task { @MainActor in action() } }
    }
}
