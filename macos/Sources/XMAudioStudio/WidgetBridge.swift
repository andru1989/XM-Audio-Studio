import Foundation
import AppKit
import Combine
import OSLog
import WidgetKit
import SonyHeadphonesKit
import WidgetBridgeKit

/// The app's half of the WidgetKit integration (docs/macos/WIDGETS.md).
///
/// - Publishes a small snapshot of the headphones' state into the App Group container
///   and asks WidgetKit to reload (throttled), keeping the last known values when the
///   control channel is released.
/// - Takes the commands widget buttons drop into the mailbox (woken by a Darwin
///   notification) and runs them through `HeadphonesController`. A command never builds
///   on stale state: if the channel is closed, the bridge has the controller acquire it,
///   waits until the headphones have reported the state the write is based on, then
///   applies it. Nothing is dropped because the channel happened to be released.
@MainActor
final class WidgetBridge: ObservableObject {
    struct Timing {
        /// Mailbox entries older than this are not applied (the tap was long ago).
        var commandMaxAge: TimeInterval = 60
        /// How long a command may wait for the connection and the state read.
        var commandWaitLimit: TimeInterval = 30
        /// After connecting, how long to wait for state a command depends on before
        /// giving up on it (or, for optional state, going ahead without it).
        var stateGrace: TimeInterval = 8
        var publishDebounce: TimeInterval = 0.15
        var minReloadInterval: TimeInterval = 1
        var pumpInterval: TimeInterval = 0.5
    }

    /// Whether this build can talk to widgets: signed with the App Group entitlement.
    /// `swift build` and `Scripts/build_app.sh` binaries aren't, and run without the bridge.
    static var widgetsAvailable: Bool { AppGroup.processHasEntitlement() }

    private static let log = Logger(subsystem: "io.github.andru1989.xmaudiostudio", category: "widget")

    private let controller: HeadphonesController
    private let store: WidgetStore?
    private let isDemo: Bool
    private let profileName: () -> String?
    private let reloadWidgets: () -> Void
    private let timing: Timing

    /// Last known values; survives the controller clearing its state on release.
    private(set) var current: WidgetSnapshot
    private var lastWritten: WidgetSnapshot?
    private(set) var pending: [WidgetCommand] = []
    private(set) var lastOutcome: CommandOutcome?
    private var waitDeadline: Date?
    private var acquireRequested = false
    private var connectedSince: Date?
    private var started = false

    private var cancellables = Set<AnyCancellable>()
    private var commandObserver: DarwinNotificationObserver?
    private var publishTask: Task<Void, Never>?
    private var reloadTask: Task<Void, Never>?
    private var pumpTask: Task<Void, Never>?
    private var lastReload = Date.distantPast
    /// Keeps App Nap away while a widget tap is being handled (see `holdActivity`).
    private var activity: NSObjectProtocol?
    private var activityEndTask: Task<Void, Never>?

    init(
        controller: HeadphonesController,
        store: WidgetStore? = WidgetStore.appGroup(),
        isDemo: Bool = false,
        timing: Timing = Timing(),
        profileName: @escaping () -> String? = { nil },
        reloadWidgets: @escaping () -> Void = { WidgetCenter.shared.reloadAllTimelines() }
    ) {
        self.controller = controller
        self.store = store
        self.isDemo = isDemo
        self.timing = timing
        self.profileName = profileName
        self.reloadWidgets = reloadWidgets
        // Start from what the previous run left (last known values, never "live"), unless
        // it came from the other mode: simulated values must not pass for real ones.
        var initial = store?.readSnapshot() ?? WidgetSnapshot()
        if initial.isDemo != isDemo { initial = WidgetSnapshot() }
        initial.link = .offline
        initial.pendingActions = []
        initial.isDemo = isDemo
        current = initial
    }

    var isEnabled: Bool { store != nil }

    func start() {
        guard let store, !started else { return }
        started = true
        Self.log.info("Widget bridge started (\(store.directory.path, privacy: .public))")

        controller.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.controllerDidChange() } }
            .store(in: &cancellables)

        commandObserver = DarwinNotificationObserver(name: store.notificationName) { [weak self] in
            MainActor.assumeIsolated { self?.takeCommands() }
        }

        let center = NotificationCenter.default
        // A Darwin notification posted while the app was launching can be missed.
        center.publisher(for: NSApplication.didBecomeActiveNotification)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.takeCommands() } }
            .store(in: &cancellables)
        center.publisher(for: NSApplication.willTerminateNotification)
            .sink { [weak self] _ in MainActor.assumeIsolated { self?.appWillTerminate() } }
            .store(in: &cancellables)

        // Commands a widget left while the app wasn't running (it launches the app).
        takeCommands()
        publishNow()
    }

    // MARK: - Commands

    func takeCommands() {
        guard let store else { return }
        let (fresh, expired) = store.takeCommands(maxAge: timing.commandMaxAge)
        for command in expired {
            record(command, .expired)
        }
        submit(fresh)
        if !expired.isEmpty && fresh.isEmpty { schedulePublish() }
    }

    func submit(_ commands: [WidgetCommand]) {
        guard !commands.isEmpty else { return }
        holdActivity()
        for command in commands {
            Self.log.info("Widget command \(String(describing: command.action), privacy: .public)")
            // Only the latest tap per kind matters (NC then Ambient = Ambient).
            pending.removeAll { $0.action.kind == command.action.kind }
            pending.append(command)
        }
        if waitDeadline == nil {
            waitDeadline = Date().addingTimeInterval(timing.commandWaitLimit)
        }
        pump()
        startPumpingIfNeeded()
        publishNow()
    }

    /// Applies whatever pending commands the controller's state allows.
    private func pump() {
        guard !pending.isEmpty else { return }
        if let deadline = waitDeadline, Date() >= deadline {
            failAll(.timedOut)
            return
        }
        switch controller.connectionState {
        case .connected:
            if connectedSince == nil { connectedSince = Date() }
            var waiting: [WidgetCommand] = []
            for command in pending {
                switch readiness(of: command.action) {
                case .ready: record(command, execute(command.action))
                case .failed(let result): record(command, result)
                case .wait: waiting.append(command)
                }
            }
            pending = waiting
        case .connecting, .initializing, .searching:
            connectedSince = nil
        case .disconnected, .failed:
            connectedSince = nil
            if !acquireRequested {
                acquireRequested = true
                acquire()
                switch controller.connectionState {
                case .disconnected, .failed: failAll(.unreachable) // e.g. no paired headset
                default: break
                }
            } else {
                // The attempt this bridge started ended without a usable connection.
                let anotherDevice = controller.lastError?.hasPrefix("Another device") ?? false
                failAll(anotherDevice ? .anotherDevice : .unreachable)
            }
        }
        if pending.isEmpty { finishWaiting() }
    }

    /// Opens the control channel the same way the app's own controls do.
    private func acquire() {
        if controller.releaseWhenIdle {
            // Counts as user activity: connects (to the last headset used) and re-arms the
            // idle timer, so the channel is released again once the command is done.
            controller.refreshState()
        } else {
            controller.autoConnect()
        }
    }

    private enum Readiness { case ready, wait, failed(CommandOutcome.Result) }

    /// Writes build on the state the headphones reported after this connection (never on
    /// defaults or values from an earlier session: AGENTS.md rule 3).
    private func readiness(of action: WidgetAction) -> Readiness {
        let graceOver = connectedSince.map { Date().timeIntervalSince($0) >= timing.stateGrace } ?? false
        switch action {
        case .setNoiseMode:
            if controller.ambientSound != nil { return .ready }
            return graceOver || controller.initialStateTimedOut ? .failed(.stateUnavailable) : .wait
        case .applyEqPreset:
            guard controller.equalizer != nil else {
                return graceOver ? .failed(.stateUnavailable) : .wait
            }
            if let mode = controller.listeningMode {
                return mode == .standard ? .ready : .failed(.blockedByListeningMode)
            }
            // The listening mode is only needed to warn about BGM/Cinema; don't block on it.
            return graceOver ? .ready : .wait
        }
    }

    private func execute(_ action: WidgetAction) -> CommandOutcome.Result {
        switch action {
        case .setNoiseMode(let mode):
            guard var state = controller.ambientSound else { return .stateUnavailable }
            let target = AmbientSoundMode(mode)
            guard state.mode != target else { return .alreadySet }
            state.mode = target
            controller.setAmbientSound(state)
            return .sent
        case .applyEqPreset(let value):
            // Only codes the connected model's catalogue lists (never another family's table).
            let model = HeadphoneModel.from(bluetoothName: controller.deviceName) ?? .wh1000xm6
            guard let preset = EqualizerPreset(rawValue: UInt8(value.code)),
                  DeviceCatalog.capabilities(model).equalizer?.presets.contains(where: { $0.code == preset.rawValue }) == true
            else { return .unsupported }
            guard controller.equalizer?.presetCode != preset.rawValue else { return .alreadySet }
            controller.setEqualizerPreset(preset)
            return .sent
        }
    }

    private func record(_ command: WidgetCommand, _ result: CommandOutcome.Result) {
        Self.log.info("Widget command \(String(describing: command.action), privacy: .public): \(result.rawValue, privacy: .public)")
        lastOutcome = CommandOutcome(commandID: command.id, action: command.action, result: result)
    }

    private func failAll(_ result: CommandOutcome.Result) {
        for command in pending { record(command, result) }
        pending.removeAll()
        finishWaiting()
    }

    private func finishWaiting() {
        waitDeadline = nil
        acquireRequested = false
        connectedSince = nil
        pumpTask?.cancel()
        pumpTask = nil
        schedulePublish()
        releaseActivitySoon()
    }

    /// A widget tap usually reaches an app with no visible window, which macOS puts in App
    /// Nap: timers are coalesced by seconds, so connect/ACK timeouts, the EQ debounce and
    /// the snapshot debounce all stall (observed with the demo transport, whose replies are
    /// timer-driven). The tap is user-initiated work, so say so until it's done.
    private func holdActivity() {
        activityEndTask?.cancel()
        activityEndTask = nil
        guard activity == nil else { return }
        activity = ProcessInfo.processInfo.beginActivity(options: .userInitiated, reason: "Applying a widget command")
    }

    /// Keeps the activity a little longer so the headphones' reply to the write and the
    /// final snapshot/reload go out on time too.
    private func releaseActivitySoon() {
        guard activity != nil, activityEndTask == nil else { return }
        let delay = UInt64((timing.minReloadInterval + 2) * 1_000_000_000)
        activityEndTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard let self, !Task.isCancelled else { return }
            self.activityEndTask = nil
            guard self.pending.isEmpty else { return }
            self.publishNow()
            if let activity = self.activity { ProcessInfo.processInfo.endActivity(activity) }
            self.activity = nil
        }
    }

    /// Re-checks pending commands on a timer as well, so grace periods and the deadline
    /// expire even when the controller goes quiet.
    private func startPumpingIfNeeded() {
        guard pumpTask == nil, !pending.isEmpty else { return }
        let interval = UInt64(timing.pumpInterval * 1_000_000_000)
        pumpTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval)
                guard let self, !Task.isCancelled else { return }
                self.pump()
                if self.pending.isEmpty { return }
            }
        }
    }

    private func controllerDidChange() {
        pump()
        schedulePublish()
    }

    // MARK: - Snapshot

    func makeSnapshot(now: Date = Date()) -> WidgetSnapshot {
        var snapshot = current
        if let name = controller.deviceName {
            snapshot.deviceName = name
            snapshot.modelID = HeadphoneModel.from(bluetoothName: name)?.rawValue ?? snapshot.modelID
        }
        if let battery = controller.battery {
            snapshot.batteryLevel = battery.level
            snapshot.isCharging = battery.isCharging
        }
        if let ambient = controller.ambientSound {
            snapshot.noiseMode = NoiseMode(ambient.mode)
            snapshot.ambientLevel = ambient.level
        }
        if let mode = controller.listeningMode {
            snapshot.listeningMode = ListeningModeValue(mode)
        }
        if let equalizer = controller.equalizer {
            snapshot.eqPresetCode = Int(equalizer.presetCode)
            snapshot.eqProfileName = profileName()
        }
        snapshot.link = link(hasValues: snapshot.hasDeviceState)
        snapshot.isDemo = isDemo
        snapshot.pendingActions = pending.map(\.action)
        snapshot.lastOutcome = lastOutcome
        snapshot.updatedAt = now
        if snapshot.link == .live { snapshot.observedAt = now }
        current = snapshot
        return snapshot
    }

    private func link(hasValues: Bool) -> WidgetSnapshot.Link {
        switch controller.connectionState {
        case .connected:
            return controller.ambientSound != nil || controller.battery != nil ? .live : .connecting
        case .connecting, .initializing, .searching:
            return .connecting
        case .failed:
            return .offline
        case .disconnected:
            // Released while idle (on-demand mode) vs. not connected at all.
            return controller.releaseWhenIdle && controller.lastError == nil && hasValues ? .released : .offline
        }
    }

    private func schedulePublish() {
        guard store != nil else { return }
        publishTask?.cancel()
        let delay = UInt64(timing.publishDebounce * 1_000_000_000)
        publishTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay)
            guard let self, !Task.isCancelled else { return }
            self.publishNow()
        }
    }

    func publishNow() {
        guard let store else { return }
        publishTask?.cancel()
        publishTask = nil
        let snapshot = makeSnapshot()
        if let lastWritten, lastWritten.hasSameContent(as: snapshot) { return }
        do {
            try store.writeSnapshot(snapshot)
            lastWritten = snapshot
            requestReload()
        } catch {
            Self.log.error("Couldn't write the widget snapshot: \(error.localizedDescription, privacy: .public)")
        }
    }

    /// WidgetKit budgets reloads; coalesce bursts (a connect reports a dozen values) into
    /// at most one reload per `minReloadInterval`, always ending with the latest state.
    private func requestReload() {
        let wait = timing.minReloadInterval - Date().timeIntervalSince(lastReload)
        if wait <= 0 {
            reloadTask?.cancel()
            reloadTask = nil
            lastReload = Date()
            reloadWidgets()
        } else if reloadTask == nil {
            reloadTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
                guard let self, !Task.isCancelled else { return }
                self.reloadTask = nil
                self.lastReload = Date()
                self.reloadWidgets()
            }
        }
    }

    private func appWillTerminate() {
        guard let store else { return }
        pending.removeAll()
        var snapshot = makeSnapshot()
        snapshot.link = .appClosed
        snapshot.pendingActions = []
        try? store.writeSnapshot(snapshot)
        reloadWidgets()
    }
}

// MARK: - Model mapping

extension NoiseMode {
    init(_ mode: AmbientSoundMode) {
        switch mode {
        case .off: self = .off
        case .noiseCancelling: self = .noiseCancelling
        case .ambientSound: self = .ambientSound
        }
    }
}

extension AmbientSoundMode {
    init(_ mode: NoiseMode) {
        switch mode {
        case .off: self = .off
        case .noiseCancelling: self = .noiseCancelling
        case .ambientSound: self = .ambientSound
        }
    }
}

extension ListeningModeValue {
    init(_ mode: ListeningMode) {
        switch mode {
        case .standard: self = .standard
        case .backgroundMusic: self = .backgroundMusic
        case .cinema: self = .cinema
        }
    }
}
