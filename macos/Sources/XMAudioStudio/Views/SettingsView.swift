import SwiftUI
import SonyHeadphonesKit

/// The native Settings window (⌘,). App preferences live here rather than at the foot of
/// the dashboard, so the dashboard fits its default window without scrolling.
struct SettingsView: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var settings: AppSettings

    var body: some View {
        Form {
            Section("General") {
                Toggle("Show only in the menu bar", isOn: $settings.menuBarOnly)
                Text("Hides the Dock icon. The menu bar panel stays available, and you can reopen this window from there.")
                    .font(.caption).foregroundStyle(.secondary)
                Toggle("Open at login", isOn: $settings.openAtLogin)
                if let error = settings.loginItemError {
                    Text(error).font(.caption).foregroundStyle(StudioColor.warning)
                }
            }
            Section("Headphones") {
                Toggle("Release the headphones when I'm not using the app", isOn: $settings.releaseWhenIdle)
                Text("Releases the control connection after all windows and panels have been closed for a short time.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            Section("Developer") {
                Toggle("Debug log", isOn: $controller.protocolLoggingEnabled)
                Text("Write a hex transcript of every frame to protocol.log")
                    .font(.caption).foregroundStyle(.secondary)
                HStack {
                    Toggle("Demo mode (simulated headphones)", isOn: $settings.demoOnNextLaunch)
                    if settings.demoOnNextLaunch != settings.isDemo {
                        Spacer()
                        Button("Relaunch to apply") { settings.relaunch() }
                    }
                }
            }
            Section {
                Text("Independent project, not affiliated with Sony. Sony Sound Connect remains the official app for firmware and features not implemented here.")
                    .font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .formStyle(.grouped)
        .frame(width: 480)
        .fixedSize(horizontal: false, vertical: true)
    }
}
