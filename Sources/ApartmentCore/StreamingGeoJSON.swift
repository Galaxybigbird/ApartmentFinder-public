import Foundation

/// Zero-DOM streaming scanner for GeoJSON FeatureCollection payloads.
/// Reads 64 KB chunks from disk (or an in-memory Data slice) and yields one
/// raw JSON feature object at a time so Foundation's JSONDecoder never builds
/// a multi-hundred-megabyte JSONMap over 200+ MB GeoJSON files on iOS.
enum StreamingGeoJSON {
    struct Header: Sendable {
        let type: String
        let schemaVersion: Int
        let packVersion: String
        let featureCount: Int
    }

    private static let chunkSize = 65_536
    private static let maxFeatureBytes = 262_144
    private static let maxScalarBytes = 4_096

    @discardableResult
    static func scan(fileURL: URL, onFeature: (Data) throws -> Void) throws -> Header {
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var reader = ByteReader(source: .file(handle))
        return try scanCollection(&reader, onFeature: onFeature)
    }

    @discardableResult
    static func scan(data: Data, onFeature: (Data) throws -> Void) throws -> Header {
        var reader = ByteReader(source: .data(data, offset: 0))
        return try scanCollection(&reader, onFeature: onFeature)
    }

    private static func scanCollection(_ reader: inout ByteReader, onFeature: (Data) throws -> Void) throws -> Header {
        try reader.skipWhitespace()
        guard try reader.readByte() == 0x7B else { // '{'
            throw CoreError("Invalid GeoJSON feature collection.")
        }
        var seenKeys = Set<String>()
        var collectionType: String?
        var schemaVersion: Int?
        var packVersion: String?
        var featureCount = 0
        var featureBuffer: [UInt8] = []
        featureBuffer.reserveCapacity(4_096)

        try reader.skipWhitespace()
        if try reader.peekByte() == 0x7D { // '}'
            throw CoreError("Unexpected or missing public catalog fields.")
        }

        while true {
            try reader.skipWhitespace()
            let key = try reader.readJSONString()
            guard seenKeys.insert(key).inserted else {
                throw CoreError("Unexpected or missing public catalog fields.")
            }
            try reader.skipWhitespace()
            guard try reader.readByte() == 0x3A else { // ':'
                throw CoreError("Invalid GeoJSON feature collection.")
            }
            try reader.skipWhitespace()

            switch key {
            case "type":
                collectionType = try reader.readJSONString()
            case "packVersion":
                packVersion = try reader.readJSONString()
            case "schemaVersion":
                schemaVersion = try reader.readJSONInt()
            case "features":
                guard try reader.readByte() == 0x5B else { // '['
                    throw CoreError("Invalid GeoJSON feature collection.")
                }
                try reader.skipWhitespace()
                if try reader.peekByte() == 0x5D { // ']'
                    _ = try reader.readByte()
                } else {
                    while true {
                        try reader.skipWhitespace()
                        featureBuffer.removeAll(keepingCapacity: true)
                        try reader.readJSONObject(into: &featureBuffer, maxBytes: maxFeatureBytes)
                        try withAutoreleasePool {
                            try onFeature(Data(featureBuffer))
                        }
                        featureCount += 1
                        try reader.skipWhitespace()
                        guard let sep = try reader.readByte() else {
                            throw CoreError("Truncated GeoJSON feature collection.")
                        }
                        if sep == 0x2C { // ','
                            continue
                        } else if sep == 0x5D { // ']'
                            break
                        } else {
                            throw CoreError("Invalid GeoJSON feature collection.")
                        }
                    }
                }
            default:
                throw CoreError("Unexpected or missing public catalog fields.")
            }

            try reader.skipWhitespace()
            guard let next = try reader.readByte() else {
                throw CoreError("Truncated GeoJSON feature collection.")
            }
            if next == 0x2C { // ','
                continue
            } else if next == 0x7D { // '}'
                break
            } else {
                throw CoreError("Invalid GeoJSON feature collection.")
            }
        }

        try reader.skipWhitespace()
        guard try reader.peekByte() == nil else {
            throw CoreError("Trailing data in GeoJSON feature collection.")
        }
        guard seenKeys == ["type", "schemaVersion", "packVersion", "features"],
              let collectionType, let schemaVersion, let packVersion else {
            throw CoreError("Unexpected or missing public catalog fields.")
        }
        return Header(type: collectionType, schemaVersion: schemaVersion, packVersion: packVersion, featureCount: featureCount)
    }

    private struct ByteReader {
        enum Source {
            case file(FileHandle)
            case data(Data, offset: Int)
        }

        private var source: Source
        private var chunk: [UInt8] = []
        private var index = 0
        private var eof = false

        init(source: Source) {
            self.source = source
        }

        private mutating func refill() throws {
            if eof { return }
            switch source {
            case .file(let handle):
                guard let next = try handle.read(upToCount: StreamingGeoJSON.chunkSize), !next.isEmpty else {
                    chunk.removeAll(keepingCapacity: false)
                    index = 0
                    eof = true
                    return
                }
                chunk = Array(next)
                index = 0
            case .data(let data, let offset):
                guard offset < data.count else {
                    chunk.removeAll(keepingCapacity: false)
                    index = 0
                    eof = true
                    return
                }
                let end = min(data.count, offset + StreamingGeoJSON.chunkSize)
                chunk = Array(data[offset..<end])
                index = 0
                source = .data(data, offset: end)
            }
        }

        mutating func peekByte() throws -> UInt8? {
            if index >= chunk.count {
                try refill()
                if index >= chunk.count { return nil }
            }
            return chunk[index]
        }

        mutating func readByte() throws -> UInt8? {
            if index >= chunk.count {
                try refill()
                if index >= chunk.count { return nil }
            }
            let b = chunk[index]
            index += 1
            return b
        }

        mutating func skipWhitespace() throws {
            while let b = try peekByte(), b == 0x20 || b == 0x09 || b == 0x0A || b == 0x0D {
                index += 1
            }
        }

        mutating func readJSONString() throws -> String {
            guard let first = try readByte(), first == 0x22 else { // '"'
                throw CoreError("Invalid GeoJSON string.")
            }
            var bytes: [UInt8] = [0x22]
            bytes.reserveCapacity(64)
            var escaped = false
            while let b = try readByte() {
                bytes.append(b)
                guard bytes.count <= StreamingGeoJSON.maxScalarBytes else {
                    throw CoreError("GeoJSON header string exceeds limit.")
                }
                if escaped {
                    escaped = false
                } else if b == 0x5C { // '\'
                    escaped = true
                } else if b == 0x22 { // '"'
                    return try JSONDecoder().decode(String.self, from: Data(bytes))
                }
            }
            throw CoreError("Unterminated GeoJSON string.")
        }

        mutating func readJSONInt() throws -> Int {
            var bytes: [UInt8] = []
            bytes.reserveCapacity(16)
            while let b = try peekByte() {
                if (0x30...0x39).contains(b) || (bytes.isEmpty && b == 0x2D) {
                    bytes.append(b)
                    index += 1
                    guard bytes.count <= 24 else { throw CoreError("Invalid GeoJSON integer.") }
                } else {
                    break
                }
            }
            guard !bytes.isEmpty, let str = String(bytes: bytes, encoding: .utf8), let val = Int(str) else {
                throw CoreError("Invalid GeoJSON integer.")
            }
            return val
        }

        mutating func readJSONObject(into buffer: inout [UInt8], maxBytes: Int) throws {
            guard let first = try readByte(), first == 0x7B else { // '{'
                throw CoreError("Expected GeoJSON feature object.")
            }
            buffer.append(first)
            var depth = 1
            var inString = false
            var escaped = false

            while depth > 0 {
                if index >= chunk.count {
                    try refill()
                    guard index < chunk.count else {
                        throw CoreError("Truncated GeoJSON feature object.")
                    }
                }
                let start = index
                while index < chunk.count && depth > 0 {
                    let b = chunk[index]
                    index += 1
                    if inString {
                        if escaped {
                            escaped = false
                        } else if b == 0x5C { // '\'
                            escaped = true
                        } else if b == 0x22 { // '"'
                            inString = false
                        }
                    } else {
                        if b == 0x22 { // '"'
                            inString = true
                        } else if b == 0x7B { // '{'
                            depth += 1
                        } else if b == 0x7D { // '}'
                            depth -= 1
                        }
                    }
                }
                let spanCount = index - start
                guard buffer.count + spanCount <= maxBytes else {
                    throw CoreError("GeoJSON feature exceeds size limit.")
                }
                buffer.append(contentsOf: chunk[start..<index])
            }
        }
    }
}
