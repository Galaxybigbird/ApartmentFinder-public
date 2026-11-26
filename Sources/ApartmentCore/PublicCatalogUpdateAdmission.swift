import Foundation

/// A valid digest is not permission to import private fields or remote checked doors.
/// Validate raw keys before the shared, private-capable decoder can ignore them.
enum PublicCatalogUpdateAdmission {
    private static let catalogKeys: Set<String> = ["schemaVersion", "packVersion", "generatedAt", "scopeZIPs", "deliveryAreaExceptions", "sources", "communities", "coverage", "unitBuildingAssociations", "unitFloorReferences"]
    private static let communityKeys: Set<String> = ["id", "name", "address", "zip", "countyZIPs", "deliveryAddresses", "aliases", "sourceIDs", "researchStatus", "gapNotes", "latitude", "longitude", "publicEvidence", "digitalLocker"]
    private static let associationKeys: Set<String> = ["id", "communityID", "address", "unit", "building", "identifierKind", "sourceIDs", "referencePlaceID", "mapFloor", "sourceUnitID", "historicalLegalAlias", "historicalFloor"]
    private static let pointKeys: Set<String> = ["communityID", "address", "building", "unit", "floor", "kind", "verification", "sourceIDs", "notes", "identityUncertain", "referenceKind", "estimatedHorizontalUncertaintyM", "mapLabelOccurrenceID", "sourceUnitID", "referenceAddresses", "entranceSideEstimate"]
    private static let pathKeys: Set<String> = ["communityID", "fromNode", "toNode", "mode", "oneWay", "blocked", "stairs", "confirmed", "sourceIDs", "notes"]
    private static let estimateRequired: Set<String> = ["frontage", "workingAllowanceMeters", "uncertaintyCalibrated", "doorCoordinatesVerified", "entranceCoordinatesVerified", "physicalSignageVerified", "dwellingFloorEvidence", "entryLevelDescription", "sharedUnits", "evidenceNotes"]

    private static let pointRequired = ["communityID", "address", "building", "unit", "floor", "kind", "verification", "sourceIDs", "notes"]
    private static let pathRequired = ["communityID", "fromNode", "toNode", "mode", "oneWay", "blocked", "stairs", "confirmed", "sourceIDs", "notes"]
    private static let estimateAllowed = estimateRequired.union(["dwellingFloor", "sourceUnitID", "sourceDate"])
    private static let estimateRequiredList = Array(estimateRequired)
    private static let collectionAllowed: Set<String> = ["type", "schemaVersion", "packVersion", "features"]
    private static let collectionRequired = ["type", "schemaVersion", "packVersion", "features"]
    private static let featureAllowed: Set<String> = ["type", "id", "geometry", "properties"]
    private static let featureRequired = ["type", "id", "geometry", "properties"]
    private static let geometryAllowed: Set<String> = ["type", "coordinates"]
    private static let geometryRequired = ["type", "coordinates"]
    private static let coordinateAllowed: Set<String> = ["latitude", "longitude"]
    private static let coordinateRequired = ["latitude", "longitude"]

    static func validate(_ files: [String: Data]) throws {
        try withAutoreleasePool {
            let catalog = try object(JSONSerialization.jsonObject(with: files["public-catalog.json"]!), allowed: catalogKeys,
                                     required: ["schemaVersion", "packVersion", "generatedAt", "scopeZIPs", "sources", "communities", "coverage"])
            let sources = try objects(catalog["sources"])
            for source in sources {
                _ = try object(source, allowed: ["id", "title", "url", "retrievedAt"], required: ["id", "title", "url", "retrievedAt"])
                guard let value = source["url"] as? String, let url = URLComponents(string: value), url.scheme == "https",
                      url.host != nil, url.user == nil, url.password == nil,
                      !(url.queryItems ?? []).contains(where: { $0.name.range(of: "token|api.?key|password|secret|credential|signature|authorization", options: [.regularExpression, .caseInsensitive]) != nil }) else {
                    throw CoreError("Credential-bearing or unsupported public evidence URL.")
                }
            }
            let sourceIDs = Set(sources.compactMap { $0["id"] as? String })
            let coverage = try object(catalog["coverage"], allowed: ["inventoryComplete", "notes"], required: ["inventoryComplete", "notes"])
            guard PublicCatalogUpdateJSON.boolean(coverage["inventoryComplete"], equals: false) else {
                throw CoreError("The public feed cannot assert complete door coverage.")
            }
            for community in try objects(catalog["communities"]) {
                _ = try object(community, allowed: communityKeys, required: ["id", "name", "address", "zip", "sourceIDs", "researchStatus", "gapNotes"])
                let latitude = community["latitude"], longitude = community["longitude"]
                guard (latitude == nil) == (longitude == nil) else { throw CoreError("Incomplete public community coordinate.") }
                if let locker = community["digitalLocker"] { try digitalLocker(locker, sourceIDs: sourceIDs) }
                if let evidence = community["publicEvidence"] {
                    try publicEvidence(evidence, sourceIDs: sourceIDs, hasCoordinate: latitude != nil)
                }
            }
            for association in try optionalObjects(catalog["unitBuildingAssociations"]) {
                _ = try object(association, allowed: associationKeys, required: ["id", "communityID", "address", "unit", "building", "identifierKind", "sourceIDs"])
            }
            for floor in try optionalObjects(catalog["unitFloorReferences"]) {
                _ = try object(floor, allowed: ["id", "communityID", "address", "unit", "floor", "sourceIDs", "sourceRevision"], required: ["id", "communityID", "address", "unit", "floor", "sourceIDs"])
            }
            let exceptionKeys: Set<String> = ["communityID", "address", "postalZIP", "propertyAPN", "currentCountyAPN", "identitySourceID", "countySourceID", "boundarySourceID", "cadastralSourceID"]
            for exception in try optionalObjects(catalog["deliveryAreaExceptions"]) { _ = try object(exception, allowed: exceptionKeys, required: exceptionKeys) }
        }

        // Stream-validate GeoJSON collections via pure-Swift JSONDecoder without materializing a >1 GB NSDictionary DOM.
        let locationIDs: Set<String> = try withAutoreleasePool {
            try JSONDecoder().decode(ValidatedPointCollection.self, from: files["public-locations.geojson"]!).ids
        }
        let pathIDs: Set<String> = try withAutoreleasePool {
            try JSONDecoder().decode(ValidatedPathCollection.self, from: files["public-paths.geojson"]!).ids
        }
        guard locationIDs.isDisjoint(with: pathIDs) else {
            throw CoreError("Duplicate public feature identity.")
        }
    }

    private struct DynamicKey: CodingKey {
        var stringValue: String
        var intValue: Int?
        init?(stringValue: String) { self.stringValue = stringValue; self.intValue = nil }
        init?(intValue: Int) { self.stringValue = String(intValue); self.intValue = intValue }
        init(_ name: String) { self.stringValue = name; self.intValue = nil }
    }

    private static func checkKeys(_ container: KeyedDecodingContainer<DynamicKey>,
                                  allowed: Set<String>, required: [String]) throws {
        for key in container.allKeys {
            guard allowed.contains(key.stringValue) else {
                throw CoreError("Unexpected or missing public catalog fields.")
            }
        }
        for name in required {
            guard container.contains(DynamicKey(name)) else {
                throw CoreError("Unexpected or missing public catalog fields.")
            }
        }
    }

    private struct ValidatedPointCollection: Decodable {
        let ids: Set<String>
        init(from decoder: Decoder) throws {
            let root = try decoder.container(keyedBy: DynamicKey.self)
            try PublicCatalogUpdateAdmission.checkKeys(root, allowed: PublicCatalogUpdateAdmission.collectionAllowed,
                                                       required: PublicCatalogUpdateAdmission.collectionRequired)
            var features = try root.nestedUnkeyedContainer(forKey: DynamicKey("features"))
            var seen = Set<String>()
            if let count = features.count { seen.reserveCapacity(count) }
            let idKey = DynamicKey("id"), geometryKey = DynamicKey("geometry"), propertiesKey = DynamicKey("properties")
            let kindKey = DynamicKey("kind"), verificationKey = DynamicKey("verification")
            let estimateKey = DynamicKey("entranceSideEstimate"), frontageKey = DynamicKey("frontage")
            let estimateFlags = [
                DynamicKey("uncertaintyCalibrated"), DynamicKey("doorCoordinatesVerified"),
                DynamicKey("entranceCoordinatesVerified"), DynamicKey("physicalSignageVerified")
            ]
            while !features.isAtEnd {
                let feature = try features.nestedContainer(keyedBy: DynamicKey.self)
                try PublicCatalogUpdateAdmission.checkKeys(feature, allowed: PublicCatalogUpdateAdmission.featureAllowed,
                                                           required: PublicCatalogUpdateAdmission.featureRequired)
                guard let id = try? feature.decode(String.self, forKey: idKey),
                      seen.insert(id).inserted else {
                    throw CoreError("Duplicate public feature identity.")
                }
                let geometry = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: geometryKey)
                try PublicCatalogUpdateAdmission.checkKeys(geometry, allowed: PublicCatalogUpdateAdmission.geometryAllowed,
                                                           required: PublicCatalogUpdateAdmission.geometryRequired)
                let properties = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: propertiesKey)
                try PublicCatalogUpdateAdmission.checkKeys(properties, allowed: PublicCatalogUpdateAdmission.pointKeys,
                                                           required: PublicCatalogUpdateAdmission.pointRequired)
                guard let kind = try? properties.decode(String.self, forKey: kindKey),
                      kind == "building" || kind == "unitPosition",
                      let verification = try? properties.decode(String.self, forKey: verificationKey),
                      verification == "sourceMapped" || verification == "estimated" else {
                    throw CoreError("A public update cannot assert a checked door or entrance.")
                }
                if properties.contains(estimateKey), !(try properties.decodeNil(forKey: estimateKey)) {
                    let estimate = try properties.nestedContainer(keyedBy: DynamicKey.self, forKey: estimateKey)
                    try PublicCatalogUpdateAdmission.checkKeys(estimate, allowed: PublicCatalogUpdateAdmission.estimateAllowed,
                                                               required: PublicCatalogUpdateAdmission.estimateRequiredList)
                    for flag in estimateFlags {
                        guard (try? estimate.decode(Bool.self, forKey: flag)) == false else {
                            throw CoreError("Public entrance estimates must remain explicitly unverified.")
                        }
                    }
                    var frontage = try estimate.nestedUnkeyedContainer(forKey: frontageKey)
                    while !frontage.isAtEnd {
                        let coord = try frontage.nestedContainer(keyedBy: DynamicKey.self)
                        try PublicCatalogUpdateAdmission.checkKeys(coord, allowed: PublicCatalogUpdateAdmission.coordinateAllowed,
                                                                   required: PublicCatalogUpdateAdmission.coordinateRequired)
                    }
                }
            }
            ids = seen
        }
    }

    private struct ValidatedPathCollection: Decodable {
        let ids: Set<String>
        init(from decoder: Decoder) throws {
            let root = try decoder.container(keyedBy: DynamicKey.self)
            try PublicCatalogUpdateAdmission.checkKeys(root, allowed: PublicCatalogUpdateAdmission.collectionAllowed,
                                                       required: PublicCatalogUpdateAdmission.collectionRequired)
            var features = try root.nestedUnkeyedContainer(forKey: DynamicKey("features"))
            var seen = Set<String>()
            if let count = features.count { seen.reserveCapacity(count) }
            let idKey = DynamicKey("id"), geometryKey = DynamicKey("geometry"), propertiesKey = DynamicKey("properties")
            let confirmedKey = DynamicKey("confirmed")
            while !features.isAtEnd {
                let feature = try features.nestedContainer(keyedBy: DynamicKey.self)
                try PublicCatalogUpdateAdmission.checkKeys(feature, allowed: PublicCatalogUpdateAdmission.featureAllowed,
                                                           required: PublicCatalogUpdateAdmission.featureRequired)
                guard let id = try? feature.decode(String.self, forKey: idKey),
                      seen.insert(id).inserted else {
                    throw CoreError("Duplicate public feature identity.")
                }
                let geometry = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: geometryKey)
                try PublicCatalogUpdateAdmission.checkKeys(geometry, allowed: PublicCatalogUpdateAdmission.geometryAllowed,
                                                           required: PublicCatalogUpdateAdmission.geometryRequired)
                let properties = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: propertiesKey)
                try PublicCatalogUpdateAdmission.checkKeys(properties, allowed: PublicCatalogUpdateAdmission.pathKeys,
                                                           required: PublicCatalogUpdateAdmission.pathRequired)
                guard (try? properties.decode(Bool.self, forKey: confirmedKey)) == false else {
                    throw CoreError("A public update cannot assert a personally confirmed path.")
                }
            }
            ids = seen
        }
    }

    private static func digitalLocker(_ raw: Any, sourceIDs: Set<String>) throws {
        let value = try object(raw, allowed: ["status", "brand", "placementDescription", "sourceIDs", "checkedAt", "point"], required: ["status", "sourceIDs", "checkedAt"])
        if let point = value["point"] {
            _ = try object(point, allowed: ["longitude", "latitude", "locationSourceIDs", "precision"], required: ["longitude", "latitude", "locationSourceIDs", "precision"])
        }
        let evidence = try JSONDecoder().decode(DigitalLockerEvidence.self, from: JSONSerialization.data(withJSONObject: value))
        guard evidence.isValid(sourceIDs: sourceIDs) else { throw CoreError("Unsupported digital locker evidence.") }
    }

    private static func publicEvidence(_ raw: Any, sourceIDs: Set<String>, hasCoordinate: Bool) throws {
        let evidence = try object(raw, allowed: ["communityType", "areaScope", "addressSourceIDs", "coordinate", "singleResidentialBuilding", "addressCityDisagreement"], required: ["communityType", "areaScope", "addressSourceIDs"])
        guard let type = evidence["communityType"] as? String, ["apartment", "condominium", "seniorLiving"].contains(type),
              let scope = evidence["areaScope"] as? String, ["core", "edge", "nearbyOutsideFocus"].contains(scope) else {
            throw CoreError("Unsupported public property evidence.")
        }
        try references(evidence["addressSourceIDs"], sourceIDs: sourceIDs)
        if let disagreement = evidence["addressCityDisagreement"], !PublicCatalogUpdateJSON.boolean(disagreement) { throw CoreError("Invalid public address disagreement flag.") }
        if let rawBuilding = evidence["singleResidentialBuilding"] {
            let building = try object(rawBuilding, allowed: ["publishedStories", "publishedApartments", "sourceIDs"], required: ["publishedStories", "publishedApartments", "sourceIDs"])
            guard PublicCatalogUpdateJSON.integer(building["publishedStories"], minimum: 1, maximum: 100),
                  PublicCatalogUpdateJSON.integer(building["publishedApartments"], minimum: 1, maximum: 10_000) else { throw CoreError("Invalid published building configuration.") }
            try references(building["sourceIDs"], sourceIDs: sourceIDs)
        }
        if let rawCoordinate = evidence["coordinate"] {
            let proof = try object(rawCoordinate, allowed: ["kind", "sourceIDs"], required: ["kind", "sourceIDs"])
            let kinds = ["operatorPublishedPropertyPoint", "municipalBoundaryCentroid", "municipalBoundaryInteriorReference", "municipalBusinessLicensePropertyReference", "hudApproximatePropertyReference", "countyPublishedAddressPropertyReference", "countyTestAddressPropertyReference"]
            guard hasCoordinate, let kind = proof["kind"] as? String, kinds.contains(kind) else { throw CoreError("Unsupported public community coordinate evidence.") }
            try references(proof["sourceIDs"], sourceIDs: sourceIDs)
        } else if hasCoordinate { throw CoreError("Public property evidence omits coordinate provenance.") }
    }
    private static func references(_ raw: Any?, sourceIDs: Set<String>) throws {
        guard let values = raw as? [String], !values.isEmpty, Set(values).count == values.count,
              Set(values).isSubset(of: sourceIDs) else { throw CoreError("Unresolved public evidence sources.") }
    }
    private static func object(_ raw: Any?, allowed: Set<String>, required: Set<String>) throws -> [String: Any] {
        guard let value = raw as? [String: Any], Set(value.keys).isSubset(of: allowed), required.isSubset(of: Set(value.keys)) else {
            throw CoreError("Unexpected or missing public catalog fields.")
        }
        return value
    }
    private static func objects(_ raw: Any?) throws -> [[String: Any]] {
        guard let values = raw as? [[String: Any]] else { throw CoreError("Invalid public catalog collection.") }
        return values
    }
    private static func optionalObjects(_ raw: Any?) throws -> [[String: Any]] {
        guard let raw, !(raw is NSNull) else { return [] }
        return try objects(raw)
    }
}
