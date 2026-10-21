import Foundation

/// Darwin notifications (notify(3)): the one cross-process signal that both the
/// non-sandboxed app and the sandboxed widget extension can use without XPC.
/// They carry no payload; the data travels through `WidgetStore` files.
public enum DarwinNotification {
    public static func post(_ name: String) {
        CFNotificationCenterPostNotification(
            CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(name as CFString), nil, nil, true
        )
    }
}

/// Calls `handler` on the main queue each time the named Darwin notification is posted,
/// until the observer is released.
public final class DarwinNotificationObserver {
    private let name: String
    private let handler: () -> Void

    public init(name: String, handler: @escaping () -> Void) {
        self.name = name
        self.handler = handler
        CFNotificationCenterAddObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(),
            { _, observer, _, _, _ in
                guard let observer else { return }
                let me = Unmanaged<DarwinNotificationObserver>.fromOpaque(observer).takeUnretainedValue()
                me.fire()
            },
            name as CFString,
            nil,
            .deliverImmediately
        )
    }

    private func fire() {
        // CF delivers Darwin notifications through the main run loop. Hop once more so the
        // handler runs outside the CF callout (it may remove observers or post again).
        DispatchQueue.main.async { [self] in handler() }
    }

    deinit {
        CFNotificationCenterRemoveObserver(
            CFNotificationCenterGetDarwinNotifyCenter(),
            Unmanaged.passUnretained(self).toOpaque(),
            CFNotificationName(name as CFString),
            nil
        )
    }
}
