import XCTest
@testable import SonyHeadphonesKit

/// Same cases as android/core/.../PlaybackSourceAdvisorTest.kt and PlaybackSourceSessionTest.kt.
final class PlaybackSourceStateTests: XCTestCase {
    private let phone = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:02", name: "Galaxy S25 Ultra", connectedStatus: 0x01, isPlayback: false)
    private let mac = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:01", name: "MacBook Pro", connectedStatus: 0x02, isPlayback: true)
    private let ipad = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:03", name: "iPad", connectedStatus: 0x00, isPlayback: false)
    private var list: [MultipointDevice] { [mac, phone, ipad] }
    private var phoneHolds: [MultipointDevice] {
        var m = mac; m.isPlayback = false
        var p = phone; p.isPlayback = true
        return [m, p, ipad]
    }
    private let paused = PlaybackStatus(state: .paused)
    private let t0 = Date(timeIntervalSince1970: 0)

    private func state(_ status: PlaybackStatus? = PlaybackStatus(state: .paused)) -> PlaybackSourceState {
        var s = PlaybackSourceState()
        s.applyList(list)
        if let status { s.applyStatus(status) }
        return s
    }

    private func advise(_ s: PlaybackSourceState, _ this: MultipointDevice?, playing: TimeInterval?, now: TimeInterval,
                        follow: Bool, last: TimeInterval? = nil, interrupted: TimeInterval? = nil) -> SourceAdvice {
        PlaybackSourceAdvisor.advise(s, thisDevice: this, localPlayingSince: playing.map { t0 + $0 }, now: t0 + now,
                                     followPlayback: follow, lastAutoSwitch: last.map { t0 + $0 },
                                     interruptedSince: interrupted.map { t0 + $0 })
    }

    func testFindsThisDeviceByAddressThenByUniqueName() {
        XCTAssertEqual(Multipoint.thisDevice(list, localAddress: "aa-bb-cc-dd-ee-01", localName: nil), mac)
        XCTAssertEqual(Multipoint.thisDevice(list, localAddress: "02:00:00:00:00:00", localName: "galaxy s25 ultra "), phone)
        XCTAssertNil(Multipoint.thisDevice(list, localAddress: nil, localName: "Pixel"))
        var twin = phone; twin.macAddress = "AA:BB:CC:DD:EE:09"
        XCTAssertNil(Multipoint.thisDevice(list + [twin], localAddress: nil, localName: "Galaxy S25 Ultra"), "ambiguous")
    }

    func testControlsElsewhereOnlyWhenThisDevicePlaysAndTheOtherIsIdle() {
        let a = advise(state(), phone, playing: 0, now: 0.5, follow: false)
        XCTAssertTrue(a.controlsElsewhere)
        XCTAssertFalse(a.switchHereNow, "following is off")
        XCTAssertEqual(a.playbackDevice, mac)

        XCTAssertFalse(advise(state(), phone, playing: nil, now: 0.5, follow: true).controlsElsewhere)
        XCTAssertFalse(advise(state(), mac, playing: 0, now: 0.5, follow: true).controlsElsewhere)
        XCTAssertFalse(advise(state(PlaybackStatus(state: .playing)), phone, playing: 0, now: 5, follow: true).controlsElsewhere)
        XCTAssertFalse(advise(state(PlaybackStatus(state: .paused, isCall: true)), phone, playing: 0, now: 5, follow: true).controlsElsewhere)
        XCTAssertFalse(advise(state(), ipad, playing: 0, now: 5, follow: true).controlsElsewhere, "not connected")
        XCTAssertTrue(advise(state(nil), phone, playing: 0, now: 0.5, follow: true).controlsElsewhere)
    }

    func testFollowSwitchesOnlyAfterTheSettleTimeAndNotTooOften() {
        let s = state()
        XCTAssertFalse(advise(s, phone, playing: 10, now: 11, follow: true).switchHereNow)
        XCTAssertTrue(advise(s, phone, playing: 10, now: 12, follow: true).switchHereNow)
        XCTAssertFalse(advise(s, phone, playing: 10, now: 12, follow: true, last: 0).switchHereNow)
        XCTAssertTrue(advise(s, phone, playing: 10, now: 15, follow: true, last: 0).switchHereNow)
    }

    func testPendingSwitchHoldsAgainstStaleListsAndFailureReverts() {
        var switching = state()
        switching.beginSwitch(to: phone.macAddress)
        XCTAssertEqual(switching.playbackDevice?.macAddress, phone.macAddress)
        XCTAssertFalse(advise(switching, phone, playing: 0, now: 5, follow: true).controlsElsewhere)

        var stale = switching
        stale.applyList(list)
        XCTAssertEqual(stale.playbackDevice?.macAddress, phone.macAddress)
        XCTAssertEqual(stale.reportedDevices?.first { $0.isPlayback }?.macAddress, mac.macAddress)

        var refused = stale
        refused.applySwitchResult(.failedA2dpNotConnected, macAddress: phone.macAddress)
        XCTAssertEqual(refused.playbackDevice?.macAddress, mac.macAddress)
        XCTAssertEqual(refused.sourceSwitchFailure, .failedA2dpNotConnected)

        var confirmed = stale
        confirmed.applySwitchResult(.success, macAddress: phone.macAddress)
        XCTAssertEqual(confirmed.playbackDevice?.macAddress, phone.macAddress)
        XCTAssertNil(confirmed.switchingTo)

        var expired = switching
        expired.expireSwitch(phone.macAddress)
        XCTAssertEqual(expired.playbackDevice?.macAddress, mac.macAddress)
    }

    func testSwitchToADisconnectedDeviceIsNotShown() {
        var s = state()
        s.beginSwitch(to: ipad.macAddress)
        XCTAssertEqual(s.playbackDevice?.macAddress, mac.macAddress)
    }

    func testHandoverFromAPlayingDeviceIsAnInterruptionAndCanBeRecovered() {
        var playing = PlaybackSourceState()
        playing.applyList(phoneHolds)
        playing.applyStatus(PlaybackStatus(state: .playing))
        var stolen = playing
        stolen.applyList(list)
        stolen.applyStatus(paused)
        XCTAssertEqual(stolen.interruptedPlayback?.fromMac, phone.macAddress)

        let advice = advise(stolen, mac, playing: nil, now: 1, follow: false, interrupted: 0)
        XCTAssertEqual(advice.interrupted?.fromMac, phone.macAddress)
        XCTAssertFalse(advice.recoverNow)
        XCTAssertFalse(advise(stolen, mac, playing: nil, now: 1, follow: true, interrupted: 0).recoverNow)
        XCTAssertTrue(advise(stolen, mac, playing: nil, now: 2, follow: true, interrupted: 0).recoverNow)
        XCTAssertFalse(advise(stolen, mac, playing: nil, now: 2, follow: true, last: 1, interrupted: 0).recoverNow)

        var again = stolen
        again.applyStatus(PlaybackStatus(state: .playing))
        XCTAssertNil(again.interruptedPlayback)
        var back = stolen
        back.applyList(phoneHolds)
        XCTAssertNil(back.interruptedPlayback)
        var chosen = stolen
        chosen.beginSwitch(to: phone.macAddress)
        XCTAssertNil(chosen.interruptedPlayback)
    }

    func testMovesThatAreNotInterruptions() {
        var idle = PlaybackSourceState()
        idle.applyList(phoneHolds)
        idle.applyStatus(paused)
        idle.applyList(list)
        XCTAssertNil(idle.interruptedPlayback)

        var chosen = PlaybackSourceState()
        chosen.applyList(phoneHolds)
        chosen.applyStatus(PlaybackStatus(state: .playing))
        chosen.beginSwitch(to: mac.macAddress)
        chosen.expireSwitch(mac.macAddress)
        chosen.applyList(list)
        XCTAssertNil(chosen.interruptedPlayback)
    }
}

/// The controller against the byte-level simulator.
@MainActor
final class PlaybackSourceControllerTests: XCTestCase {
    private func connected(_ configure: (SimulatedXM6Connection) -> Void = { _ in }) async throws
        -> (HeadphonesController, SimulatedXM6Connection) {
        let simulator = SimulatedXM6Connection(latency: 0.002)
        configure(simulator)
        let controller = HeadphonesController(connection: simulator, pairedDevices: { [SimulatedXM6Connection.pairedDevice] },
                                              localAddress: { SimulatedXM6Connection.localAddress })
        controller.sourceWatchInterval = .milliseconds(300)
        controller.sourceSwitchTimeout = .milliseconds(600)
        controller.autoConnect()
        try await waitFor { controller.playbackSource.nowPlaying != nil && controller.devices != nil }
        return (controller, simulator)
    }

    func testConnectReadsSourcePlayStateTrackAndThisMac() async throws {
        let (controller, _) = try await connected()
        defer { controller.disconnect() }
        XCTAssertEqual(controller.playbackSource.playbackDevice?.name, "MacBook Pro")
        XCTAssertEqual(controller.thisDevice?.name, "MacBook Pro")
        XCTAssertEqual(controller.playbackSource.playbackStatus?.state, .playing)
        XCTAssertEqual(controller.playbackSource.nowPlaying?.title, "Amanecer en la ciudad")
    }

    func testSwitchIsShownAtOnceAndConfirmed() async throws {
        let (controller, simulator) = try await connected()
        defer { controller.disconnect() }
        let pixel = try XCTUnwrap(controller.devices?.first { $0.name == "Pixel" })
        controller.switchPlayback(to: pixel)
        XCTAssertEqual(controller.playbackSource.playbackDevice?.name, "Pixel", "optimistic")
        try await waitFor { controller.playbackSource.switchingTo == nil }
        XCTAssertEqual(simulator.playbackDeviceIndex, 1)
        XCTAssertEqual(controller.playbackSource.playbackDevice?.name, "Pixel")
        XCTAssertNil(controller.playbackSource.sourceSwitchFailure)
    }

    func testRefusedSwitchRevertsAndSaysWhy() async throws {
        let (controller, simulator) = try await connected { $0.sourceSwitchResult = 0x02 }
        defer { controller.disconnect() }
        controller.switchPlayback(toAddress: "AA:BB:CC:DD:EE:02", resume: true)
        try await waitFor { controller.playbackSource.sourceSwitchFailure != nil }
        XCTAssertEqual(controller.playbackSource.sourceSwitchFailure, .failedCalling)
        XCTAssertEqual(controller.playbackSource.playbackDevice?.name, "MacBook Pro")
        XCTAssertEqual(simulator.playbackDeviceIndex, 0)
    }

    func testSwitchWithResumeSendsPlayOnceConfirmed() async throws {
        let (controller, simulator) = try await connected()
        defer { controller.disconnect() }
        controller.controlPlayback(.pause)
        try await waitFor { simulator.playbackState == 0x02 }
        controller.switchPlayback(toAddress: "AA:BB:CC:DD:EE:02", resume: true)
        try await waitFor { simulator.playbackState == 0x01 }
        XCTAssertEqual(simulator.playbackDeviceIndex, 1)
    }

    func testSilentTakeoverIsFoundByTheWatchWhileVisible() async throws {
        let (controller, simulator) = try await connected()
        defer { controller.disconnect() }
        controller.setControlSurface(UUID(), visible: true)
        simulator.simulateSourceTakeover(index: 1, notify: false)
        try await waitFor { controller.playbackSource.playbackDevice?.name == "Pixel" }
    }

    func testHandoverWhilePlayingIsReportedAsAnInterruption() async throws {
        let (controller, simulator) = try await connected()
        defer { controller.disconnect() }
        simulator.simulateSourceTakeover(index: 1, notify: true, playbackState: 0x02)
        try await waitFor { controller.playbackSource.interruptedPlayback != nil }
        XCTAssertEqual(controller.playbackSource.interruptedPlayback?.fromName, "MacBook Pro")
    }

    func testPlaybackControlsAreRelayedAndReconciled() async throws {
        let (controller, simulator) = try await connected()
        defer { controller.disconnect() }
        controller.controlPlayback(.pause)
        XCTAssertEqual(controller.playbackSource.playbackStatus?.state, .paused, "optimistic")
        try await waitFor { simulator.playbackState == 0x02 }
        controller.controlPlayback(.next)
        try await waitFor { controller.playbackSource.nowPlaying?.title == "Ruido blanco" }
    }

    func testRefreshWhileDisconnectedDoesNotConnect() {
        let simulator = SimulatedXM6Connection(latency: 0.002)
        let controller = HeadphonesController(connection: simulator, pairedDevices: { [SimulatedXM6Connection.pairedDevice] })
        controller.refreshPlaybackSource()
        XCTAssertEqual(controller.connectionState, .disconnected)
    }

    private func waitFor(timeout: Duration = .seconds(5), _ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + timeout
        while !condition() {
            guard ContinuousClock.now < deadline else { return XCTFail("timed out") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}

/// Claims: the device that starts playing takes the audio (ADR-0011). Same cases as Kotlin.
@MainActor
final class PlaybackClaimTests: XCTestCase {
    private let phone = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:02", name: "Galaxy S25 Ultra", connectedStatus: 0x01, isPlayback: false)
    private let mac = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:01", name: "MacBook Pro", connectedStatus: 0x02, isPlayback: true)
    private let ipad = MultipointDevice(macAddress: "AA:BB:CC:DD:EE:03", name: "iPad", connectedStatus: 0x00, isPlayback: false)
    private let t0 = Date(timeIntervalSince1970: 0)

    private func state(_ status: PlaybackStatus? = PlaybackStatus(state: .paused)) -> PlaybackSourceState {
        var s = PlaybackSourceState()
        s.applyList([mac, phone, ipad])
        if let status { s.applyStatus(status) }
        return s
    }

    func testClaimTargetIsThisDeviceOnlyWhenItCanTakeTheAudio() {
        XCTAssertEqual(PlaybackSourceAdvisor.claimTarget(state(), localAddress: nil, localName: "Galaxy S25 Ultra"), phone)
        XCTAssertNil(PlaybackSourceAdvisor.claimTarget(state(), localAddress: "aa:bb:cc:dd:ee:01", localName: nil), "already has it")
        XCTAssertNil(PlaybackSourceAdvisor.claimTarget(state(), localAddress: nil, localName: "iPad"), "not connected")
        XCTAssertNil(PlaybackSourceAdvisor.claimTarget(state(), localAddress: nil, localName: "Pixel"), "unknown")
        XCTAssertNil(PlaybackSourceAdvisor.claimTarget(state(PlaybackStatus(state: .playing, isCall: true)), localAddress: nil, localName: "Galaxy S25 Ultra"))
        var pending = state()
        pending.beginSwitch(to: mac.macAddress)
        XCTAssertNil(PlaybackSourceAdvisor.claimTarget(pending, localAddress: nil, localName: "Galaxy S25 Ultra"), "switch pending")
        XCTAssertEqual(PlaybackSourceAdvisor.claimTarget(state(PlaybackStatus(state: .playing)), localAddress: nil, localName: "Galaxy S25 Ultra"),
                       phone, "newest start wins over a device that is playing")
    }

    func testOneClaimPerPlaybackStartAfterTheSettleTime() {
        func claim(_ follow: Bool, _ since: TimeInterval?, _ now: TimeInterval, _ claimed: TimeInterval?, _ last: TimeInterval?) -> Bool {
            PlaybackSourceAdvisor.shouldClaim(followPlayback: follow, localPlayingSince: since.map { t0 + $0 }, now: t0 + now,
                                              claimedStart: claimed.map { t0 + $0 }, lastAutoSwitch: last.map { t0 + $0 })
        }
        XCTAssertFalse(claim(false, 0, 5, nil, nil))
        XCTAssertFalse(claim(true, nil, 5, nil, nil))
        XCTAssertFalse(claim(true, 0, 1.5, nil, nil), "could be a notification")
        XCTAssertTrue(claim(true, 0, 2, nil, nil))
        XCTAssertFalse(claim(true, 0, 9, 0, 2), "already claimed")
        XCTAssertFalse(claim(true, 10, 12, 0, 2))
        XCTAssertTrue(claim(true, 20, 22, 0, 2))
    }

    func testClaimWithTheChannelReleasedConnectsReadsAndTakesTheAudio() async throws {
        let simulator = SimulatedXM6Connection(latency: 0.002)
        // This "Mac" is the second entry (Pixel's address) so it does not hold the audio yet.
        let controller = HeadphonesController(connection: simulator, pairedDevices: { [SimulatedXM6Connection.pairedDevice] },
                                              localAddress: { "aa-bb-cc-dd-ee-02" })
        controller.releaseWhenIdle = true
        defer { controller.disconnect() }
        controller.claimPlaybackHere()
        try await waitFor { simulator.playbackDeviceIndex == 1 }
        XCTAssertEqual(controller.playbackSource.playbackDevice?.macAddress, "AA:BB:CC:DD:EE:02")
    }

    func testClaimByTheDeviceThatAlreadyHasTheAudioWritesNothing() async throws {
        let simulator = SimulatedXM6Connection(latency: 0.002)
        let controller = HeadphonesController(connection: simulator, pairedDevices: { [SimulatedXM6Connection.pairedDevice] },
                                              localAddress: { SimulatedXM6Connection.localAddress })
        controller.releaseWhenIdle = false
        defer { controller.disconnect() }
        controller.claimPlaybackHere()
        try await waitFor { controller.playbackSource.nowPlaying != nil }
        try await Task.sleep(for: .milliseconds(200))
        XCTAssertEqual(simulator.playbackDeviceIndex, 0)
        XCTAssertNil(controller.playbackSource.switchingTo)
    }

    private func waitFor(timeout: Duration = .seconds(5), _ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + timeout
        while !condition() {
            guard ContinuousClock.now < deadline else { return XCTFail("timed out") }
            try await Task.sleep(for: .milliseconds(10))
        }
    }
}
