import SwiftUI
import AppKit
import SonyHeadphonesKit

/// Reports whether the window hosting a control surface can actually be seen, so the
/// controller holds the headphones' control channel only while someone can use it.
///
/// Design (replaces the original KVO observer from Anneo22's PR, see ADR-0004):
/// - Uses documented AppKit notifications only: occlusion changes, miniaturize /
///   deminiaturize, will-close, and app hide / unhide. Occlusion also covers a window left
///   on another Space or fully covered, which previously held the channel forever.
/// - Evaluates synchronously when attached to a window, then once more on the next run
///   loop turn (window state settles after SwiftUI's first layout).
/// - In menu-bar-only mode the launch window is hidden synchronously on attach, so it
///   never flashes on screen.
struct ControlSurfaceObserver: NSViewRepresentable {
    let controller: HeadphonesController
    var hideInitialWindow: (() -> Bool)?

    func makeNSView(context: Context) -> SurfaceView {
        SurfaceView(report: { [weak controller] id, visible in
            controller?.setControlSurface(id, visible: visible)
        }, hideFirstAppearance: hideInitialWindow?() ?? false)
    }

    func updateNSView(_ nsView: SurfaceView, context: Context) {}

    static func dismantleNSView(_ nsView: SurfaceView, coordinator: ()) {
        nsView.detach()
    }

    final class SurfaceView: NSView {
        let id = UUID()
        private let report: (UUID, Bool) -> Void
        private var hideFirstAppearance: Bool
        private var tokens: [NSObjectProtocol] = []
        private var lastReported: Bool?

        init(report: @escaping (UUID, Bool) -> Void, hideFirstAppearance: Bool) {
            self.report = report
            self.hideFirstAppearance = hideFirstAppearance
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

        /// Whether the hosting window is on screen and not hidden, minimized or occluded.
        static func isVisible(_ window: NSWindow?, appHidden: Bool) -> Bool {
            guard let window, !appHidden else { return false }
            return window.isVisible && !window.isMiniaturized && window.occlusionState.contains(.visible)
        }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            removeObservers()
            guard let window, !ProbeMode.active else {
                publish(false)
                return
            }
            if hideFirstAppearance {
                hideFirstAppearance = false
                window.orderOut(nil)
            }
            let center = NotificationCenter.default
            let windowNames: [Notification.Name] = [
                NSWindow.didChangeOcclusionStateNotification,
                NSWindow.didMiniaturizeNotification,
                NSWindow.didDeminiaturizeNotification,
                NSWindow.didBecomeKeyNotification,
            ]
            for name in windowNames {
                tokens.append(center.addObserver(forName: name, object: window, queue: .main) { [weak self] _ in
                    self?.evaluate()
                })
            }
            tokens.append(center.addObserver(forName: NSWindow.willCloseNotification, object: window, queue: .main) { [weak self] _ in
                self?.publish(false)
            })
            for name in [NSApplication.didHideNotification, NSApplication.didUnhideNotification] {
                tokens.append(center.addObserver(forName: name, object: NSApp, queue: .main) { [weak self] _ in
                    self?.evaluate()
                })
            }
            evaluate()
            DispatchQueue.main.async { [weak self] in self?.evaluate() }
        }

        private func evaluate() {
            publish(Self.isVisible(window, appHidden: NSApp?.isHidden ?? false))
        }

        private func publish(_ visible: Bool) {
            guard visible != lastReported else { return }
            lastReported = visible
            report(id, visible)
        }

        private func removeObservers() {
            tokens.forEach(NotificationCenter.default.removeObserver)
            tokens.removeAll()
        }

        func detach() {
            removeObservers()
            publish(false)
        }

        deinit {
            tokens.forEach(NotificationCenter.default.removeObserver)
        }
    }
}
