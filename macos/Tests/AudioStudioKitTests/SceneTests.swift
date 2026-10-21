import XCTest
@testable import AudioStudioKit
import SonyHeadphonesKit

/// Same cases as `android/core/.../presets/ScenesTest.kt`, against the same shared files.
final class SceneTests: XCTestCase {
    var tempDir: URL!

    override func setUpWithError() throws {
        tempDir = FileManager.default.temporaryDirectory.appendingPathComponent("xmas-scenes-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: tempDir)
    }

    private func shared(_ path: String) throws -> Data {
        try Data(contentsOf: ProfileTests.shared.appendingPathComponent(path))
    }

    private func library() -> ProfileLibrary {
        var n = 0
        return ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("profiles.json")),
                              now: { "2026-10-21T00:00:0\(n)Z" }, newId: { n += 1; return "id-\(n)" })
    }

    // MARK: - Format

    func testV1FilesStillDecodeAndAreRewrittenAsV2() throws {
        let profiles = try ProfileCodec.decode(shared("preset-schema/examples/export-v1.json"))
        XCTAssertTrue(profiles.allSatisfy { $0.scene == nil })
        let encoded = try ProfileCodec.encode(profiles, exportedAt: "2026-10-21T12:00:00Z")
        let object = try JSONSerialization.jsonObject(with: encoded) as! [String: Any]
        XCTAssertEqual(object["version"] as? Int, 2)
        XCTAssertFalse(String(decoding: encoded, as: UTF8.self).contains("\"scene\""), "an EQ-only profile writes no scene key")
        XCTAssertEqual(try ProfileCodec.decode(encoded), profiles)
        XCTAssertTrue(BuiltinProfiles.all.allSatisfy { $0.scene == nil })
        XCTAssertEqual(BuiltinProfiles.all.count, 14)
    }

    func testV2ExampleDecodesAndRoundTrips() throws {
        let profiles = try ProfileCodec.decode(shared("preset-schema/examples/export-v2.json"))
        XCTAssertEqual(profiles.count, 4)
        let office = try XCTUnwrap(profiles[0].scene)
        XCTAssertEqual(office.noiseControl, SceneNoiseControl(mode: .ambientSound, level: 12, focusOnVoice: true))
        XCTAssertEqual(office.listeningMode, .standard)
        XCTAssertEqual(office.bgmRoomSize, .middle)
        XCTAssertEqual(office.speakToChat, true)
        XCTAssertEqual(office.pauseWhenTakenOff, true)
        XCTAssertEqual(office.autoPowerOff, .whenTakenOff)
        let plane = try XCTUnwrap(profiles[1].scene)
        XCTAssertEqual(plane.noiseControl, SceneNoiseControl(mode: .noiseCancelling))
        XCTAssertNil(plane.pauseWhenTakenOff)
        XCTAssertEqual(profiles[1].deviceEq.presetCode, 0x30)
        XCTAssertEqual(profiles[2].model, "wh-1000xm4")
        XCTAssertNil(profiles[3].scene)
        XCTAssertEqual(try ProfileCodec.decode(ProfileCodec.encode(profiles, exportedAt: "2026-10-21T12:00:00Z")), profiles)
    }

    func testInvalidSceneExamplesAreRejectedForTheRightReason() throws {
        func reason(_ file: String) throws -> String {
            do {
                _ = try ProfileCodec.decode(shared("preset-schema/examples/invalid/\(file)"))
                XCTFail("\(file) should be rejected")
                return ""
            } catch let error as ProfileFormatError {
                return error.message
            }
        }
        XCTAssertTrue(try reason("scene-in-v1.json").contains("version 2"))
        XCTAssertTrue(try reason("scene-empty.json").contains("empty"))
        XCTAssertTrue(try reason("scene-level-out-of-range.json").contains("level"))
        XCTAssertTrue(try reason("scene-unsupported-feature.json").contains("listeningMode"))
        XCTAssertTrue(try reason("future-version.json").contains("newer"))
        for file in ["scene-bad-noise-mode.json", "scene-noise-without-mode.json", "scene-bad-room-size.json", "scene-bad-auto-power-off.json"] {
            XCTAssertFalse(try reason(file).isEmpty, file)
        }
    }

    func testValidationFollowsTheModelCapabilities() {
        func validate(_ model: String, _ scene: HeadphoneScene, bands: Int) -> String? {
            ProfileCodec.validate(EqProfile(id: "x", name: "x", category: .user, model: model,
                                            deviceEq: DeviceEqSetting(steps: Array(repeating: 0, count: bands)), scene: scene))
        }
        let nc = HeadphoneScene(noiseControl: SceneNoiseControl(mode: .ambientSound, level: 20, focusOnVoice: true))
        XCTAssertNil(validate("wh-1000xm6", nc, bands: 10))
        XCTAssertNil(validate("wh-1000xm5", nc, bands: 5))
        XCTAssertNil(validate("wh-1000xm6", HeadphoneScene(pauseWhenTakenOff: false, autoPowerOff: .off), bands: 10))
        XCTAssertTrue(validate("wh-1000xm5", HeadphoneScene(pauseWhenTakenOff: true), bands: 5)?.contains("pauseWhenTakenOff") ?? false)
        XCTAssertTrue(validate("wh-1000xm5", HeadphoneScene(autoPowerOff: .off), bands: 5)?.contains("autoPowerOff") ?? false)
        XCTAssertTrue(validate("wh-1000xm3", HeadphoneScene(speakToChat: true), bands: 5)?.contains("speakToChat") ?? false)
        XCTAssertTrue(validate("wh-1000xm4", HeadphoneScene(bgmRoomSize: .small), bands: 5)?.contains("bgmRoomSize") ?? false)
        XCTAssertTrue(validate("wh-1000xm6", HeadphoneScene(noiseControl: SceneNoiseControl(mode: .off, level: 0)), bands: 10)?
            .contains("level") ?? false)
    }

    // MARK: - Shared vectors

    private lazy var vectors: [String: Any] = {
        let data = try! Data(contentsOf: ProfileTests.shared.appendingPathComponent("preset-schema/scene-vectors.json"))
        return try! JSONSerialization.jsonObject(with: data) as! [String: Any]
    }()

    /// Re-decodes a JSON fragment with the codable types (the same vocabulary as the profile file).
    private func decode<T: Decodable>(_ type: T.Type, _ value: Any) throws -> T {
        try JSONDecoder().decode(T.self, from: JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed]))
    }

    /// The scene vocabulary for what the headphones report, as the controller's published state.
    private func reported(_ o: [String: Any]) throws -> ReportedHeadphoneState {
        var state = ReportedHeadphoneState()
        if let nc = o["noiseControl"] as? [String: Any] {
            state.ambientSound = AmbientSoundState(mode: try decode(SceneNoiseMode.self, nc["mode"]!).mode,
                                                   focusOnVoice: nc["focusOnVoice"] as! Bool, level: nc["level"] as! Int)
        }
        state.listeningMode = try o["listeningMode"].map { try decode(SceneListeningMode.self, $0).mode }
        state.bgmRoomSize = try o["bgmRoomSize"].map { try decode(SceneRoomSize.self, $0).size }
        state.speakToChatEnabled = o["speakToChat"] as? Bool
        state.pauseWhenTakenOff = o["pauseWhenTakenOff"] as? Bool
        state.automaticPowerOff = try o["autoPowerOff"].map { try decode(SceneAutoPowerOff.self, $0).mode }
        if let eq = o["equalizer"] as? [String: Any] {
            state.equalizer = EqualizerState(presetCode: UInt8(eq["presetCode"] as! Int), bands: eq["bands"] as! [Int],
                                             clearBass: eq["clearBass"] as? Int)
        }
        return state
    }

    private func intent(_ o: [String: Any]) throws -> SceneIntent {
        switch o["type"] as! String {
        case "ambientSound":
            return .ambientSound(mode: try decode(SceneNoiseMode.self, o["mode"]!).mode, focusOnVoice: o["focusOnVoice"] as! Bool,
                                 level: o["level"] as! Int)
        case "bgmRoomSize": return .bgmRoomSize(try decode(SceneRoomSize.self, o["roomSize"]!).size)
        case "listeningMode": return .listeningMode(try decode(SceneListeningMode.self, o["mode"]!).mode)
        case "eqBands": return .equalizerBands(o["steps"] as! [Int], clearBass: o["clearBass"] as? Int)
        case "eqPreset": return .equalizerPreset(o["code"] as! Int)
        case "speakToChat": return .speakToChat(o["enabled"] as! Bool)
        case "pauseWhenTakenOff": return .pauseWhenTakenOff(o["enabled"] as! Bool)
        case "autoPowerOff": return .automaticPowerOff(try decode(SceneAutoPowerOff.self, o["mode"]!).mode)
        default: throw ProfileFormatError(message: "unknown intent \(o)")
        }
    }

    func testCaptureMatchesSharedVectors() throws {
        for c in vectors["capture"] as! [[String: Any]] {
            let name = c["name"] as! String
            let model = HeadphoneModel(rawValue: c["model"] as! String)!
            let state = try reported(c["state"] as! [String: Any])
            let expectedScene = c["scene"] is NSNull ? nil : try decode(HeadphoneScene.self, c["scene"]!)
            XCTAssertEqual(HeadphoneScenes.capture(state, capabilities: DeviceCatalog.capabilities(model)), expectedScene, name)

            if c["deviceEq"] is NSNull {
                XCTAssertThrowsError(try library().saveScene(name: "Escena", modelId: model.rawValue, state: state), name)
            } else {
                let profile = try library().saveScene(name: "Escena", modelId: model.rawValue, state: state)
                XCTAssertEqual(profile.deviceEq, try decode(DeviceEqSetting.self, c["deviceEq"]!), name)
                XCTAssertEqual(profile.scene, expectedScene, name)
                XCTAssertEqual(profile.category, .user, name)
            }
        }
    }

    func testApplyMatchesSharedVectors() throws {
        for c in vectors["apply"] as! [[String: Any]] {
            let name = c["name"] as! String
            let caps = DeviceCatalog.capabilities(HeadphoneModel(rawValue: c["model"] as! String)!)
            let current = try reported(c["current"] as! [String: Any])
            let scene = try decode(HeadphoneScene.self, c["scene"]!)
            let eq = c["eq"] is NSNull ? nil : HeadphoneScenes.eqIntent(try decode(DeviceEqSetting.self, c["eq"]!), capabilities: caps)
            let expected = try (c["intents"] as! [[String: Any]]).map(intent)
            XCTAssertEqual(HeadphoneScenes.intents(scene, current: current, capabilities: caps, eq: eq), expected, name)
            XCTAssertEqual(HeadphoneScenes.changes(scene, current: current, capabilities: caps).map(\.key), c["changes"] as! [String], name)
        }
    }

    // MARK: - Library and controller

    func testSceneSurvivesPersistenceDuplicateRenameAndExport() throws {
        let lib = library()
        let state = ReportedHeadphoneState(
            ambientSound: AmbientSoundState(mode: .ambientSound, focusOnVoice: true, level: 9),
            listeningMode: .backgroundMusic, bgmRoomSize: .large, speakToChatEnabled: false, pauseWhenTakenOff: true,
            automaticPowerOff: .off, equalizer: EqualizerState(presetCode: 0xa0, bands: [1, 2, 3, 0, 0, 0, 0, 0, 0, 0])
        )
        let saved = try lib.saveScene(name: "  Café  ", modelId: "wh-1000xm6", state: state)
        XCTAssertEqual(saved.name, "Café")
        let copy = try lib.duplicate(saved.id, name: "Café 2")
        try lib.rename(copy.id, to: "Café B")
        try lib.updateSteps(copy.id, steps: Array(repeating: 1, count: 10))

        let reopened = ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("profiles.json")))
        XCTAssertEqual(reopened.userProfiles[0].scene, saved.scene)
        XCTAssertEqual(reopened.userProfiles[1].scene, saved.scene)
        XCTAssertEqual(reopened.userProfiles[1].deviceEq.steps, Array(repeating: 1, count: 10))

        let other = ProfileLibrary(store: ProfileStore(url: tempDir.appendingPathComponent("other.json")))
        let imported = try other.importData(lib.exportData())
        XCTAssertEqual(imported.map(\.scene), [saved.scene, saved.scene])
        XCTAssertThrowsError(try lib.saveScene(name: "x", modelId: "mdr-1000x", state: state))
    }

    @MainActor
    private func waitUntil(_ timeout: TimeInterval = 5, _ condition: () -> Bool) async {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() && Date() < deadline {
            try? await Task.sleep(nanoseconds: 20_000_000)
        }
    }

    /// End to end against the byte-level XM6 simulator (demo mode), with the channel held and released.
    @MainActor
    func testSceneReachesTheSimulatedHeadphonesThroughTheControllerSetters() async throws {
        let scene = HeadphoneScene(
            noiseControl: SceneNoiseControl(mode: .ambientSound, level: 5, focusOnVoice: true),
            listeningMode: .backgroundMusic, bgmRoomSize: .large,
            speakToChat: true, pauseWhenTakenOff: false, autoPowerOff: .whenTakenOff
        )
        let caps = DeviceCatalog.capabilities(.wh1000xm6)
        for held in [true, false] {
            let controller = HeadphonesController.demo()
            controller.autoConnect()
            await waitUntil { controller.connectionState == .connected && controller.equalizer != nil && controller.listeningMode != nil }
            XCTAssertEqual(controller.connectionState, .connected)
            if !held {
                // Released: the setters reconnect, read the real state, then write.
                controller.releaseWhenIdle = true
                controller.disconnect()
            }
            let eq = HeadphoneScenes.eqIntent(DeviceEqSetting(steps: [2, 1, 0, 0, 0, 0, 0, 0, 1, 2]), capabilities: caps)
            controller.apply(HeadphoneScenes.intents(scene, current: controller.reportedState, capabilities: caps, eq: eq))
            await waitUntil { controller.connectionState == .connected }
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            // Re-read everything so the check sees what the simulated headphones hold, not the optimistic UI.
            controller.refreshState()
            try? await Task.sleep(nanoseconds: 800_000_000)

            let label = held ? "held" : "released"
            XCTAssertEqual(HeadphoneScenes.capture(controller.reportedState, capabilities: caps), scene, label)
            XCTAssertEqual(controller.equalizer?.bands, [2, 1, 0, 0, 0, 0, 0, 0, 1, 2], label)
            XCTAssertEqual(controller.equalizer?.presetCode, EqualizerPreset.custom.rawValue, label)
            controller.disconnect()
        }
    }
}
