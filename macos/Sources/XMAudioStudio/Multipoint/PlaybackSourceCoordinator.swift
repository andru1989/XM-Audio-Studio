import Foundation
import Combine
import SonyHeadphonesKit

/// Puts together where the headphones say the audio is, whether this Mac is sending audio, and the
/// "follow playback" preference, and acts on `PlaybackSourceAdvisor` (docs/decisions/ADR-0010).
///
/// - A change in local audio re-reads the source at once instead of waiting for the next poll.
/// - With following on (the default): when this Mac starts playing, it claims the audio (the
///   newest device to start wins, ADR-0011), connecting if the channel was released; when
///   connected it also moves the controls here if the source is idle, and gives back music the
///   headphones cut off while switching. At most one automatic action every 15 s.
@MainActor
final class PlaybackSourceCoordinator: ObservableObject {
    @Published private(set) var advice: SourceAdvice?

    let monitor = LocalAudioMonitor()
    private let controller: HeadphonesController
    private let settings: AppSettings
    private var interruptedSince: Date?
    private var lastAutoSwitch: Date?
    /// The local playback start that was already claimed (one claim per start).
    private var claimedStart: Date?
    private var ticker: Timer?
    private var cancellables: Set<AnyCancellable> = []

    init(controller: HeadphonesController, settings: AppSettings) {
        self.controller = controller
        self.settings = settings

        controller.$deviceAddress
            .combineLatest(controller.$deviceName)
            .sink { [weak self, weak controller] address, name in
                guard let self else { return }
                if address != nil {
                    self.monitor.track(address: address, name: name)
                } else if let paired = controller?.pairedXM6() {
                    // Not connected yet this launch (menu bar only, channel released): watch the
                    // paired XM6 anyway, so starting to play here can still claim the audio.
                    self.monitor.track(address: paired.id, name: paired.name)
                }
            }
            .store(in: &cancellables)

        monitor.$isSendingAudio
            .removeDuplicates()
            .dropFirst()
            .sink { [weak self] _ in
                // Something started or stopped here: ask the headphones now.
                self?.controller.refreshPlaybackSource()
            }
            .store(in: &cancellables)

        settings.$followPlayback
            .combineLatest(settings.$releaseWhenIdle)
            .sink { [weak controller] follow, release in
                // Holding the channel anyway: keep watching with no window open, so a handover can be undone.
                controller?.watchesPlaybackInBackground = follow && !release
            }
            .store(in: &cancellables)

        controller.$playbackSource.map { _ in () }
            .merge(with: monitor.$sendingSince.map { _ in () }, settings.$followPlayback.map { _ in () })
            .receive(on: RunLoop.main)
            .sink { [weak self] in self?.evaluate() }
            .store(in: &cancellables)
    }

    private func evaluate(now: Date = Date()) {
        let state = controller.playbackSource
        if state.interruptedPlayback == nil {
            interruptedSince = nil
        } else if interruptedSince == nil {
            interruptedSince = now
        }

        let advice = PlaybackSourceAdvisor.advise(
            state,
            thisDevice: controller.thisDevice,
            localPlayingSince: monitor.sendingSince,
            now: now,
            followPlayback: settings.followPlayback,
            lastAutoSwitch: lastAutoSwitch,
            interruptedSince: interruptedSince
        )
        if advice != self.advice { self.advice = advice }

        if PlaybackSourceAdvisor.shouldClaim(followPlayback: settings.followPlayback, localPlayingSince: monitor.sendingSince,
                                             now: now, claimedStart: claimedStart, lastAutoSwitch: lastAutoSwitch) {
            // Resolved against the list read after connecting: writes nothing if this Mac already has it.
            claimedStart = monitor.sendingSince
            lastAutoSwitch = now
            controller.claimPlaybackHere()
        } else if advice.switchHereNow, let here = advice.thisDevice {
            lastAutoSwitch = now
            controller.switchPlayback(to: here)
        } else if advice.recoverNow, let interrupted = advice.interrupted {
            lastAutoSwitch = now
            controller.switchPlayback(toAddress: interrupted.fromMac, resume: true)
        }
        updateTicker()
    }

    /// The advisor's settle times need a clock only while something is pending.
    private func updateTicker() {
        let needed = (monitor.sendingSince != nil && claimedStart != monitor.sendingSince) || interruptedSince != nil
            || (monitor.sendingSince != nil && controller.connectionState == .connected)
        if needed, ticker == nil {
            ticker = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
                Task { @MainActor in self?.evaluate() }
            }
        } else if !needed {
            ticker?.invalidate()
            ticker = nil
        }
    }
}
