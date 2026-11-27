package dev.galaxybigbird.apartmentfinder.core

/**
 * Exact Kotlin port of Swift ApartmentRoutePlan, ApartmentDoorMatch, and NextApartmentRoute.
 */
object ApartmentRoutePlanner {

    fun buildPlan(
        stops: List<DeliveryStop>,
        communities: List<Community>,
        rules: List<ApartmentAddressRule> = emptyList(),
        unitCorrections: Map<String, String> = emptyMap(),
        personalCommunityIds: Set<String> = emptySet()
    ): ApartmentRoutePlan {
        val rulesByKey = LinkedHashMap<String, ApartmentAddressRule>()
        for (rule in rules) {
            rulesByKey[rule.key] = rule
            rulesByKey[LocalSearch.addressKey(rule.address)] = rule
        }

        val catalogByAddress = HashMap<String, MutableList<Community>>()
        val knownAddresses = LinkedHashSet<String>()
        for (c in communities) {
            for (addr in c.matchingAddresses) {
                knownAddresses.add(addr)
                val k = LocalSearch.addressKey(addr)
                if (k.isNotEmpty()) {
                    catalogByAddress.getOrPut(k) { mutableListOf() }.add(c)
                }
            }
        }
        for (r in rules) {
            knownAddresses.add(r.address)
            r.matchingAddress?.let { knownAddresses.add(it) }
        }

        val resolver = DeliveryAddress.Resolver(knownAddresses)
        val groupsMap = LinkedHashMap<String, MutableList<DeliveryStop>>()
        val groupMeta = LinkedHashMap<String, ApartmentGroup>()
        val unmatched = mutableListOf<DeliveryStop>()
        val excluded = mutableListOf<DeliveryStop>()

        val sortedStops = stops.sortedWith(compareBy<DeliveryStop>({ it.sequence }, { it.id }))
        for (original in sortedStops) {
            val resolved = resolver.resolve(original.address, original.unit)
            val addressKey = LocalSearch.addressKey(resolved.address)
            val rule = rulesByKey[addressKey]
            if (rule?.kind == AddressRuleKind.NON_APARTMENT) {
                excluded.add(original)
                continue
            }

            var canonical = rule?.address ?: resolved.address
            val originalMatches = catalogByAddress[addressKey].orEmpty()
            val canonicalMatches = catalogByAddress[LocalSearch.addressKey(canonical)].orEmpty()
            if (originalMatches.size == 1 && canonicalMatches.size == 1 &&
                canonicalMatches[0].id != originalMatches[0].id
            ) {
                canonical = resolved.address
            }
            val matches = catalogByAddress[LocalSearch.addressKey(canonical)].orEmpty()
            val personalId = "personal-apartment-${LocalSearch.addressKey(canonical)}"
            val preserveLegacyId = rule?.kind == AddressRuleKind.APARTMENT &&
                rule.communityId == null && personalCommunityIds.contains(personalId)

            var legacyCommunityId: String? = null
            val community: Community? = when {
                rule?.communityId != null -> {
                    val selected = communities.firstOrNull { it.id == rule.communityId }
                    if (selected != null && matches.size == 1 && matches[0].id != rule.communityId) {
                        legacyCommunityId = rule.communityId
                        matches[0]
                    } else {
                        selected
                    }
                }
                preserveLegacyId -> null
                matches.size == 1 -> matches[0]
                else -> null
            }

            if (community == null && rule?.kind != AddressRuleKind.APARTMENT) {
                unmatched.add(original)
                continue
            }

            val groupId = community?.id ?: rule?.communityId ?: personalId
            val directory = if (community == null && matches.size == 1) matches[0] else null
            if (community?.id == "alcove-6350") legacyCommunityId = "alcove"

            val correctedUnit = unitCorrections[original.id]
            val projectedStop = when {
                correctedUnit != null -> original.copy(address = resolved.address, unit = correctedUnit)
                !resolved.hasConflictingUnit -> original.copy(address = resolved.address, unit = resolved.unit)
                else -> original
            }

            if (groupId !in groupMeta) {
                val ruleName = rule?.name?.trim().orEmpty()
                groupMeta[groupId] = ApartmentGroup(
                    id = groupId,
                    name = ruleName.ifEmpty { community?.name ?: directory?.name ?: canonical },
                    address = community?.address ?: canonical,
                    coordinate = community?.coordinate ?: directory?.coordinate,
                    stops = emptyList(),
                    directoryCommunityId = directory?.id,
                    legacyCommunityId = legacyCommunityId
                )
                groupsMap[groupId] = mutableListOf()
            }
            groupsMap[groupId]!!.add(projectedStop)
        }

        val groups = groupMeta.values
            .map { meta -> meta.copy(stops = groupsMap[meta.id].orEmpty()) }
            .sortedWith(compareBy<ApartmentGroup>({ it.firstSequence }, { it.id }))

        return ApartmentRoutePlan(groups = groups, unmatched = unmatched, excluded = excluded)
    }

    fun resolveDoorMatch(
        stop: DeliveryStop,
        communityId: String,
        places: List<Place>,
        selectedPlaceId: String? = null,
        communityAddresses: List<String> = emptyList()
    ): ApartmentDoorMatch {
        val local = places.filter { it.communityId == communityId }
        val resolved = DeliveryAddress.resolve(
            stop.address,
            suppliedUnit = stop.unit,
            knownAddresses = (local.map { it.address } + communityAddresses).distinct()
        )
        if (resolved.hasConflictingUnit) {
            return ApartmentDoorMatch(issue = DoorMatchIssue.CHECK_UNIT)
        }
        val unitKey = LocalSearch.normalized(resolved.unit)
        if (unitKey.isEmpty()) {
            return ApartmentDoorMatch(issue = DoorMatchIssue.ADD_UNIT)
        }

        val addressKeys = local.map { LocalSearch.addressKey(it.address) }.filter { it.isNotEmpty() }.toSet()
        val aliasKeys = communityAddresses.map { LocalSearch.addressKey(it) }.filter { it.isNotEmpty() }.toSet()
        val stopAddressKey = LocalSearch.addressKey(resolved.address)
        val stopStreetNumber = stopAddressKey.split(' ').firstOrNull()

        fun streetMatches(place: Place): Boolean {
            val placeKey = LocalSearch.addressKey(place.address)
            if (placeKey == stopAddressKey || LocalSearch.sameStreetIgnoringDirectional(placeKey, stopAddressKey)) {
                return true
            }
            if (addressKeys.size <= 1 && placeKey in aliasKeys && stopAddressKey in aliasKeys &&
                stopStreetNumber != null && placeKey.split(' ').firstOrNull() == stopStreetNumber
            ) {
                return true
            }
            return false
        }

        val candidates = local.filter { place ->
            (place.kind == LocationKind.DOOR || place.kind == LocationKind.UNIT_POSITION) &&
                place.coordinate.isValid &&
                LocalSearch.normalized(place.unit) == unitKey &&
                (addressKeys.size <= 1 || streetMatches(place)) &&
                (place.entranceSideEstimate == null || streetMatches(place))
        }.sortedBy { it.id }

        val verified = candidates.filter { it.kind == LocationKind.DOOR && it.verification == Verification.FIELD_VERIFIED }
        if (selectedPlaceId != null) {
            val explicit = verified.firstOrNull { it.id == selectedPlaceId }
            if (explicit != null) {
                return ApartmentDoorMatch(candidates = candidates, readyDoor = explicit)
            }
        }

        if (verified.size == 1) {
            val checked = verified[0]
            val compatible = candidates.all { c ->
                if (c.verification == Verification.ESTIMATED && c.identityUncertain) return@all true
                (c.building.isEmpty() || LocalSearch.normalized(c.building) == LocalSearch.normalized(checked.building)) &&
                    (c.floor.isEmpty() || LocalSearch.normalized(c.floor) == LocalSearch.normalized(checked.floor))
            }
            if (compatible) {
                return ApartmentDoorMatch(candidates = candidates, readyDoor = checked)
            }
        }

        if (candidates.isEmpty()) {
            val bldgRefs = local.filter { it.kind == LocationKind.BUILDING && it.coordinate.isValid }
                .sortedBy { it.id }
            return ApartmentDoorMatch(
                candidates = emptyList(),
                issue = DoorMatchIssue.PLACE_PIN,
                buildingReferences = bldgRefs
            )
        }

        val distinctBuildings = candidates.map {
            "${LocalSearch.normalized(it.building)}|${LocalSearch.normalized(it.floor)}"
        }.toSet()
        val issue = if (candidates.size > 1 && (distinctBuildings.size > 1 || verified.size > 1)) {
            DoorMatchIssue.CHOOSE_DOOR
        } else {
            DoorMatchIssue.CONFIRM_PIN
        }
        return ApartmentDoorMatch(candidates = candidates, issue = issue)
    }

    data class NextApartmentChoice(
        val group: ApartmentGroup,
        val isNearestByGps: Boolean,
        val distanceMeters: Double?
    )

    fun selectNextApartment(
        groups: List<ApartmentGroup>,
        currentGroupId: String,
        fix: UserLocationFix?
    ): NextApartmentChoice? {
        val remaining = groups.filter { it.id != currentGroupId && it.remainingCount > 0 }
        if (remaining.isEmpty()) return null
        val freshFix = fix?.takeIf { it.isFresh() }
        if (freshFix != null) {
            val withCoords = remaining.mapNotNull { g ->
                g.coordinate?.takeIf { it.isValid }?.let { c ->
                    g to LocalSearch.haversineMeters(freshFix.coordinate, c)
                }
            }
            if (withCoords.size == remaining.size && withCoords.isNotEmpty()) {
                val best = withCoords.minBy { it.second }
                return NextApartmentChoice(best.first, isNearestByGps = true, distanceMeters = best.second)
            }
        }
        val nextManifest = remaining.first()
        val dist = freshFix?.let { f ->
            nextManifest.coordinate?.takeIf { it.isValid }?.let { LocalSearch.haversineMeters(f.coordinate, it) }
        }
        return NextApartmentChoice(nextManifest, isNearestByGps = false, distanceMeters = dist)
    }
}
