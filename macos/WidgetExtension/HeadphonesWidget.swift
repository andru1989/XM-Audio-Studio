import WidgetKit
import SwiftUI
import AppKit
import ImageIO
import WidgetBridgeKit

@main
struct XMAudioStudioWidgets: WidgetBundle {
    var body: some Widget {
        HeadphonesWidget()
    }
}

struct HeadphonesWidget: Widget {
    static let kind = "io.github.andru1989.xmaudiostudio.headphones"

    var body: some WidgetConfiguration {
        StaticConfiguration(kind: Self.kind, provider: HeadphonesProvider()) { entry in
            HeadphonesWidgetView(entry: entry)
                .containerBackground(for: .widget) { WidgetBackground() }
        }
        .configurationDisplayName("XM Audio Studio")
        .description("Battery, noise control and equalizer presets for your Sony headphones.")
        .supportedFamilies([.systemSmall, .systemMedium, .systemLarge])
    }
}

// MARK: - Timeline

struct HeadphonesEntry: TimelineEntry {
    let date: Date
    /// nil until the app has published anything.
    let snapshot: WidgetSnapshot?
    /// Taps not yet confirmed: still in the mailbox, or accepted by the app and waiting.
    let pending: [WidgetAction]
    /// Shown only for a while after it happened.
    let failure: CommandOutcome.Result?
    let appRunning: Bool
    /// Gallery preview with no real data yet.
    var isSample = false

    func isPending(_ action: WidgetAction) -> Bool { pending.contains(action) }
}

struct HeadphonesProvider: TimelineProvider {
    /// How long the widget keeps showing that a tap failed.
    static let failureDisplayTime: TimeInterval = 120
    /// Matches the app's `commandMaxAge`: after that a queued tap is no longer pending.
    static let commandMaxAge: TimeInterval = 60

    func placeholder(in context: Context) -> HeadphonesEntry {
        Self.sample
    }

    func getSnapshot(in context: Context, completion: @escaping (HeadphonesEntry) -> Void) {
        let entries = Self.entries(now: Date())
        if context.isPreview, entries.first?.snapshot?.hasDeviceState != true {
            completion(Self.sample)
        } else {
            completion(entries.first ?? Self.sample)
        }
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<HeadphonesEntry>) -> Void) {
        // The app reloads the timeline whenever something changes; the extra entries only
        // retire "pending" and failure states when they go stale, and the periodic refresh
        // re-checks whether the app is still running.
        let now = Date()
        completion(Timeline(entries: Self.entries(now: now), policy: .after(now.addingTimeInterval(30 * 60))))
    }

    static func entries(now: Date) -> [HeadphonesEntry] {
        let store = WidgetStore.appGroup(requireEntitlement: false)
        let snapshot = store?.readSnapshot()
        let queued = store?.queuedCommands(maxAge: commandMaxAge, now: now) ?? []
        let running = !NSRunningApplication.runningApplications(withBundleIdentifier: AppGroup.appBundleIdentifier).isEmpty

        // Change points: when each queued tap stops counting as pending, when a failure
        // message stops showing.
        var dates = [now]
        dates += queued.map { $0.issuedAt.addingTimeInterval(commandMaxAge) }
        if let outcome = snapshot?.lastOutcome, outcome.result.isFailure {
            dates.append(outcome.at.addingTimeInterval(failureDisplayTime))
        }
        return dates.filter { $0 >= now }.sorted().map { date in
            var pending = running ? (snapshot?.pendingActions ?? []) : []
            for command in queued where date.timeIntervalSince(command.issuedAt) < commandMaxAge {
                pending.removeAll { $0.kind == command.action.kind }
                pending.append(command.action)
            }
            var failure: CommandOutcome.Result?
            if let outcome = snapshot?.lastOutcome, outcome.result.isFailure,
               date.timeIntervalSince(outcome.at) < failureDisplayTime {
                failure = outcome.result
            }
            return HeadphonesEntry(date: date, snapshot: snapshot, pending: pending, failure: failure, appRunning: running)
        }
    }

    static let sample = HeadphonesEntry(
        date: Date(),
        snapshot: WidgetSnapshot(
            link: .live, modelID: "wh-1000xm6", deviceName: "WH-1000XM6", batteryLevel: 80, isCharging: false,
            noiseMode: .noiseCancelling, ambientLevel: 12, listeningMode: .standard, eqPresetCode: EqPresetValue.clear.code,
            observedAt: Date()
        ),
        pending: [], failure: nil, appRunning: true, isSample: true
    )
}

// MARK: - Product photo

enum ModelPhoto {
    private static var cache: [String: NSImage] = [:]

    /// The bundled product photo for a model, downsampled: widget extensions have a small
    /// memory budget and the source images are 800×800.
    static func image(for modelID: String?) -> NSImage? {
        guard let modelID else { return nil }
        if let cached = cache[modelID] { return cached }
        guard let url = Bundle.main.url(forResource: modelID, withExtension: "png", subdirectory: "Models"),
              let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: 192,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let cgImage = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let image = NSImage(cgImage: cgImage, size: NSSize(width: cgImage.width, height: cgImage.height))
        cache[modelID] = image
        return image
    }
}
