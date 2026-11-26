import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif

/// Zero-DOM streaming scanner for GeoJSON FeatureCollection payloads.
/// Uses POSIX read() into a single reusable 64 KB buffer (zero ObjC NSFileHandle autorelease
/// overhead) and yields one raw JSON feature object at a time so peak memory stays < 1 MB
/// across 230+ MB GeoJSON files on iOS.
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
        #if canImport(Darwin) || canImport(Glibc)
        let fd = fileURL.withUnsafeFileSystemRepresentation { ptr -> Int32 in
            guard let ptr else { return -1 }
            return open(ptr, O_RDONLY)
        }
        guard fd >= 0 else { throw CoreError("Could not open GeoJSON feature collection.") }
        defer { close(fd) }
        var reader = ByteReader(fd: fd)
        return try scanCollection(&reader, onFeature: onFeature)
        #else
        let handle = try FileHandle(forReadingFrom: fileURL)
        defer { try? handle.close() }
        var reader = ByteReader(handle: handle)
        return try scanCollection(&reader, onFeature: onFeature)
        #endif
    }

    @discardableResult
    static func scan(data: Data, onFeature: (Data) throws -> Void) throws -> Header {
        var reader = ByteReader(data: data)
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
                        try featureBuffer.withUnsafeMutableBufferPointer { buf in
                            let slice = Data(bytesNoCopy: buf.baseAddress!, count: buf.count, deallocator: .none)
                            try withAutoreleasePool { try onFeature(slice) }
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
        private enum Source {
            #if canImport(Darwin) || canImport(Glibc)
            case fd(Int32)
            #else
            case file(FileHandle)
            #endif
            case data(Data, offset: Int)
        }

        private var source: Source
        private var chunk: [UInt8]
        private var validCount = 0
        private var index = 0
        private var eof = false

        #if canImport(Darwin) || canImport(Glibc)
        init(fd: Int32) {
            self.source = .fd(fd)
            self.chunk = [UInt8](repeating: 0, count: StreamingGeoJSON.chunkSize)
        }
        #else
        init(handle: FileHandle) {
            self.source = .file(handle)
            self.chunk = []
        }
        #endif

        init(data: Data) {
            self.source = .data(data, offset: 0)
            self.chunk = [UInt8](repeating: 0, count: min(data.count, StreamingGeoJSON.chunkSize))
        }

        private mutating func refill() throws {
            if eof { return }
            switch source {
            #if canImport(Darwin) || canImport(Glibc)
            case .fd(let fd):
                while true {
                    let n = chunk.withUnsafeMutableBytes { buf in
                        read(fd, buf.baseAddress!, StreamingGeoJSON.chunkSize)
                    }
                    if n < 0 {
                        if errno == EINTR { continue }
                        throw CoreError("Could not read GeoJSON feature collection.")
                    }
                    if n == 0 {
                        validCount = 0
                        index = 0
                        eof = true
                        return
                    }
                    validCount = n
                    index = 0
                    return
                }
            #else
            case .file(let handle):
                guard let next = try handle.read(upToCount: StreamingGeoJSON.chunkSize), !next.isEmpty else {
                    validCount = 0; index = 0; eof = true; return
                }
                chunk = Array(next); validCount = chunk.count; index = 0
            #endif
            case .data(let data, let offset):
                guard offset < data.count else {
                    validCount = 0; index = 0; eof = true; return
                }
                let count = min(data.count - offset, StreamingGeoJSON.chunkSize)
                if chunk.count < count { chunk = [UInt8](repeating: 0, count: count) }
                chunk.withUnsafeMutableBytes { dst in
                    data.copyBytes(to: dst.bindMemory(to: UInt8.self).baseAddress!, from: offset..<(offset + count))
                }
                validCount = count
                index = 0
                source = .data(data, offset: offset + count)
            }
        }

        mutating func peekByte() throws -> UInt8? {
            if index >= validCount {
                try refill()
                if index >= validCount { return nil }
            }
            return chunk[index]
        }

        mutating func readByte() throws -> UInt8? {
            if index >= validCount {
                try refill()
                if index >= validCount { return nil }
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
                if index >= validCount {
                    try refill()
                    guard index < validCount else {
                        throw CoreError("Truncated GeoJSON feature object.")
                    }
                }
                let start = index
                while index < validCount && depth > 0 {
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
