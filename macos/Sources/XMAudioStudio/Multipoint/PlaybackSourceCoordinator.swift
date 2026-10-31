import Foundation
import Combine
import SonyHeadphonesKit

/// Puts together where the headphones say the audio is, whether this Mac is sending audio, and the
/// "follow playback" preference, and acts on `PlaybackSourceAdvisor` (docs/decisions/ADR-0010).
///
/// - A change in local audio re-reads the source at once instead of waiting for the next poll.
/// - With following on: moves the controls here when this Mac plays and the source is idle, and
///   gives the music back (and resumes it) when the headphones handed the right away from a device
///   that was playing. At most one automatic switch every 15 s.
@MainActor
final class PlaybackSourceCoordinator: ObservableObject {
    @Published private(set) var advice: SourceAdvice?

    let monitor = LocalAudioMonitor()
    private let controller: HeadphonesController
    private let settings: AppSettings
    private var interruptedSince: Date?
    private var lastAutoSwitch: Date?
    private var lastAcquireForFollow: Date?
    private var ticker: Timer?
    private var cancellables: Set<AnyCancellable> = []

    init(controller: HeadphonesController, settings: AppSettings) {
        self.controller = controller
        self.settings = settings

        controller.$deviceAddress
            .combineLatest(controller.$deviceName)
            .sink { [weak self] address, name in self?.monitor.track(address: address, name: name) }
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

        if advice.switchHereNow, let here = advice.thisDevice {
            lastAutoSwitch = now
            controller.switchPlayback(to: here)
        } else if advice.recoverNow, let interrupted = advice.interrupted {
            lastAutoSwitch = now
            controller.switchPlayback(toAddress: interrupted.fromMac, resume: true)
        }
        acquireIfFollowingNeedsIt(now: now)
        updateTicker()
    }

    /// Following, this Mac has played for a moment, and the channel was released: connect once to
    /// read where the controls are. Never more than once a minute.
    private func acquireIfFollowingNeedsIt(now: Date) {
        guard settings.followPlayback, controller.connectionState != .connected,
              let since = monitor.sendingSince, now.timeIntervalSince(since) >= PlaybackSourceAdvisor.settle,
              lastAcquireForFollow.map({ now.timeIntervalSince($0) >= 60 }) ?? true,
              controller.deviceAddress != nil else { return }
        lastAcquireForFollow = now
        controller.refreshState()
    }

    /// The advisor's settle times need a clock only while something is pending.
    private func updateTicker() {
        let needed = monitor.sendingSince != nil || interruptedSince != nil
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
