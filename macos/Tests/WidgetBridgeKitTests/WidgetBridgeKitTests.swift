import XCTest
import SonyHeadphonesKit
@testable import WidgetBridgeKit

final class WidgetModelTests: XCTestCase {
    func testEqPresetCodesMatchTheProtocolPresets() {
        XCTAssertEqual(EqPresetValue.allCases.count, EqualizerPreset.allCases.count)
        for value in EqPresetValue.allCases {
            let preset = EqualizerPreset(rawValue: UInt8(value.code))
            XCTAssertNotNil(preset, "\(value) has no protocol preset")
            XCTAssertEqual(preset?.label.lowercased(), value.rawValue)
            XCTAssertEqual(EqPresetValue(code: value.code), value)
        }
        XCTAssertNil(EqPresetValue(code: 0xa1)) // Sound Connect user preset
    }

    func testNoiseAndListeningModesMirrorTheProtocolEnums() {
        XCTAssertEqual(Set(NoiseMode.allCases.map(\.rawValue)), Set(AmbientSoundMode.allCases.map(\.rawValue)))
        XCTAssertEqual(Set(ListeningModeValue.allCases.map(\.rawValue)), Set(ListeningMode.allCases.map(\.rawValue)))
    }

    func testActionWireFormat() throws {
        let data = try WidgetStore.encoder.encode(WidgetAction.setNoiseMode(.ambientSound))
        XCTAssertEqual(String(decoding: data, as: UTF8.self), #"{"type":"setNoiseMode","value":"ambientSound"}"#)
        let eq = try WidgetStore.decoder.decode(WidgetAction.self, from: Data(#"{"type":"applyEqPreset","value":"heavy"}"#.utf8))
        XCTAssertEqual(eq, .applyEqPreset(.heavy))
        XCTAssertThrowsError(try WidgetStore.decoder.decode(WidgetAction.self, from: Data(#"{"type":"powerOff","value":"now"}"#.utf8)))
        XCTAssertThrowsError(try WidgetStore.decoder.decode(WidgetAction.self, from: Data(#"{"type":"setNoiseMode","value":"loud"}"#.utf8)))
    }

    func testSnapshotRoundTripAndContentEquality() throws {
        let snapshot = WidgetSnapshot(
            link: .released, modelID: "wh-1000xm6", deviceName: "WH-1000XM6", batteryLevel: 80, isCharging: false,
            noiseMode: .noiseCancelling, ambientLevel: 12, listeningMode: .standard, eqPresetCode: 0x31,
            eqProfileName: "Clear", isDemo: true, observedAt: Date(timeIntervalSince1970: 1_000),
            updatedAt: Date(timeIntervalSince1970: 2_000.25), pendingActions: [.applyEqPreset(.soft)],
            lastOutcome: CommandOutcome(commandID: UUID(), action: .setNoiseMode(.off), result: .unreachable,
                                        at: Date(timeIntervalSince1970: 1_500.5))
        )
        let decoded = try WidgetStore.decoder.decode(WidgetSnapshot.self, from: WidgetStore.encoder.encode(snapshot))
        XCTAssertEqual(decoded.updatedAt.timeIntervalSince1970, 2_000.25, accuracy: 0.001)
        XCTAssertTrue(decoded.hasSameContent(as: snapshot))
        XCTAssertEqual(decoded.eqPreset, .clear)

        var touched = snapshot
        touched.updatedAt = Date()
        touched.observedAt = Date()
        XCTAssertTrue(touched.hasSameContent(as: snapshot), "timestamps alone must not trigger a reload")
        touched.batteryLevel = 79
        XCTAssertFalse(touched.hasSameContent(as: snapshot))
    }
}

final class WidgetStoreTests: XCTestCase {
    private var directory: URL!
    private var store: WidgetStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("WidgetStoreTests-\(UUID().uuidString)")
        store = WidgetStore(directory: directory, notificationName: "io.github.andru1989.xmaudiostudio.tests.\(UUID().uuidString)")
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    func testMissingOrNewerSnapshotReadsAsNil() throws {
        XCTAssertNil(store.readSnapshot())
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var future = WidgetSnapshot()
        future.version = WidgetSnapshot.currentVersion + 1
        try WidgetStore.encoder.encode(future).write(to: store.snapshotURL)
        XCTAssertNil(store.readSnapshot(), "a snapshot from a newer app must not be misread")

        let current = WidgetSnapshot(link: .live, batteryLevel: 55)
        try store.writeSnapshot(current)
        XCTAssertEqual(store.readSnapshot()?.batteryLevel, 55)
    }

    func testCommandsAreTakenOnceInIssueOrder() throws {
        let now = Date()
        let second = WidgetCommand(issuedAt: now.addingTimeInterval(-1), action: .applyEqPreset(.heavy))
        let first = WidgetCommand(issuedAt: now.addingTimeInterval(-2), action: .setNoiseMode(.ambientSound))
        try store.post(second)
        try store.post(first)

        XCTAssertEqual(store.queuedCommands(maxAge: 60, now: now).map(\.id), [first.id, second.id])
        let taken = store.takeCommands(maxAge: 60, now: now)
        XCTAssertEqual(taken.fresh.map(\.id), [first.id, second.id])
        XCTAssertTrue(taken.expired.isEmpty)
        XCTAssertTrue(store.takeCommands(maxAge: 60, now: now).fresh.isEmpty, "commands are consumed")
    }

    func testOldCommandsExpireInsteadOfRunning() throws {
        let now = Date()
        let stale = WidgetCommand(issuedAt: now.addingTimeInterval(-600), action: .setNoiseMode(.off))
        let fresh = WidgetCommand(issuedAt: now, action: .setNoiseMode(.noiseCancelling))
        try store.post(stale)
        try store.post(fresh)
        XCTAssertEqual(store.queuedCommands(maxAge: 60, now: now).map(\.id), [fresh.id])
        let taken = store.takeCommands(maxAge: 60, now: now)
        XCTAssertEqual(taken.fresh.map(\.id), [fresh.id])
        XCTAssertEqual(taken.expired.map(\.id), [stale.id])
    }

    func testForeignAndOversizedFilesAreDiscarded() throws {
        try FileManager.default.createDirectory(at: store.commandsURL, withIntermediateDirectories: true)
        try Data("not json".utf8).write(to: store.commandsURL.appendingPathComponent("0000000000001-junk.json"))
        try Data(repeating: 0x20, count: WidgetStore.maxCommandBytes + 1)
            .write(to: store.commandsURL.appendingPathComponent("0000000000002-big.json"))
        try Data("ignored".utf8).write(to: store.commandsURL.appendingPathComponent("notes.txt"))
        let taken = store.takeCommands(maxAge: 60)
        XCTAssertTrue(taken.fresh.isEmpty && taken.expired.isEmpty)
        let left = try FileManager.default.contentsOfDirectory(atPath: store.commandsURL.path)
        XCTAssertEqual(left, ["notes.txt"])
    }

    func testQueueIsBounded() throws {
        let now = Date()
        for i in 0..<(WidgetStore.maxQueuedCommands + 5) {
            try store.post(WidgetCommand(issuedAt: now.addingTimeInterval(Double(i) / 1000), action: .setNoiseMode(.off)))
        }
        XCTAssertEqual(store.queuedCommands(maxAge: 60, now: now).count, WidgetStore.maxQueuedCommands)
    }

    func testPostingWakesAnObserver() throws {
        let woke = expectation(description: "Darwin notification delivered")
        let observer = DarwinNotificationObserver(name: store.notificationName) { woke.fulfill() }
        try store.post(WidgetCommand(action: .setNoiseMode(.off)))
        wait(for: [woke], timeout: 5)
        withExtendedLifetime(observer) {}
    }
}
