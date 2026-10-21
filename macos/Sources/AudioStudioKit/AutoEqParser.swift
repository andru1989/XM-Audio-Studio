import Foundation

/// Parsers for AutoEq / Equalizer APO text formats. Kept in step with Kotlin `AutoEqParser`.
public enum AutoEqParser {
    public struct ParseError: Error, Equatable, CustomStringConvertible {
        public let description: String
    }

    public static let defaultShelfQ = 0.7071

    private static let preampPattern = try! NSRegularExpression(
        pattern: #"^\s*Preamp:\s*(-?\d+(?:\.\d+)?)\s*dB"#, options: [.caseInsensitive])
    private static let filterPattern = try! NSRegularExpression(
        pattern: #"^\s*Filter\s*\d*\s*:\s*(ON|OFF)\s+([A-Z]+)\s+Fc\s+(\d+(?:\.\d+)?)\s*Hz(?:\s+Gain\s+(-?\d+(?:\.\d+)?)\s*dB)?(?:\s+Q\s+(\d+(?:\.\d+)?))?"#,
        options: [.caseInsensitive])

    private static func group(_ m: NSTextCheckingResult, _ i: Int, in line: String) -> String? {
        let r = m.range(at: i)
        guard r.location != NSNotFound, let range = Range(r, in: line) else { return nil }
        return String(line[range])
    }

    /// Parses a `ParametricEQ.txt` (Preamp + `Filter N: ON PK Fc 100 Hz Gain -2.0 dB Q 0.70` lines).
    public static func parseParametric(_ text: String) throws -> ParametricProfile {
        var preamp = 0.0
        var filters: [ParametricFilter] = []
        for line in text.components(separatedBy: .newlines) {
            let full = NSRange(line.startIndex..., in: line)
            if let m = preampPattern.firstMatch(in: line, range: full), let v = group(m, 1, in: line).flatMap(Double.init) {
                preamp = v
                continue
            }
            guard let m = filterPattern.firstMatch(in: line, range: full) else { continue }
            if group(m, 1, in: line)?.uppercased() == "OFF" { continue }
            let code = group(m, 2, in: line)?.uppercased() ?? ""
            let type: FilterType
            switch code {
            case "PK", "PEQ": type = .peaking
            case "LS", "LSC", "LSQ": type = .lowShelf
            case "HS", "HSC", "HSQ": type = .highShelf
            default: throw ParseError(description: "Unsupported filter type '\(code)' in: \(line.trimmingCharacters(in: .whitespaces))")
            }
            let frequency = group(m, 3, in: line).flatMap(Double.init) ?? 0
            let gain = group(m, 4, in: line).flatMap(Double.init) ?? 0
            let q = group(m, 5, in: line).flatMap(Double.init) ?? defaultShelfQ
            guard frequency > 0, frequency < 24_000, q > 0 else {
                throw ParseError(description: "Out-of-range filter: \(line.trimmingCharacters(in: .whitespaces))")
            }
            filters.append(ParametricFilter(type: type, frequency: frequency, gainDb: gain, q: q))
        }
        guard !filters.isEmpty else { throw ParseError(description: "No enabled filters found") }
        return ParametricProfile(preampDb: preamp, filters: filters)
    }

    /// Parses `GraphicEQ: 20 -4.4; 21 -4.3; ...` into points sorted by frequency.
    public static func parseGraphic(_ text: String) throws -> [(frequency: Double, gainDb: Double)] {
        guard let range = text.range(of: "GraphicEQ:") else { throw ParseError(description: "Missing 'GraphicEQ:' prefix") }
        let body = text[range.upperBound...]
        let points: [(Double, Double)] = body.split(separator: ";").compactMap { entry in
            let parts = entry.split(whereSeparator: { $0.isWhitespace })
            guard parts.count == 2, let f = Double(parts[0]), let g = Double(parts[1]) else { return nil }
            return (f, g)
        }.sorted { $0.0 < $1.0 }
        guard points.count >= 2 else { throw ParseError(description: "GraphicEQ needs at least two points") }
        return points.map { (frequency: $0.0, gainDb: $0.1) }
    }

    /// Linear interpolation in log-frequency, clamped at the ends.
    public static func interpolate(_ points: [(frequency: Double, gainDb: Double)], at frequencies: [Double]) -> [Double] {
        frequencies.map { f in
            if f <= points[0].frequency { return points[0].gainDb }
            if f >= points[points.count - 1].frequency { return points[points.count - 1].gainDb }
            let hi = points.firstIndex { $0.frequency >= f }!
            let (f0, g0) = points[hi - 1]
            let (f1, g1) = points[hi]
            let t = (log10(f) - log10(f0)) / (log10(f1) - log10(f0))
            return g0 + (g1 - g0) * t
        }
    }
}
