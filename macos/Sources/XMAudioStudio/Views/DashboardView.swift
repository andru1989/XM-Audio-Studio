import SwiftUI
import SonyHeadphonesKit

struct DashboardView: View {
    @EnvironmentObject private var controller: HeadphonesController
    @EnvironmentObject private var settings: AppSettings
    @Environment(\.openWindow) private var openWindow

    /// Above this width the cards sit in two columns. Below it they stack. A single
    /// column stretched to a wide window turns every card into a mostly-empty slab,
    /// which is what made the interface look barren when resized.
    private static let twoColumnWidth: CGFloat = 720
    /// Content stops growing past this, so a maximised window centres the layout
    /// instead of stretching controls apart.
    private static let maxContentWidth: CGFloat = 1000

    var body: some View {
        GeometryReader { geometry in
            let isWide = geometry.size.width >= Self.twoColumnWidth

            ScrollView {
                VStack(spacing: 14) {
                    if settings.isDemo {
                        Label("Demo mode: simulated headphones, invented data.", systemImage: "theatermasks")
                            .font(.caption)
                            .foregroundStyle(StudioColor.warning)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    HeaderView(horizontal: isWide)

                    if isWide {
                        HStack(alignment: .top, spacing: 14) {
                            VStack(spacing: 14) {
                                NoiseControlCard()
                                SoundCard()
                            }
                            VStack(spacing: 14) {
                                ConnectionCard()
                                BehaviorCard()
                            }
                        }
                    } else {
                        NoiseControlCard()
                        SoundCard()
                        ConnectionCard()
                        BehaviorCard()
                    }

                    footer
                }
                .frame(maxWidth: Self.maxContentWidth)
                .frame(maxWidth: .infinity)
                .padding(16)
            }
        }
    }

    /// One row: actions on the left, settings and protocol info on the right. Kept to a
    /// single line so the dashboard fits its default window height.
    private var footer: some View {
        HStack(spacing: 8) {
            Button {
                openWindow(id: "desktop-widget")
            } label: {
                Label("Widget", systemImage: "macwindow.on.rectangle")
            }
            .help("Open the floating desktop widget")

            Button {
                controller.refreshState()
            } label: {
                Label("Refresh", systemImage: "arrow.clockwise")
            }
            .help("Re-read every setting from the headphones")

            Button(role: .destructive) {
                controller.disconnect()
            } label: {
                Text("Disconnect")
            }

            SettingsLink {
                Label("Settings…", systemImage: "gearshape")
            }

            Spacer(minLength: 8)
            Text(LocalizedStringKey(footerText))
                .font(.caption2)
                .foregroundStyle(.tertiary)
                .lineLimit(1)
                .truncationMode(.middle)
        }
        .buttonStyle(.bordered)
        .controlSize(.small)
        .padding(.top, 2)
    }

    private var footerText: String {
        switch controller.protocolVersion {
        case .v2: return "Sony protocol v2"
        case .v1: return "Sony protocol v1 (some features may be limited)"
        case .unknown: return "Protocol version not identified"
        }
    }
}
