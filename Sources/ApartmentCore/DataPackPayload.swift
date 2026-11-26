import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// A validated snapshot of the existing three-file format. It contains no personal backup.
public struct DataPackPayload: Sendable {
    public let catalog: Data
    public let locations: Data
    public let paths: Data
    private let decoded: LocalDataPack
    static let limits = ["catalog.json": 2_000_000, "locations.geojson": 64_000_000,
                         "paths.geojson": 16_000_000, "known-addresses.json": 2_000_000]
    public init(catalog: Data, locations: Data, paths: Data) throws {
        guard catalog.count <= Self.limits["catalog.json"]!, locations.count <= Self.limits["locations.geojson"]!,
              paths.count <= Self.limits["paths.geojson"]! else { throw CoreError("The selected map pack is too large.") }
        self.catalog = catalog; self.locations = locations; self.paths = paths
        let pack = try LocalDataPack.read(catalog: catalog, locations: locations, paths: paths)
        guard !pack.catalog.communities.isEmpty, !pack.places.isEmpty else {
            throw CoreError("Choose a complete private map pack with communities and mapped locations.")
        }
        decoded = pack
    }
    public func validated() throws -> LocalDataPack { decoded }
    public static func read(directory: URL) throws -> Self {
        try checkDirectory(directory)
        return try Self(catalog: boundedData(directory.appendingPathComponent("catalog.json"), maximum: limits["catalog.json"]!),
                        locations: boundedData(directory.appendingPathComponent("locations.geojson"), maximum: limits["locations.geojson"]!),
                        paths: boundedData(directory.appendingPathComponent("paths.geojson"), maximum: limits["paths.geojson"]!))
    }
    public static func readCatalogFile(_ url: URL) throws -> Data { try boundedData(url, maximum: limits["catalog.json"]!) }
    public static func readLocationsFile(_ url: URL) throws -> Data { try boundedData(url, maximum: limits["locations.geojson"]!) }
    public static func readPathsFile(_ url: URL) throws -> Data { try boundedData(url, maximum: limits["paths.geojson"]!) }
    static func checkDirectory(_ url: URL) throws {
        let values = try url.resourceValues(forKeys: [.isDirectoryKey, .isSymbolicLinkKey])
        guard values.isDirectory == true, values.isSymbolicLink != true else { throw CoreError("Choose a regular map-pack folder.") }
    }
    static func boundedData(_ url: URL, maximum: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size <= maximum else { throw CoreError("A map-pack file is missing, unsupported or too large.") }
        var bytes = Data()
        if size > 0 { bytes.reserveCapacity(size) }
        _ = try visitBoundedFile(url, maximum: maximum) { bytes.append($0) }
        return bytes
    }
    /// Memory-maps immutable verified generation files so large GeoJSON payloads stay clean instead of consuming dirty RAM.
    static func mappedBoundedData(_ url: URL, maximum: Int) throws -> Data {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size <= maximum else { throw CoreError("A map-pack file is missing, unsupported or too large.") }
        if size <= 4_000_000 { return try boundedData(url, maximum: maximum) }
        let mapped = try Data(contentsOf: url, options: .mappedIfSafe)
        guard mapped.count == size, mapped.count <= maximum else { throw CoreError("A map-pack file is missing, unsupported or too large.") }
        return mapped
    }
    static func visitBoundedFile(_ url: URL, maximum: Int, consume: (Data) throws -> Void) throws -> Int {
        let values = try url.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let size = values.fileSize, size <= maximum else { throw CoreError("A map-pack file is missing, unsupported or too large.") }
        #if canImport(Darwin) || canImport(Glibc)
        // Use POSIX read() into one reusable 64 KB buffer so hashing 200+ MB never leaks
        // thousands of autoreleased NSData objects from NSFileHandle.read(upToCount:).
        let fd = url.withUnsafeFileSystemRepresentation { ptr -> Int32 in
            guard let ptr else { return -1 }
            #if canImport(Darwin)
            return Darwin.open(ptr, O_RDONLY)
            #else
            return Glibc.open(ptr, O_RDONLY)
            #endif
        }
        guard fd >= 0 else { throw CoreError("A map-pack file could not be opened.") }
        defer {
            #if canImport(Darwin)
            Darwin.close(fd)
            #else
            Glibc.close(fd)
            #endif
        }
        let chunkSize = 65_536
        let buffer = UnsafeMutablePointer<UInt8>.allocate(capacity: chunkSize)
        defer { buffer.deallocate() }
        var count = 0
        while true {
            let remaining = maximum + 1 - count
            let toRead = min(chunkSize, remaining)
            #if canImport(Darwin)
            let n = Darwin.read(fd, buffer, toRead)
            #else
            let n = Glibc.read(fd, buffer, toRead)
            #endif
            if n < 0 {
                if errno == EINTR { continue }
                throw CoreError("A map-pack file could not be read.")
            }
            if n == 0 { break }
            count += n
            guard count <= maximum else { throw CoreError("A map-pack file is too large.") }
            try consume(Data(bytesNoCopy: buffer, count: n, deallocator: .none))
        }
        return count
        #else
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var count = 0
        while let chunk = try handle.read(upToCount: min(65_536, maximum + 1 - count)), !chunk.isEmpty {
            count += chunk.count
            guard count <= maximum else { throw CoreError("A map-pack file is too large.") }
            try consume(chunk)
        }
        return count
        #endif
    }
    var files: [String: Data] { ["catalog.json": catalog, "locations.geojson": locations, "paths.geojson": paths] }
}
