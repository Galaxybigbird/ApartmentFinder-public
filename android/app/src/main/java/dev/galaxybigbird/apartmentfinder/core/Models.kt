package dev.galaxybigbird.apartmentfinder.core

import java.util.UUID
import kotlin.math.abs

data class Coordinate(
    val latitude: Double,
    val longitude: Double
) {
    val isValid: Boolean
        get() = latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0
}

enum class LocationKind(val wire: String, val label: String) {
    BUILDING("building", "Building"),
    UNIT_POSITION("unitPosition", "Unit position"),
    ENTRANCE("entrance", "Entrance"),
    DOOR("door", "Door");

    companion object {
        fun fromWire(value: String?): LocationKind =
            entries.firstOrNull { it.wire == value } ?: UNIT_POSITION
    }
}

enum class Verification(val wire: String) {
    ESTIMATED("estimated"),
    SOURCE_MAPPED("sourceMapped"),
    FIELD_VERIFIED("fieldVerified");

    companion object {
        fun fromWire(value: String?): Verification =
            entries.firstOrNull { it.wire == value } ?: SOURCE_MAPPED
    }
}

data class EntranceSideEstimate(
    val frontage: List<Coordinate>,
    val workingAllowanceMeters: Double,
    val uncertaintyCalibrated: Boolean = false,
    val doorCoordinatesVerified: Boolean = false,
    val entranceCoordinatesVerified: Boolean = false,
    val physicalSignageVerified: Boolean = false,
    val dwellingFloorEvidence: String = "unknown",
    val dwellingFloor: String? = null,
    val entryLevelDescription: String = "Building access wall.",
    val sharedUnits: List<String> = emptyList(),
    val evidenceNotes: String = ""
) {
    val compactNotice: String
        get() {
            val floorText = when (dwellingFloorEvidence) {
                "currentExplicit", "historicalExplicit" ->
                    dwellingFloor?.takeIf { it.isNotBlank() }?.let { "Floor $it" } ?: "Floor unknown"
                else -> "Floor unknown"
            }
            val sharedText = if (sharedUnits.size >= 2) "Shared entry area · " else ""
            return "${sharedText}Estimated building access wall · $floorText · unverified & uncalibrated (~${workingAllowanceMeters.toInt()} m allowance)"
        }
}

data class DigitalLockerPoint(
    val coordinate: Coordinate,
    val precision: String = ""
)

data class DigitalLockerEvidence(
    val status: String, // present, absent, unknown
    val brand: String? = null,
    val placementDescription: String? = null,
    val point: DigitalLockerPoint? = null
) {
    val isPresent: Boolean get() = status == "present"
    val statusLabel: String
        get() = when (status) {
            "present" -> listOfNotNull("Parcel Locker", brand?.takeIf { it.isNotBlank() }).joinToString(" · ")
            "absent" -> "No parcel locker recorded"
            else -> "Locker status unknown"
        }
}

data class Community(
    val id: String,
    val name: String,
    val address: String,
    val zip: String,
    val deliveryAddresses: List<String> = emptyList(),
    val aliases: List<String> = emptyList(),
    val researchStatus: String = "",
    val gapNotes: List<String> = emptyList(),
    val coordinate: Coordinate? = null,
    val digitalLocker: DigitalLockerEvidence? = null
) {
    val matchingAddresses: List<String>
        get() {
            val out = LinkedHashSet<String>()
            if (address.isNotBlank()) out.add(address)
            deliveryAddresses.filter { it.isNotBlank() }.forEach { out.add(it) }
            return out.toList()
        }
}

data class Place(
    val id: String,
    val communityId: String,
    val address: String,
    val building: String = "",
    val unit: String = "",
    val floor: String = "",
    val kind: LocationKind = LocationKind.UNIT_POSITION,
    val verification: Verification = Verification.SOURCE_MAPPED,
    val coordinate: Coordinate,
    val notes: String = "",
    val identityUncertain: Boolean = false,
    val referenceKind: String? = null,
    val sourceUnitId: String? = null,
    val entranceSideEstimate: EntranceSideEstimate? = null
) {
    val isBuildingReference: Boolean get() = referenceKind != null

    val pinEvidenceLabel: String
        get() = when {
            kind == LocationKind.DOOR && verification == Verification.FIELD_VERIFIED -> "Checked door"
            entranceSideEstimate != null ->
                if (entranceSideEstimate.sharedUnits.size >= 2) "Estimated shared entry" else "Estimated building access wall"
            referenceKind != null -> "Approximate building area"
            sourceUnitId != null -> "Operator map unit"
            verification == Verification.ESTIMATED -> "Estimated location"
            else -> "Map location"
        }

    fun highlightZoneRadiusMeters(completed: Boolean, ready: Boolean): Double {
        entranceSideEstimate?.let { est ->
            val maxFrontageDist = est.frontage.maxOfOrNull { LocalSearch.haversineMeters(coordinate, it) } ?: 0.0
            return maxFrontageDist + est.workingAllowanceMeters
        }
        return when {
            completed -> 6.0
            ready -> 6.5
            else -> 9.0
        }
    }

    fun confirmedDoor(
        point: Coordinate,
        newBuilding: String,
        newFloor: String,
        newAddress: String? = null,
        newUnit: String? = null
    ): Place {
        val keepId = kind == LocationKind.DOOR && verification == Verification.FIELD_VERIFIED
        return copy(
            id = if (keepId) id else "personal-door-${UUID.randomUUID()}",
            address = newAddress ?: address,
            building = newBuilding,
            unit = newUnit ?: unit,
            floor = newFloor,
            kind = LocationKind.DOOR,
            verification = Verification.FIELD_VERIFIED,
            coordinate = point,
            identityUncertain = false,
            referenceKind = null,
            sourceUnitId = null,
            entranceSideEstimate = null,
            notes = notes.ifBlank { "Door pin checked by owner." }
        )
    }
}

data class DeliveryStop(
    val id: String = UUID.randomUUID().toString(),
    val sequence: Int,
    val address: String,
    val unit: String = "",
    val notes: String = "",
    val completed: Boolean = false,
    val sourceStopLabel: String? = null
) {
    val sequenceLabel: String
        get() = sourceStopLabel?.takeIf { it.isNotBlank() } ?: sequence.toString()
}

data class ActiveManifest(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val importedAtEpochMs: Long = System.currentTimeMillis(),
    val orderWarning: String? = null
)

enum class AddressRuleKind { APARTMENT, NON_APARTMENT }

data class ApartmentAddressRule(
    val address: String,
    val kind: AddressRuleKind,
    val name: String = "",
    val communityId: String? = null,
    val matchingAddress: String? = null
) {
    val key: String get() = LocalSearch.addressKey(matchingAddress ?: address)
}

data class ApartmentGroup(
    val id: String,
    val name: String,
    val address: String,
    val coordinate: Coordinate?,
    val stops: List<DeliveryStop>,
    val directoryCommunityId: String? = null,
    val legacyCommunityId: String? = null
) {
    val firstSequence: Int get() = stops.firstOrNull()?.sequence ?: 0
    val firstSequenceLabel: String get() = stops.firstOrNull()?.sequenceLabel ?: "0"
    val remainingCount: Int get() = stops.count { !it.completed }
    val nextStop: DeliveryStop? get() = stops.firstOrNull { !it.completed }
}

data class ApartmentRoutePlan(
    val groups: List<ApartmentGroup> = emptyList(),
    val unmatched: List<DeliveryStop> = emptyList(),
    val excluded: List<DeliveryStop> = emptyList()
)

enum class DoorMatchIssue { ADD_UNIT, CHECK_UNIT, PLACE_PIN, CONFIRM_PIN, CHOOSE_DOOR }

data class ApartmentDoorMatch(
    val candidates: List<Place> = emptyList(),
    val readyDoor: Place? = null,
    val issue: DoorMatchIssue? = null,
    val buildingReferences: List<Place> = emptyList()
) {
    val pinEvidenceLabel: String
        get() = when {
            readyDoor != null -> readyDoor.pinEvidenceLabel
            candidates.size == 1 -> candidates[0].pinEvidenceLabel
            candidates.size > 1 -> "Choose location"
            buildingReferences.isNotEmpty() -> "Approximate building area"
            else -> "Unit location not mapped"
        }

    val statusActionLabel: String
        get() = when {
            readyDoor != null -> "Door pin ready"
            issue == DoorMatchIssue.ADD_UNIT -> "Add apartment unit"
            issue == DoorMatchIssue.CHECK_UNIT -> "Check apartment unit"
            issue == DoorMatchIssue.PLACE_PIN -> "Add door pin on map"
            issue == DoorMatchIssue.CONFIRM_PIN ->
                if (candidates.any { it.verification == Verification.ESTIMATED }) {
                    "Check / correct estimated pin"
                } else {
                    "Check suggested pin"
                }
            issue == DoorMatchIssue.CHOOSE_DOOR -> "Choose building / door"
            else -> "Check door pin"
        }
}

data class MiniRouteUndoReceipt(
    val groupId: String,
    val manifestId: String?,
    val completedStopIds: List<String>
)

data class PublicCatalogFileDescriptor(
    val bytes: Long,
    val sha256: String,
    val compressedBytes: Long? = null,
    val compressedSHA256: String? = null,
    val encoding: String? = null
)

data class PublicCatalogManifest(
    val schemaVersion: Int,
    val revision: String,
    val sequence: Long,
    val packVersion: String,
    val files: Map<String, PublicCatalogFileDescriptor>
)

data class InstalledCatalogInfo(
    val revision: String,
    val sequence: Long,
    val communityCount: Int,
    val placeCount: Int,
    val installedAtEpochMs: Long
)

data class CatalogSyncProgress(
    val inProgress: Boolean = false,
    val stage: String = "",
    val fraction: Float = 0f,
    val availableRevision: String? = null,
    val error: String? = null,
    val lastCheckedEpochMs: Long = 0L
)

data class UserLocationFix(
    val coordinate: Coordinate,
    val accuracyMeters: Float,
    val bearingDegrees: Float?,
    val timestampMs: Long
) {
    fun isFresh(nowMs: Long = System.currentTimeMillis()): Boolean =
        abs(nowMs - timestampMs) <= 45_000L && coordinate.isValid
}
