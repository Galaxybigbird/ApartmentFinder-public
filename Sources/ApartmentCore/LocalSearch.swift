import Foundation
public enum LocalSearch {
    // A locality suffix needs a delimiter or state/ZIP context; city words can be street names.
    // Longer multi-word names (e.g. North Las Vegas) come before Las Vegas so "North" is never left dangling.
    static let localitySuffixPattern = #"(?i)(?:[,\.\r\n]+\s*(?:north\s+las\s+vegas|n\.?\s+las\s+vegas|las\s+vegas|henderson|boulder\s+city|blue\s+diamond|nellis\s+afb|spring\s+valley|sunrise\s+manor|enterprise|paradise|whitney|winchester|summerlin(?:\s+south)?)(?:[,\s.]+(?:nv|nevada))?(?:[,\s.]+\d{5}(?:-\d{4})?)?|[,\s.]+(?:north\s+las\s+vegas|n\.?\s+las\s+vegas|las\s+vegas|henderson|boulder\s+city|blue\s+diamond|nellis\s+afb|spring\s+valley|sunrise\s+manor|enterprise|paradise|whitney|winchester|summerlin(?:\s+south)?)[,\s.]+(?:(?:nv|nevada)(?:[,\s.]+\d{5}(?:-\d{4})?)?|\d{5}(?:-\d{4})?)|[,\s.]+(?:nv|nevada)(?:[,\s.]+\d{5}(?:-\d{4})?)?)[,\s.]*$"#
    private static let localitySuffixRegex = try! NSRegularExpression(pattern: localitySuffixPattern)
    private static let trailingPostalRegex = try! NSRegularExpression(pattern: #"\s+\d{5}(-\d{4})?$"#)
    private static let streetAliases = [
        "west":"w", "east":"e", "north":"n", "south":"s",
        "road":"rd", "street":"st", "avenue":"ave", "drive":"dr", "lane":"ln",
        "boulevard":"blvd", "court":"ct", "way":"wy", "parkway":"pkwy",
        "highway":"hwy", "circle":"cir", "place":"pl", "terrace":"ter",
        "trail":"trl", "point":"pt", "cove":"cv", "square":"sq"
    ]
    private static let cardinalDirections: Set<String> = ["n", "s", "e", "w"]
    private static let posixLocale = Locale(identifier: "en_US_POSIX")
    public static func normalized(_ value: String) -> String {
        value.folding(options: [.caseInsensitive, .diacriticInsensitive], locale: posixLocale)
            .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
    public static func addressKey(_ address: String, stripPostalCode: Bool = true) -> String {
        var text = normalized(address)
        let fullRange = NSRange(text.startIndex..<text.endIndex, in: text)
        text = localitySuffixRegex.stringByReplacingMatches(in: text, range: fullRange, withTemplate: "")
        if let comma = text.firstIndex(of: ",") { text = String(text[..<comma]) }
        if stripPostalCode {
            let postalRange = NSRange(text.startIndex..<text.endIndex, in: text)
            text = trailingPostalRegex.stringByReplacingMatches(in: text, range: postalRange, withTemplate: "")
        }
        return text.components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty }
            .map { streetAliases[$0] ?? $0 }.joined(separator: " ")
    }
    /// True when two normalized address keys refer to the exact same house number and street
    /// name, differing only by an omitted or repositioned cardinal directional (n/s/e/w), e.g.
    /// "7089 n aliante pkwy" == "7089 aliante pkwy" or "8445 las vegas blvd s" == "8445 s las vegas blvd".
    /// Strictly rejects different street numbers AND conflicting explicit directionals (e.g. E vs W).
    public static func sameStreetIgnoringDirectional(_ lhsKey: String, _ rhsKey: String) -> Bool {
        guard !lhsKey.isEmpty, !rhsKey.isEmpty else { return false }
        if lhsKey == rhsKey { return true }
        let a = lhsKey.split(separator: " ").map(String.init)
        let b = rhsKey.split(separator: " ").map(String.init)
        guard let firstA = a.first, let firstB = b.first, firstA == firstB,
              firstA.rangeOfCharacter(from: .decimalDigits) != nil else { return false }
        let dirsA = a.dropFirst().filter { cardinalDirections.contains($0) }
        let dirsB = b.dropFirst().filter { cardinalDirections.contains($0) }
        if !dirsA.isEmpty && !dirsB.isEmpty && Set(dirsA) != Set(dirsB) { return false }
        let coreA = a.dropFirst().filter { !cardinalDirections.contains($0) }
        let coreB = b.dropFirst().filter { !cardinalDirections.contains($0) }
        return !coreA.isEmpty && coreA == coreB
    }
    /// Removes cardinal directional tokens (n/s/e/w) after the house number for safe fallback indexing.
    public static func directionalStrippedKey(_ key: String) -> String? {
        let parts = key.split(separator: " ").map(String.init)
        guard let first = parts.first, first.rangeOfCharacter(from: .decimalDigits) != nil else { return nil }
        let core = parts.dropFirst().filter { !cardinalDirections.contains($0) }
        guard !core.isEmpty, core.count < parts.count - 1 else { return nil }
        return ([first] + core).joined(separator: " ")
    }
    public static func locations(address: String, unit: String, building: String = "", places: [Place]) -> [Place] {
        let parsed = DeliveryAddress.resolve(address, unit: unit, knownAddresses: Array(Set(places.map(\.address))))
        guard !parsed.hasConflictingUnit else { return [] }
        let key = addressKey(parsed.address), unitKey = normalized(parsed.unit), buildingKey = normalized(building)
        guard !key.isEmpty else { return [] }
        return places.filter {
            addressKey($0.address) == key && (unitKey.isEmpty || normalized($0.unit) == unitKey) &&
            (buildingKey.isEmpty || normalized($0.building) == buildingKey)
        }.sorted {
            if $0.verification != $1.verification {
                let priority: [Verification: Int] = [.fieldVerified: 0, .sourceMapped: 1, .estimated: 2]
                return priority[$0.verification, default: 2] < priority[$1.verification, default: 2]
            }
            return $0.id < $1.id
        }
    }
    public static func merged(seed: [Place], overrides: [Place]) -> [Place] {
        if overrides.isEmpty {
            var seen = Set<String>()
            seen.reserveCapacity(seed.count)
            var unique: [Place] = []
            unique.reserveCapacity(seed.count)
            for value in seed.reversed() where seen.insert(value.id).inserted {
                unique.append(value)
            }
            return unique.sorted { $0.id < $1.id }
        }
        var overrideMap: [String: Place] = [:]
        overrideMap.reserveCapacity(overrides.count)
        for value in overrides { overrideMap[value.id] = value }
        var seen = Set<String>()
        seen.reserveCapacity(seed.count + overrideMap.count)
        var result: [Place] = []
        result.reserveCapacity(seed.count + overrideMap.count)
        for value in seed.reversed() where seen.insert(value.id).inserted {
            result.append(overrideMap.removeValue(forKey: value.id) ?? value)
        }
        for value in overrideMap.values { result.append(value) }
        return result.sorted { $0.id < $1.id }
    }
}
