import SwiftUI

extension Color {
    /// Single accent for selection and emphasis, deliberately the *system* accent
    /// rather than a fixed hue: macOS users expect selection to follow their
    /// System Settings choice, and a hardcoded indigo reads as a generic app
    /// default instead of a Mac app.
    static let brand = Color.accentColor
}

/// One corner-radius scale for the whole app (tokens: radius.card / radius.control).
/// Status badges are capsules and the three-way mode selectors are circles; everything
/// else uses these two values.
enum Radius {
    static let card: CGFloat = 16
    static let control: CGFloat = 10
}

/// Flat card: uniform fill and a hairline edge. No material, gradient or shadow.
struct CardSurface: ViewModifier {
    var cornerRadius: CGFloat = Radius.card

    func body(content: Content) -> some View {
        content
            .background(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).fill(Surface.card))
            .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(Surface.cardEdge, lineWidth: 1))
    }
}

extension View {
    func cardSurface(cornerRadius: CGFloat = Radius.card) -> some View {
        modifier(CardSurface(cornerRadius: cornerRadius))
    }
}

/// The selection treatment shared by every pick-one control (mode selectors, EQ chips,
/// the menu bar panel): selected is a solid accent fill, unselected a quiet neutral one.
/// Keeping it in one place stops the panel and the window drifting apart visually.
struct ControlSurface<S: InsettableShape>: ViewModifier {
    let shape: S
    let isSelected: Bool

    func body(content: Content) -> some View {
        content
            .background(shape.fill(isSelected ? AnyShapeStyle(Color.brand) : AnyShapeStyle(Surface.well)))
            .overlay(shape.strokeBorder(isSelected ? Color.clear : Surface.wellEdge, lineWidth: 1))
    }
}

extension View {
    func controlSurface<S: InsettableShape>(_ shape: S, isSelected: Bool) -> some View {
        modifier(ControlSurface(shape: shape, isSelected: isSelected))
    }
}

/// A rounded flat card container. Reserved for groups that genuinely deserve their
/// own elevated surface: related controls that are grouped inside a card use
/// `CardSection` instead, so secondary settings don't each spawn a panel and flatten
/// the hierarchy.
struct Card<Content: View>: View {
    let title: String?
    let icon: String?
    @ViewBuilder let content: Content

    init(_ title: String? = nil, icon: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.icon = icon
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let title {
                SectionLabel(title: title, icon: icon, font: .headline, prominent: true)
            }
            content
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .cardSurface(cornerRadius: Radius.card)
        .hoverLift()
    }
}

/// Heading for a card or a section. The glyph is not decoration: with several
/// groups on one surface it is what lets you find the one you want without
/// reading every label.
struct SectionLabel: View {
    let title: String
    let icon: String?
    var font: Font = .subheadline.weight(.semibold)
    /// Card headings are the top of the hierarchy and take the primary colour;
    /// sections nested inside a card step down to secondary.
    var prominent: Bool = false

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                Image(systemName: icon)
                    .font(.caption)
                    .foregroundStyle(Color.brand)
                    .frame(width: 14)
            }
            Text(LocalizedStringKey(title))
                .font(font)
                .foregroundStyle(prominent ? .primary : .secondary)
        }
    }
}

/// A labeled group of controls *inside* a card. Lets several related settings share
/// one surface, which is what keeps the dashboard from being a stack of identical
/// panels with no visual ranking.
struct CardSection<Content: View>: View {
    let title: String
    let icon: String?
    @ViewBuilder let content: Content

    init(_ title: String, icon: String? = nil, @ViewBuilder content: () -> Content) {
        self.title = title
        self.icon = icon
        self.content = content()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            SectionLabel(title: title, icon: icon)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Shown inside a card when the headphones didn't answer the initial state query for
/// this feature. Controls below it still work (writes are independent of reads).
struct StateNotReportedBanner: View {
    var body: some View {
        Label("Current state not reported. Controls below still work.", systemImage: "info.circle")
            .font(.caption2)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct LoadingRow: View {
    var body: some View {
        HStack {
            Spacer()
            ProgressView()
                .controlSize(.small)
            Spacer()
        }
        .padding(.vertical, 10)
    }
}


/// A settings row: label left, control right, the way System Settings lays one out.
/// Controls previously sat immediately after their label with the rest of the row
/// empty, which read as unfinished rather than deliberate.
struct SettingsRow<Control: View>: View {
    let label: String
    @ViewBuilder let control: Control

    init(_ label: String, @ViewBuilder control: () -> Control) {
        self.label = label
        self.control = control()
    }

    var body: some View {
        HStack(spacing: 12) {
            Text(LocalizedStringKey(label))
            Spacer(minLength: 12)
            control
        }
        .frame(minHeight: 22)
    }
}
