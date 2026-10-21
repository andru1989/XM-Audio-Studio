import Foundation
import SonyHeadphonesKit

/// Scenes on top of `HeadphonesController`: its published state as a `ReportedHeadphoneState`,
/// and scene intents sent through its regular setters. No bytes are built here; the controller
/// encodes each command when it is sent, after the state read that follows a (re)connect.
@MainActor
public extension HeadphonesController {
    /// What the controller has published, for capturing a scene or comparing against one.
    var reportedState: ReportedHeadphoneState {
        ReportedHeadphoneState(ambientSound: ambientSound, listeningMode: listeningMode, bgmRoomSize: bgmRoomSize,
                               speakToChatEnabled: speakToChatEnabled, pauseWhenTakenOff: pauseWhenTakenOff,
                               automaticPowerOff: automaticPowerOff, equalizer: equalizer)
    }

    /// Sends `intents` (from `HeadphoneScenes.intents`) in order through the controller setters.
    ///
    /// When the scene sets both the room and the listening mode, the room travels in the
    /// listening-mode write (one BGM message with both), so a state read that lands between two
    /// writes cannot put the old room back. The EQ curve is queued at once, not debounced, so it
    /// keeps its place before a switch to Background Music or Cinema. This controller drives the
    /// XM6 only, which has no Clear Bass; the Clear Bass of a 5-band profile is not sent.
    func apply(_ intents: [SceneIntent]) {
        let room: BGMRoomSize? = intents.lazy.compactMap { intent -> BGMRoomSize? in
            if case .bgmRoomSize(let size) = intent { return size }
            return nil
        }.first
        let setsListeningMode = intents.contains { if case .listeningMode = $0 { return true } else { return false } }
        for intent in intents {
            switch intent {
            case let .ambientSound(mode, focusOnVoice, level):
                var state = ambientSound ?? AmbientSoundState()
                state.mode = mode
                state.focusOnVoice = focusOnVoice
                state.level = level
                setAmbientSound(state)
            case .bgmRoomSize(let size):
                if !setsListeningMode { setBGMRoomSize(size) }
            case .listeningMode(let mode):
                setListeningMode(mode, roomSize: room)
            case .equalizerPreset(let code):
                if let preset = UInt8(exactly: code).flatMap(EqualizerPreset.init(rawValue:)) { setEqualizerPreset(preset) }
            case .equalizerBands(let bands, _):
                setEqualizerBands(bands, coalescing: false)
            case .speakToChat(let enabled):
                setSpeakToChatEnabled(enabled)
            case .pauseWhenTakenOff(let enabled):
                setPauseWhenTakenOff(enabled)
            case .automaticPowerOff(let mode):
                setAutomaticPowerOff(mode)
            }
        }
    }
}
