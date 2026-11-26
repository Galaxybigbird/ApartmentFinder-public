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
    private static let featureAllowed: Set<String> = ["type", "id", "geometry", "properties"]
    private static let featureRequired = ["type", "id", "geometry", "properties"]
    private static let geometryAllowed: Set<String> = ["type", "coordinates"]
    private static let geometryRequired = ["type", "coordinates"]
    private static let coordinateAllowed: Set<String> = ["latitude", "longitude"]
    private static let coordinateRequired = ["latitude", "longitude"]

    static func validateCatalog(_ data: Data) throws {
        try withAutoreleasePool {
            let catalog = try object(JSONSerialization.jsonObject(with: data), allowed: catalogKeys,
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
    }

    static func validatePointFeatureData(_ data: Data, decoder: JSONDecoder = JSONDecoder()) throws -> String {
        try decoder.decode(ValidatedPointFeature.self, from: data).id
    }

    /// Single-pass admission validation + Place decode (halves JSONDecoder overhead on 355k+ features).
    static func validateAndDecodePointFeature(_ data: Data, decoder: JSONDecoder = JSONDecoder()) throws -> Place {
        try decoder.decode(AdmittedPointFeature.self, from: data).place
    }

    static func validatePathFeatureData(_ data: Data, decoder: JSONDecoder = JSONDecoder()) throws -> String {
        try decoder.decode(ValidatedPathFeature.self, from: data).id
    }

    static func validatePaths(_ data: Data) throws -> Set<String> {
        let decoder = JSONDecoder()
        var ids = Set<String>()
        _ = try StreamingGeoJSON.scan(data: data) { featureData in
            let id = try validatePathFeatureData(featureData, decoder: decoder)
            guard ids.insert(id).inserted else {
                throw CoreError("Duplicate public feature identity.")
            }
        }
        return ids
    }

    static func validate(_ files: [String: Data]) throws {
        try validateCatalog(files["public-catalog.json"]!)
        let decoder = JSONDecoder()
        var locationIDs = Set<String>()
        _ = try StreamingGeoJSON.scan(data: files["public-locations.geojson"]!) { featureData in
            let id = try validatePointFeatureData(featureData, decoder: decoder)
            guard locationIDs.insert(id).inserted else {
                throw CoreError("Duplicate public feature identity.")
            }
        }
        let pathIDs = try validatePaths(files["public-paths.geojson"]!)
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

    private struct ValidatedPointFeature: Decodable {
        let id: String
        private static let idKey = DynamicKey("id")
        private static let geometryKey = DynamicKey("geometry")
        private static let propertiesKey = DynamicKey("properties")
        private static let kindKey = DynamicKey("kind")
        private static let verificationKey = DynamicKey("verification")
        private static let estimateKey = DynamicKey("entranceSideEstimate")
        private static let frontageKey = DynamicKey("frontage")
        private static let estimateFlags = [
            DynamicKey("uncertaintyCalibrated"), DynamicKey("doorCoordinatesVerified"),
            DynamicKey("entranceCoordinatesVerified"), DynamicKey("physicalSignageVerified")
        ]

        init(from decoder: Decoder) throws {
            let feature = try decoder.container(keyedBy: DynamicKey.self)
            try PublicCatalogUpdateAdmission.checkKeys(feature, allowed: PublicCatalogUpdateAdmission.featureAllowed,
                                                       required: PublicCatalogUpdateAdmission.featureRequired)
            guard let id = try? feature.decode(String.self, forKey: Self.idKey) else {
                throw CoreError("Duplicate public feature identity.")
            }
            let geometry = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.geometryKey)
            try PublicCatalogUpdateAdmission.checkKeys(geometry, allowed: PublicCatalogUpdateAdmission.geometryAllowed,
                                                       required: PublicCatalogUpdateAdmission.geometryRequired)
            let properties = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.propertiesKey)
            try PublicCatalogUpdateAdmission.checkKeys(properties, allowed: PublicCatalogUpdateAdmission.pointKeys,
                                                       required: PublicCatalogUpdateAdmission.pointRequired)
            guard let kind = try? properties.decode(String.self, forKey: Self.kindKey),
                  kind == "building" || kind == "unitPosition",
                  let verification = try? properties.decode(String.self, forKey: Self.verificationKey),
                  verification == "sourceMapped" || verification == "estimated" else {
                throw CoreError("A public update cannot assert a checked door or entrance.")
            }
            if properties.contains(Self.estimateKey), !(try properties.decodeNil(forKey: Self.estimateKey)) {
                let estimate = try properties.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.estimateKey)
                try PublicCatalogUpdateAdmission.checkKeys(estimate, allowed: PublicCatalogUpdateAdmission.estimateAllowed,
                                                           required: PublicCatalogUpdateAdmission.estimateRequiredList)
                for flag in Self.estimateFlags {
                    guard (try? estimate.decode(Bool.self, forKey: flag)) == false else {
                        throw CoreError("Public entrance estimates must remain explicitly unverified.")
                    }
                }
                var frontage = try estimate.nestedUnkeyedContainer(forKey: Self.frontageKey)
                while !frontage.isAtEnd {
                    let coord = try frontage.nestedContainer(keyedBy: DynamicKey.self)
                    try PublicCatalogUpdateAdmission.checkKeys(coord, allowed: PublicCatalogUpdateAdmission.coordinateAllowed,
                                                               required: PublicCatalogUpdateAdmission.coordinateRequired)
                }
            }
            self.id = id
        }
    }

    private struct AdmittedPointFeature: Decodable {
        let place: Place
        private static let typeKey = DynamicKey("type")
        private static let idKey = DynamicKey("id")
        private static let geometryKey = DynamicKey("geometry")
        private static let coordinatesKey = DynamicKey("coordinates")
        private static let propertiesKey = DynamicKey("properties")
        private static let communityIDKey = DynamicKey("communityID")
        private static let addressKey = DynamicKey("address")
        private static let buildingKey = DynamicKey("building")
        private static let unitKey = DynamicKey("unit")
        private static let floorKey = DynamicKey("floor")
        private static let kindKey = DynamicKey("kind")
        private static let verificationKey = DynamicKey("verification")
        private static let sourceIDsKey = DynamicKey("sourceIDs")
        private static let notesKey = DynamicKey("notes")
        private static let identityUncertainKey = DynamicKey("identityUncertain")
        private static let referenceKindKey = DynamicKey("referenceKind")
        private static let estimatedHorizontalUncertaintyMKey = DynamicKey("estimatedHorizontalUncertaintyM")
        private static let mapLabelOccurrenceIDKey = DynamicKey("mapLabelOccurrenceID")
        private static let sourceUnitIDKey = DynamicKey("sourceUnitID")
        private static let referenceAddressesKey = DynamicKey("referenceAddresses")
        private static let estimateKey = DynamicKey("entranceSideEstimate")
        private static let frontageKey = DynamicKey("frontage")
        private static let estimateFlags = [
            DynamicKey("uncertaintyCalibrated"), DynamicKey("doorCoordinatesVerified"),
            DynamicKey("entranceCoordinatesVerified"), DynamicKey("physicalSignageVerified")
        ]

        init(from decoder: Decoder) throws {
            let feature = try decoder.container(keyedBy: DynamicKey.self)
            try PublicCatalogUpdateAdmission.checkKeys(feature, allowed: PublicCatalogUpdateAdmission.featureAllowed,
                                                       required: PublicCatalogUpdateAdmission.featureRequired)
            let featureType = try feature.decode(String.self, forKey: Self.typeKey)
            guard let id = try? feature.decode(String.self, forKey: Self.idKey) else {
                throw CoreError("Duplicate public feature identity.")
            }
            let geometry = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.geometryKey)
            try PublicCatalogUpdateAdmission.checkKeys(geometry, allowed: PublicCatalogUpdateAdmission.geometryAllowed,
                                                       required: PublicCatalogUpdateAdmission.geometryRequired)
            let geomType = try geometry.decode(String.self, forKey: Self.typeKey)
            guard featureType == "Feature", geomType == "Point", !id.isEmpty else {
                throw CoreError("Invalid point feature.")
            }
            let coords = try geometry.decode([Double].self, forKey: Self.coordinatesKey)
            guard coords.count == 2 else { throw CoreError("Expected a longitude/latitude pair.") }
            let coordinate = Coordinate(latitude: coords[1], longitude: coords[0])
            guard coordinate.isValid else { throw CoreError("Invalid map coordinates.") }

            let properties = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.propertiesKey)
            try PublicCatalogUpdateAdmission.checkKeys(properties, allowed: PublicCatalogUpdateAdmission.pointKeys,
                                                       required: PublicCatalogUpdateAdmission.pointRequired)
            guard let kindRaw = try? properties.decode(String.self, forKey: Self.kindKey),
                  (kindRaw == "building" || kindRaw == "unitPosition"),
                  let kind = LocationKind(rawValue: kindRaw),
                  let verRaw = try? properties.decode(String.self, forKey: Self.verificationKey),
                  (verRaw == "sourceMapped" || verRaw == "estimated"),
                  let verification = Verification(rawValue: verRaw) else {
                throw CoreError("A public update cannot assert a checked door or entrance.")
            }

            var entranceSideEstimate: EntranceSideEstimate?
            if properties.contains(Self.estimateKey), !(try properties.decodeNil(forKey: Self.estimateKey)) {
                let estimate = try properties.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.estimateKey)
                try PublicCatalogUpdateAdmission.checkKeys(estimate, allowed: PublicCatalogUpdateAdmission.estimateAllowed,
                                                           required: PublicCatalogUpdateAdmission.estimateRequiredList)
                for flag in Self.estimateFlags {
                    guard (try? estimate.decode(Bool.self, forKey: flag)) == false else {
                        throw CoreError("Public entrance estimates must remain explicitly unverified.")
                    }
                }
                var frontage = try estimate.nestedUnkeyedContainer(forKey: Self.frontageKey)
                while !frontage.isAtEnd {
                    let coord = try frontage.nestedContainer(keyedBy: DynamicKey.self)
                    try PublicCatalogUpdateAdmission.checkKeys(coord, allowed: PublicCatalogUpdateAdmission.coordinateAllowed,
                                                               required: PublicCatalogUpdateAdmission.coordinateRequired)
                }
                entranceSideEstimate = try properties.decode(EntranceSideEstimate.self, forKey: Self.estimateKey)
            }

            place = Place(
                id: id,
                communityID: try properties.decode(String.self, forKey: Self.communityIDKey),
                address: try properties.decode(String.self, forKey: Self.addressKey),
                building: try properties.decode(String.self, forKey: Self.buildingKey),
                unit: try properties.decode(String.self, forKey: Self.unitKey),
                floor: try properties.decode(String.self, forKey: Self.floorKey),
                kind: kind,
                verification: verification,
                coordinate: coordinate,
                sourceIDs: try properties.decode([String].self, forKey: Self.sourceIDsKey),
                notes: try properties.decode(String.self, forKey: Self.notesKey),
                approachID: nil,
                graphNodeID: nil,
                identityUncertain: try properties.decodeIfPresent(Bool.self, forKey: Self.identityUncertainKey),
                referenceKind: try properties.decodeIfPresent(BuildingReferenceKind.self, forKey: Self.referenceKindKey),
                estimatedHorizontalUncertaintyM: try properties.decodeIfPresent(Double.self, forKey: Self.estimatedHorizontalUncertaintyMKey),
                mapLabelOccurrenceID: try properties.decodeIfPresent(String.self, forKey: Self.mapLabelOccurrenceIDKey),
                sourceUnitID: try properties.decodeIfPresent(String.self, forKey: Self.sourceUnitIDKey),
                referenceAddresses: try properties.decodeIfPresent([String].self, forKey: Self.referenceAddressesKey),
                entranceSideEstimate: entranceSideEstimate
            )
        }
    }

    private struct ValidatedPathFeature: Decodable {
        let id: String
        private static let idKey = DynamicKey("id")
        private static let geometryKey = DynamicKey("geometry")
        private static let propertiesKey = DynamicKey("properties")
        private static let confirmedKey = DynamicKey("confirmed")

        init(from decoder: Decoder) throws {
            let feature = try decoder.container(keyedBy: DynamicKey.self)
            try PublicCatalogUpdateAdmission.checkKeys(feature, allowed: PublicCatalogUpdateAdmission.featureAllowed,
                                                       required: PublicCatalogUpdateAdmission.featureRequired)
            guard let id = try? feature.decode(String.self, forKey: Self.idKey) else {
                throw CoreError("Duplicate public feature identity.")
            }
            let geometry = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.geometryKey)
            try PublicCatalogUpdateAdmission.checkKeys(geometry, allowed: PublicCatalogUpdateAdmission.geometryAllowed,
                                                       required: PublicCatalogUpdateAdmission.geometryRequired)
            let properties = try feature.nestedContainer(keyedBy: DynamicKey.self, forKey: Self.propertiesKey)
            try PublicCatalogUpdateAdmission.checkKeys(properties, allowed: PublicCatalogUpdateAdmission.pathKeys,
                                                       required: PublicCatalogUpdateAdmission.pathRequired)
            guard (try? properties.decode(Bool.self, forKey: Self.confirmedKey)) == false else {
                throw CoreError("A public update cannot assert a personally confirmed path.")
            }
            self.id = id
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
