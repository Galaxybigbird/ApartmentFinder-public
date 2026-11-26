import Foundation
#if canImport(SQLite3)
import SQLite3
#endif

/// Indexed SQLite store for public catalog Place records so 355k+ pins never
/// live simultaneously in Swift heap memory on iOS.
public final class PublicPlaceStore: @unchecked Sendable {
    public let url: URL
    public let locationsSHA256: String
    public let communityCounts: [String: Int]
    public let totalCount: Int
    public let unitPositionCount: Int

    #if canImport(SQLite3)
    private var db: OpaquePointer?
    private let lock = NSLock()
    private static let sqliteTransient = unsafeBitCast(OpaquePointer(bitPattern: -1), to: sqlite3_destructor_type.self)
    #endif

    private struct PlaceExtras: Codable {
        var approachID: String?
        var graphNodeID: String?
        var identityUncertain: Bool?
        var referenceKind: BuildingReferenceKind?
        var estimatedHorizontalUncertaintyM: Double?
        var mapLabelOccurrenceID: String?
        var sourceUnitID: String?
        var referenceAddresses: [String]?
        var entranceSideEstimate: EntranceSideEstimate?

        init?(from place: Place) {
            guard place.approachID != nil || place.graphNodeID != nil || place.identityUncertain != nil ||
                    place.referenceKind != nil || place.estimatedHorizontalUncertaintyM != nil ||
                    place.mapLabelOccurrenceID != nil || place.sourceUnitID != nil ||
                    place.referenceAddresses != nil || place.entranceSideEstimate != nil else {
                return nil
            }
            approachID = place.approachID
            graphNodeID = place.graphNodeID
            identityUncertain = place.identityUncertain
            referenceKind = place.referenceKind
            estimatedHorizontalUncertaintyM = place.estimatedHorizontalUncertaintyM
            mapLabelOccurrenceID = place.mapLabelOccurrenceID
            sourceUnitID = place.sourceUnitID
            referenceAddresses = place.referenceAddresses
            entranceSideEstimate = place.entranceSideEstimate
        }
    }

    public init(url: URL, expectedLocationsSHA256: String) throws {
        #if canImport(SQLite3)
        self.url = url
        var handle: OpaquePointer?
        let flags = SQLITE_OPEN_READONLY | SQLITE_OPEN_FULLMUTEX
        guard sqlite3_open_v2(url.path, &handle, flags, nil) == SQLITE_OK, let handle else {
            if let handle { sqlite3_close_v2(handle) }
            throw CoreError("Could not open public pin index.")
        }
        self.db = handle
        // Cap SQLite page cache to ~2 MB so idle RAM stays flat on iOS.
        sqlite3_exec(handle, "PRAGMA cache_size = -2000; PRAGMA temp_store = MEMORY;", nil, nil, nil)

        var meta: [String: String] = [:]
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT key, value FROM meta", -1, &stmt, nil) == SQLITE_OK, let stmt else {
            sqlite3_close_v2(handle)
            self.db = nil
            throw CoreError("Public pin index metadata is unreadable.")
        }
        defer { sqlite3_finalize(stmt) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let k = sqlite3_column_text(stmt, 0), let v = sqlite3_column_text(stmt, 1) {
                meta[String(cString: k)] = String(cString: v)
            }
        }
        guard meta["schema_version"] == "1",
              meta["locations_sha256"] == expectedLocationsSHA256,
              let total = meta["total_count"].flatMap(Int.init),
              let units = meta["unit_position_count"].flatMap(Int.init) else {
            sqlite3_close_v2(handle)
            self.db = nil
            throw CoreError("Public pin index does not match its verified catalog.")
        }
        self.locationsSHA256 = expectedLocationsSHA256
        self.totalCount = total
        self.unitPositionCount = units

        var counts: [String: Int] = [:]
        var cStmt: OpaquePointer?
        guard sqlite3_prepare_v2(handle, "SELECT community_id, total_count FROM community_counts", -1, &cStmt, nil) == SQLITE_OK, let cStmt else {
            sqlite3_close_v2(handle)
            self.db = nil
            throw CoreError("Public pin index counts are unreadable.")
        }
        defer { sqlite3_finalize(cStmt) }
        while sqlite3_step(cStmt) == SQLITE_ROW {
            if let cid = sqlite3_column_text(cStmt, 0) {
                counts[String(cString: cid)] = Int(sqlite3_column_int64(cStmt, 1))
            }
        }
        self.communityCounts = counts
        #else
        throw CoreError("SQLite is required for indexed public pin storage.")
        #endif
    }

    deinit {
        #if canImport(SQLite3)
        lock.lock()
        if let db { sqlite3_close_v2(db); self.db = nil }
        lock.unlock()
        #endif
    }

    public func place(id: String) -> Place? {
        #if canImport(SQLite3)
        lock.lock(); defer { lock.unlock() }
        guard let db else { return nil }
        let sql = "SELECT id, community_id, address, building, unit, floor, kind, verification, lat, lon, source_ids, notes, extras FROM places WHERE id = ? LIMIT 1"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return nil }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, id, -1, Self.sqliteTransient)
        let decoder = JSONDecoder()
        guard sqlite3_step(stmt) == SQLITE_ROW else { return nil }
        return Self.decodeRow(stmt, decoder: decoder)
        #else
        return nil
        #endif
    }

    public func places(in communityID: String) -> [Place] {
        #if canImport(SQLite3)
        lock.lock(); defer { lock.unlock() }
        guard let db else { return [] }
        let sql = "SELECT id, community_id, address, building, unit, floor, kind, verification, lat, lon, source_ids, notes, extras FROM places WHERE community_id = ? ORDER BY id"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return [] }
        defer { sqlite3_finalize(stmt) }
        sqlite3_bind_text(stmt, 1, communityID, -1, Self.sqliteTransient)
        let decoder = JSONDecoder()
        var results: [Place] = []
        if let expected = communityCounts[communityID] { results.reserveCapacity(expected) }
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let place = Self.decodeRow(stmt, decoder: decoder) { results.append(place) }
        }
        return results
        #else
        return []
        #endif
    }

    public func places(in communityIDs: Set<String>) -> [Place] {
        guard !communityIDs.isEmpty else { return [] }
        var out: [Place] = []
        for id in communityIDs.sorted() {
            out.append(contentsOf: places(in: id))
        }
        return out
    }

    public func places(matchingAddressKeys keys: Set<String>) -> [Place] {
        #if canImport(SQLite3)
        guard !keys.isEmpty else { return [] }
        lock.lock(); defer { lock.unlock() }
        guard let db else { return [] }
        let sql = "SELECT id, community_id, address, building, unit, floor, kind, verification, lat, lon, source_ids, notes, extras FROM places WHERE address_key = ?"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return [] }
        defer { sqlite3_finalize(stmt) }
        let decoder = JSONDecoder()
        var results: [Place] = []
        for key in keys.sorted() {
            sqlite3_reset(stmt)
            sqlite3_clear_bindings(stmt)
            sqlite3_bind_text(stmt, 1, key, -1, Self.sqliteTransient)
            while sqlite3_step(stmt) == SQLITE_ROW {
                if let place = Self.decodeRow(stmt, decoder: decoder) { results.append(place) }
            }
        }
        return results.sorted { $0.id < $1.id }
        #else
        return []
        #endif
    }

    public func allPlaces() -> [Place] {
        #if canImport(SQLite3)
        lock.lock(); defer { lock.unlock() }
        guard let db else { return [] }
        let sql = "SELECT id, community_id, address, building, unit, floor, kind, verification, lat, lon, source_ids, notes, extras FROM places ORDER BY id"
        var stmt: OpaquePointer?
        guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else { return [] }
        defer { sqlite3_finalize(stmt) }
        let decoder = JSONDecoder()
        var results: [Place] = []
        results.reserveCapacity(totalCount)
        while sqlite3_step(stmt) == SQLITE_ROW {
            if let place = Self.decodeRow(stmt, decoder: decoder) { results.append(place) }
        }
        return results
        #else
        return []
        #endif
    }

    #if canImport(SQLite3)
    private static func columnString(_ stmt: OpaquePointer, _ index: Int32) -> String {
        guard let ptr = sqlite3_column_text(stmt, index) else { return "" }
        return String(cString: ptr)
    }

    private static func decodeRow(_ stmt: OpaquePointer, decoder: JSONDecoder) -> Place? {
        let id = columnString(stmt, 0)
        let communityID = columnString(stmt, 1)
        let address = columnString(stmt, 2)
        let building = columnString(stmt, 3)
        let unit = columnString(stmt, 4)
        let floor = columnString(stmt, 5)
        guard let kind = LocationKind(rawValue: columnString(stmt, 6)),
              let verification = Verification(rawValue: columnString(stmt, 7)) else { return nil }
        let lat = sqlite3_column_double(stmt, 8)
        let lon = sqlite3_column_double(stmt, 9)
        let rawSources = columnString(stmt, 10)
        let sourceIDs: [String]
        if rawSources.isEmpty {
            sourceIDs = []
        } else if rawSources.hasPrefix("["), let data = rawSources.data(using: .utf8),
                  let decoded = try? decoder.decode([String].self, from: data) {
            sourceIDs = decoded
        } else {
            sourceIDs = [rawSources]
        }
        let notes = columnString(stmt, 11)
        var extras: PlaceExtras?
        if sqlite3_column_type(stmt, 12) == SQLITE_BLOB,
           let blob = sqlite3_column_blob(stmt, 12) {
            let count = Int(sqlite3_column_bytes(stmt, 12))
            if count > 0 {
                let data = Data(bytes: blob, count: count)
                extras = try? decoder.decode(PlaceExtras.self, from: data)
            }
        }
        return Place(
            id: id,
            communityID: communityID,
            address: address,
            building: building,
            unit: unit,
            floor: floor,
            kind: kind,
            verification: verification,
            coordinate: Coordinate(latitude: lat, longitude: lon),
            sourceIDs: sourceIDs,
            notes: notes,
            approachID: extras?.approachID,
            graphNodeID: extras?.graphNodeID,
            identityUncertain: extras?.identityUncertain,
            referenceKind: extras?.referenceKind,
            estimatedHorizontalUncertaintyM: extras?.estimatedHorizontalUncertaintyM,
            mapLabelOccurrenceID: extras?.mapLabelOccurrenceID,
            sourceUnitID: extras?.sourceUnitID,
            referenceAddresses: extras?.referenceAddresses,
            entranceSideEstimate: extras?.entranceSideEstimate
        )
    }
    #endif

    /// Streaming SQLite builder that writes one Place at a time inside a single transaction
    /// and enforces unique IDs via PRIMARY KEY with ~4 MB peak RAM.
    final class Writer {
        private let tempURL: URL
        private let destinationURL: URL
        private let locationsSHA256: String
        private let packVersion: String
        #if canImport(SQLite3)
        private var db: OpaquePointer?
        private var insertStmt: OpaquePointer?
        #endif
        private let encoder = JSONEncoder()
        private var totalCount = 0
        private var unitPositionCount = 0
        private var communityCounts: [String: (total: Int, units: Int)] = [:]
        private var finished = false

        init(destinationURL: URL, locationsSHA256: String, packVersion: String) throws {
            #if canImport(SQLite3)
            self.destinationURL = destinationURL
            self.locationsSHA256 = locationsSHA256
            self.packVersion = packVersion
            self.tempURL = destinationURL.deletingLastPathComponent()
                .appendingPathComponent(".\(destinationURL.lastPathComponent).\(UUID().uuidString).tmp")
            var handle: OpaquePointer?
            let flags = SQLITE_OPEN_CREATE | SQLITE_OPEN_READWRITE | SQLITE_OPEN_NOMUTEX
            guard sqlite3_open_v2(tempURL.path, &handle, flags, nil) == SQLITE_OK, let handle else {
                if let handle { sqlite3_close_v2(handle) }
                try? FileManager.default.removeItem(at: tempURL)
                throw CoreError("Could not create public pin index.")
            }
            self.db = handle
            let ddl = """
            PRAGMA journal_mode = OFF;
            PRAGMA synchronous = OFF;
            PRAGMA temp_store = FILE;
            PRAGMA cache_size = -2000;
            CREATE TABLE meta (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL);
            CREATE TABLE places (
                id TEXT PRIMARY KEY NOT NULL,
                community_id TEXT NOT NULL,
                address_key TEXT NOT NULL,
                address TEXT NOT NULL,
                building TEXT NOT NULL,
                unit TEXT NOT NULL,
                floor TEXT NOT NULL,
                kind TEXT NOT NULL,
                verification TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                source_ids TEXT NOT NULL,
                notes TEXT NOT NULL,
                extras BLOB
            );
            CREATE TABLE community_counts (
                community_id TEXT PRIMARY KEY NOT NULL,
                total_count INTEGER NOT NULL,
                unit_count INTEGER NOT NULL
            );
            BEGIN IMMEDIATE;
            """
            guard sqlite3_exec(handle, ddl, nil, nil, nil) == SQLITE_OK else {
                cleanup()
                throw CoreError("Could not initialize public pin index schema.")
            }
            let sql = "INSERT INTO places (id, community_id, address_key, address, building, unit, floor, kind, verification, lat, lon, source_ids, notes, extras) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(handle, sql, -1, &stmt, nil) == SQLITE_OK, let stmt else {
                cleanup()
                throw CoreError("Could not prepare public pin insert statement.")
            }
            self.insertStmt = stmt
            #else
            throw CoreError("SQLite is required for indexed public pin storage.")
            #endif
        }

        deinit {
            if !finished { cleanup() }
        }

        private func cleanup() {
            #if canImport(SQLite3)
            if let insertStmt { sqlite3_finalize(insertStmt); self.insertStmt = nil }
            if let db { sqlite3_close_v2(db); self.db = nil }
            try? FileManager.default.removeItem(at: tempURL)
            #endif
        }

        func insert(_ place: Place, addressKey: String) throws {
            #if canImport(SQLite3)
            guard let stmt = insertStmt else { throw CoreError("Public pin index writer is closed.") }
            sqlite3_reset(stmt)
            sqlite3_clear_bindings(stmt)
            sqlite3_bind_text(stmt, 1, place.id, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 2, place.communityID, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 3, addressKey, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 4, place.address, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 5, place.building, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 6, place.unit, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 7, place.floor, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 8, place.kind.rawValue, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 9, place.verification.rawValue, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_double(stmt, 10, place.coordinate.latitude)
            sqlite3_bind_double(stmt, 11, place.coordinate.longitude)

            let sourceText: String
            if place.sourceIDs.count == 1, let only = place.sourceIDs.first, !only.hasPrefix("[") {
                sourceText = only
            } else {
                let encoded = try encoder.encode(place.sourceIDs)
                sourceText = String(decoding: encoded, as: UTF8.self)
            }
            sqlite3_bind_text(stmt, 12, sourceText, -1, PublicPlaceStore.sqliteTransient)
            sqlite3_bind_text(stmt, 13, place.notes, -1, PublicPlaceStore.sqliteTransient)

            if let extras = PlaceExtras(from: place) {
                let blob = try encoder.encode(extras)
                blob.withUnsafeBytes { raw in
                    _ = sqlite3_bind_blob(stmt, 14, raw.baseAddress, Int32(raw.count), PublicPlaceStore.sqliteTransient)
                }
            } else {
                sqlite3_bind_null(stmt, 14)
            }

            let rc = sqlite3_step(stmt)
            if (rc & 0xFF) == SQLITE_CONSTRAINT {
                throw CoreError("Duplicate public feature identity.")
            }
            guard rc == SQLITE_DONE else {
                throw CoreError("Could not write public pin record.")
            }

            totalCount += 1
            let isUnit = place.kind == .unitPosition
            if isUnit { unitPositionCount += 1 }
            var bucket = communityCounts[place.communityID] ?? (0, 0)
            bucket.total += 1
            if isUnit { bucket.units += 1 }
            communityCounts[place.communityID] = bucket
            #endif
        }

        func finish() throws -> PublicPlaceStore {
            #if canImport(SQLite3)
            guard let db else { throw CoreError("Public pin index writer is closed.") }
            if let insertStmt { sqlite3_finalize(insertStmt); self.insertStmt = nil }

            var countStmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "INSERT INTO community_counts (community_id, total_count, unit_count) VALUES (?, ?, ?)", -1, &countStmt, nil) == SQLITE_OK, let countStmt else {
                cleanup(); throw CoreError("Could not store community counts.")
            }
            for (cid, counts) in communityCounts {
                sqlite3_reset(countStmt)
                sqlite3_bind_text(countStmt, 1, cid, -1, PublicPlaceStore.sqliteTransient)
                sqlite3_bind_int64(countStmt, 2, Int64(counts.total))
                sqlite3_bind_int64(countStmt, 3, Int64(counts.units))
                guard sqlite3_step(countStmt) == SQLITE_DONE else {
                    sqlite3_finalize(countStmt); cleanup(); throw CoreError("Could not insert community count.")
                }
            }
            sqlite3_finalize(countStmt)

            var metaStmt: OpaquePointer?
            guard sqlite3_prepare_v2(db, "INSERT INTO meta (key, value) VALUES (?, ?)", -1, &metaStmt, nil) == SQLITE_OK, let metaStmt else {
                cleanup(); throw CoreError("Could not store index metadata.")
            }
            let metaPairs = [
                ("schema_version", "1"),
                ("locations_sha256", locationsSHA256),
                ("pack_version", packVersion),
                ("total_count", String(totalCount)),
                ("unit_position_count", String(unitPositionCount))
            ]
            for (k, v) in metaPairs {
                sqlite3_reset(metaStmt)
                sqlite3_bind_text(metaStmt, 1, k, -1, PublicPlaceStore.sqliteTransient)
                sqlite3_bind_text(metaStmt, 2, v, -1, PublicPlaceStore.sqliteTransient)
                guard sqlite3_step(metaStmt) == SQLITE_DONE else {
                    sqlite3_finalize(metaStmt); cleanup(); throw CoreError("Could not insert index metadata.")
                }
            }
            sqlite3_finalize(metaStmt)

            let finishSQL = """
            CREATE INDEX idx_places_community ON places(community_id);
            CREATE INDEX idx_places_address ON places(address_key);
            COMMIT;
            """
            guard sqlite3_exec(db, finishSQL, nil, nil, nil) == SQLITE_OK else {
                cleanup(); throw CoreError("Could not finalize public pin indexes.")
            }
            sqlite3_close_v2(db)
            self.db = nil

            #if os(iOS)
            try? FileManager.default.setAttributes(
                [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
                ofItemAtPath: tempURL.path
            )
            #endif
            if FileManager.default.fileExists(atPath: destinationURL.path) {
                _ = try FileManager.default.replaceItemAt(destinationURL, withItemAt: tempURL)
            } else {
                try FileManager.default.moveItem(at: tempURL, to: destinationURL)
            }
            finished = true
            return try PublicPlaceStore(url: destinationURL, expectedLocationsSHA256: locationsSHA256)
            #else
            throw CoreError("SQLite is required for indexed public pin storage.")
            #endif
        }
    }
}
