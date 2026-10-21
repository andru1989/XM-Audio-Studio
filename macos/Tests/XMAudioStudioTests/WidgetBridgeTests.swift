import XCTest
@testable import SonyHeadphonesKit
@testable import XMAudioStudio
import WidgetBridgeKit

/// The app side of the widget IPC, driven through the byte-level XM6 simulator (demo
/// transport): commands must survive a released channel, wait for the real state, and
/// end up in the snapshot the widget reads.
@MainActor
final class WidgetBridgeTests: XCTestCase {
    private var directory: URL!
    private var store: WidgetStore!
    private var reloads = 0

    override func setUp() async throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("WidgetBridgeTests-\(UUID().uuidString)")
        store = WidgetStore(directory: directory, notificationName: "io.github.andru1989.xmaudiostudio.tests.\(UUID().uuidString)")
        reloads = 0
    }

    override func tearDown() async throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeBridge(_ controller: HeadphonesController, waitLimit: TimeInterval = 10) -> WidgetBridge {
        var timing = WidgetBridge.Timing()
        timing.commandWaitLimit = waitLimit
        timing.stateGrace = 3
        timing.publishDebounce = 0.02
        timing.minReloadInterval = 0.5
        timing.pumpInterval = 0.1
        let bridge = WidgetBridge(controller: controller, store: store, isDemo: true, timing: timing,
                                  profileName: { "Profile" }, reloadWidgets: { [weak self] in self?.reloads += 1 })
        bridge.start()
        return bridge
    }

    private func releasedDemoController() -> HeadphonesController {
        let controller = HeadphonesController.demo()
        controller.releaseWhenIdle = true // no visible surface: stays disconnected until used
        return controller
    }

    private func waitUntil(_ what: String, timeout: TimeInterval = 10, _ condition: () -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() > end { XCTFail("Timed out waiting for \(what)"); return }
            try await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    func testNoiseCommandWhileReleasedConnectsReadsStateThenWrites() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        XCTAssertEqual(controller.connectionState, .disconnected)

        try store.post(WidgetCommand(action: .setNoiseMode(.ambientSound)))
        bridge.takeCommands() // what the Darwin notification triggers

        XCTAssertEqual(bridge.pending.count, 1, "kept, not dropped, while the channel opens")
        XCTAssertEqual(store.readSnapshot()?.pendingActions, [.setNoiseMode(.ambientSound)])
        try await waitUntil("ambient mode applied") { controller.ambientSound?.mode == .ambientSound }
        // Built on the level the headphones reported (12 in the simulator), not a default.
        XCTAssertEqual(controller.ambientSound?.level, 12)
        XCTAssertEqual(bridge.lastOutcome?.result, .sent)
        XCTAssertTrue(bridge.pending.isEmpty)

        try await waitUntil("snapshot published") {
            self.store.readSnapshot()?.noiseMode == .ambientSound && self.store.readSnapshot()?.batteryLevel != nil
        }
        let snapshot = try XCTUnwrap(store.readSnapshot())
        XCTAssertEqual(snapshot.link, .live)
        XCTAssertEqual(snapshot.modelID, "wh-1000xm6")
        XCTAssertEqual(snapshot.batteryLevel, 72)
        XCTAssertTrue(snapshot.isDemo)
        XCTAssertTrue(snapshot.pendingActions.isEmpty)
        XCTAssertGreaterThan(reloads, 0)
    }

    func testEqPresetCommandAppliesAndReportsAlreadySet() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .applyEqPreset(.heavy))])
        try await waitUntil("Heavy applied") { controller.equalizer?.presetCode == EqualizerPreset.heavy.rawValue }
        XCTAssertEqual(bridge.lastOutcome?.result, .sent)
        try await waitUntil("snapshot shows Heavy") { self.store.readSnapshot()?.eqPreset == .heavy }
        XCTAssertEqual(store.readSnapshot()?.eqProfileName, "Profile")

        bridge.submit([WidgetCommand(action: .applyEqPreset(.heavy))])
        XCTAssertEqual(bridge.lastOutcome?.result, .alreadySet, "connected with known state: decided at once")
    }

    func testLaterTapOfTheSameKindSupersedesEarlierOnes() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .setNoiseMode(.ambientSound))])
        bridge.submit([WidgetCommand(action: .setNoiseMode(.off)), WidgetCommand(action: .applyEqPreset(.soft))])
        XCTAssertEqual(bridge.pending.map(\.action), [.setNoiseMode(.off), .applyEqPreset(.soft)])
        try await waitUntil("both applied") {
            controller.ambientSound?.mode == .off && controller.equalizer?.presetCode == EqualizerPreset.soft.rawValue
        }
    }

    func testValuesStayAsLastKnownWhenTheChannelIsReleased() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .setNoiseMode(.noiseCancelling))])
        try await waitUntil("live snapshot with battery") {
            self.store.readSnapshot()?.link == .live && self.store.readSnapshot()?.batteryLevel != nil
        }
        XCTAssertEqual(bridge.lastOutcome?.result, .alreadySet) // the simulator starts in NC

        controller.disconnect() // what the idle timer does after 20 s
        XCTAssertNil(controller.battery)
        try await waitUntil("released snapshot") { self.store.readSnapshot()?.link == .released }
        let snapshot = try XCTUnwrap(store.readSnapshot())
        XCTAssertEqual(snapshot.batteryLevel, 72)
        XCTAssertEqual(snapshot.noiseMode, .noiseCancelling)
        XCTAssertNotNil(snapshot.observedAt)
    }

    func testCommandAfterIdleReleaseReconnects() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .setNoiseMode(.ambientSound))])
        try await waitUntil("first command applied") { controller.ambientSound?.mode == .ambientSound }
        try await Task.sleep(nanoseconds: 300_000_000) // let the write's ACK arrive

        controller.disconnect() // what the idle timer does after 20 s
        try await waitUntil("released snapshot") { self.store.readSnapshot()?.link == .released }

        bridge.submit([WidgetCommand(action: .setNoiseMode(.off))])
        try await waitUntil("second command applied after reconnecting") { controller.ambientSound?.mode == .off }
        XCTAssertEqual(bridge.lastOutcome?.result, .sent)
    }

    func testCommandLeftWhileTheAppWasClosedRunsAtStart() async throws {
        // The widget launched the app; the command was written before the bridge existed.
        try store.post(WidgetCommand(action: .applyEqPreset(.soft)))
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        try await waitUntil("applied at startup") { controller.equalizer?.presetCode == EqualizerPreset.soft.rawValue }
        XCTAssertEqual(bridge.lastOutcome?.result, .sent, "\(String(describing: bridge.lastOutcome)) \(controller.connectionState)")
        XCTAssertTrue(store.queuedCommands(maxAge: 60).isEmpty)
    }

    func testLastKnownValuesDoNotCrossDemoAndRealMode() throws {
        try store.writeSnapshot(WidgetSnapshot(link: .released, batteryLevel: 72, noiseMode: .ambientSound, isDemo: true))
        let real = WidgetBridge(controller: HeadphonesController.demo(), store: store, isDemo: false)
        XCTAssertNil(real.makeSnapshot().batteryLevel, "demo values must not show up as the real headphones'")
        let demo = WidgetBridge(controller: HeadphonesController.demo(), store: store, isDemo: true)
        XCTAssertEqual(demo.makeSnapshot().batteryLevel, 72)
        XCTAssertEqual(demo.makeSnapshot().link, .offline, "never starts as live")
    }

    func testNoPairedHeadsetFailsInsteadOfWaiting() async throws {
        let controller = HeadphonesController(connection: SimulatedXM6Connection(), pairedDevices: { [] })
        controller.releaseWhenIdle = true
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .setNoiseMode(.off))])
        XCTAssertEqual(bridge.lastOutcome?.result, .unreachable)
        XCTAssertTrue(bridge.pending.isEmpty)
        try await waitUntil("failure published") { self.store.readSnapshot()?.lastOutcome?.result == .unreachable }
    }

    func testExpiredMailboxCommandIsNotApplied() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        try store.post(WidgetCommand(issuedAt: Date().addingTimeInterval(-3_600), action: .setNoiseMode(.off)))
        bridge.takeCommands()
        XCTAssertEqual(bridge.lastOutcome?.result, .expired)
        XCTAssertEqual(controller.connectionState, .disconnected, "an expired tap must not even connect")
    }

    func testLegacyModeConnectsForTheCommand() async throws {
        let controller = HeadphonesController.demo()
        XCTAssertFalse(controller.releaseWhenIdle)
        let bridge = makeBridge(controller)
        bridge.submit([WidgetCommand(action: .setNoiseMode(.ambientSound))])
        try await waitUntil("applied") { controller.ambientSound?.mode == .ambientSound }
        XCTAssertEqual(bridge.lastOutcome?.result, .sent)
    }

    func testReloadsAreThrottled() async throws {
        let controller = releasedDemoController()
        let bridge = makeBridge(controller)
        let before = reloads
        bridge.submit([WidgetCommand(action: .applyEqPreset(.clear))])
        try await waitUntil("applied") { controller.equalizer?.presetCode == EqualizerPreset.clear.rawValue }
        // A connect reports a dozen values within ~0.5 s; the widget is reloaded only a few times.
        try await Task.sleep(nanoseconds: 700_000_000)
        XCTAssertLessThanOrEqual(reloads - before, 4)
        XCTAssertEqual(store.readSnapshot()?.eqPreset, .clear, "the trailing reload carries the final state")
    }
}
