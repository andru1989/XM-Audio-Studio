import Foundation

/// Files in the App Group container:
///
/// - `Widget/snapshot.json`: written only by the app, read by the widget.
/// - `Widget/Commands/<time>-<uuid>.json`: one file per widget tap, written only by the
///   widget and deleted by the app once it has taken the command.
///
/// One writer per file and atomic writes (temporary file + rename), so a reader never
/// sees a half-written file and there is no locking.
public struct WidgetStore: Sendable {
    public let directory: URL
    /// Darwin notification posted after a command is written.
    public let notificationName: String

    /// Commands bigger than this are not ours; ignore them.
    static let maxCommandBytes = 4_096
    /// Never queue more than this many commands (a stuck app shouldn't fill the disk).
    static let maxQueuedCommands = 32

    public init(directory: URL, notificationName: String = AppGroup.commandNotification) {
        self.directory = directory
        self.notificationName = notificationName
    }

    /// The store in the App Group container, or nil if this process can't use the group.
    public static func appGroup(requireEntitlement: Bool = true) -> WidgetStore? {
        guard let container = AppGroup.containerURL(requireEntitlement: requireEntitlement) else { return nil }
        return WidgetStore(directory: container.appendingPathComponent("Widget", isDirectory: true))
    }

    public var snapshotURL: URL { directory.appendingPathComponent("snapshot.json") }
    public var commandsURL: URL { directory.appendingPathComponent("Commands", isDirectory: true) }

    // MARK: - Snapshot (app writes, widget reads)

    public func readSnapshot() -> WidgetSnapshot? {
        guard let data = try? Data(contentsOf: snapshotURL) else { return nil }
        guard let snapshot = try? Self.decoder.decode(WidgetSnapshot.self, from: data),
              snapshot.version <= WidgetSnapshot.currentVersion else { return nil }
        return snapshot
    }

    public func writeSnapshot(_ snapshot: WidgetSnapshot) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Self.encoder.encode(snapshot).write(to: snapshotURL, options: .atomic)
    }

    // MARK: - Commands (widget writes, app takes)

    /// Writes the command and wakes the app with a Darwin notification.
    public func post(_ command: WidgetCommand) throws {
        try FileManager.default.createDirectory(at: commandsURL, withIntermediateDirectories: true)
        let millis = Int64((command.issuedAt.timeIntervalSince1970 * 1000).rounded())
        let name = String(format: "%013lld-%@.json", millis, command.id.uuidString)
        try Self.encoder.encode(command).write(to: commandsURL.appendingPathComponent(name), options: .atomic)
        trimQueue()
        DarwinNotification.post(notificationName)
    }

    /// Commands still in the mailbox (not yet taken by the app), oldest first. Read-only:
    /// the widget uses it to show a tap as pending until the app picks it up.
    public func queuedCommands(maxAge: TimeInterval, now: Date = Date()) -> [WidgetCommand] {
        commandFiles().compactMap { decode($0) }
            .filter { now.timeIntervalSince($0.issuedAt) <= maxAge }
            .sorted { $0.issuedAt < $1.issuedAt }
    }

    /// Removes every command from the mailbox and returns them, oldest first, split into
    /// the ones young enough to run and the ones that expired (e.g. tapped while the app
    /// wasn't running and nothing launched it). Unreadable files are deleted.
    public func takeCommands(maxAge: TimeInterval, now: Date = Date()) -> (fresh: [WidgetCommand], expired: [WidgetCommand]) {
        var fresh: [WidgetCommand] = []
        var expired: [WidgetCommand] = []
        for url in commandFiles() {
            let command = decode(url)
            try? FileManager.default.removeItem(at: url)
            guard let command else { continue }
            // A clock that went backwards counts as fresh; far-future stamps as expired.
            let age = now.timeIntervalSince(command.issuedAt)
            if age <= maxAge && age >= -maxAge {
                fresh.append(command)
            } else {
                expired.append(command)
            }
        }
        return (fresh.sorted { $0.issuedAt < $1.issuedAt }, expired.sorted { $0.issuedAt < $1.issuedAt })
    }

    private func commandFiles() -> [URL] {
        let urls = (try? FileManager.default.contentsOfDirectory(
            at: commandsURL, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]
        )) ?? []
        return urls.filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
    }

    private func decode(_ url: URL) -> WidgetCommand? {
        let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        guard size <= Self.maxCommandBytes, let data = try? Data(contentsOf: url) else { return nil }
        return try? Self.decoder.decode(WidgetCommand.self, from: data)
    }

    private func trimQueue() {
        let files = commandFiles()
        guard files.count > Self.maxQueuedCommands else { return }
        for url in files.prefix(files.count - Self.maxQueuedCommands) {
            try? FileManager.default.removeItem(at: url)
        }
    }

    // MARK: - Coding

    static let encoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601WithFractionalSeconds
        encoder.outputFormatting = [.sortedKeys]
        return encoder
    }()

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601WithFractionalSeconds
        return decoder
    }()
}

private extension JSONEncoder.DateEncodingStrategy {
    static let iso8601WithFractionalSeconds = custom { date, encoder in
        var container = encoder.singleValueContainer()
        try container.encode(Date.ISO8601FormatStyle(includingFractionalSeconds: true).format(date))
    }
}

private extension JSONDecoder.DateDecodingStrategy {
    static let iso8601WithFractionalSeconds = custom { decoder in
        let container = try decoder.singleValueContainer()
        let string = try container.decode(String.self)
        let strategy = Date.ISO8601FormatStyle(includingFractionalSeconds: true)
        if let date = try? strategy.parse(string) { return date }
        if let date = try? Date.ISO8601FormatStyle().parse(string) { return date }
        throw DecodingError.dataCorruptedError(in: container, debugDescription: "Bad date \(string)")
    }
}
