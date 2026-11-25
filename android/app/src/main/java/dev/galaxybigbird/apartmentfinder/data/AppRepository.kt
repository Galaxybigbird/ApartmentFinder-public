package dev.galaxybigbird.apartmentfinder.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.galaxybigbird.apartmentfinder.core.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class AppUiState(
    val initialized: Boolean = false,
    val catalogInfo: InstalledCatalogInfo? = null,
    val syncProgress: CatalogSyncProgress = CatalogSyncProgress(),
    val communities: List<Community> = emptyList(),
    val activeManifest: ActiveManifest? = null,
    val stops: List<DeliveryStop> = emptyList(),
    val rules: List<ApartmentAddressRule> = emptyList(),
    val unitCorrections: Map<String, String> = emptyMap(),
    val selectedDoorIds: Map<String, String> = emptyMap(),
    val plan: ApartmentRoutePlan = ApartmentRoutePlan(),
    val undoReceipt: MiniRouteUndoReceipt? = null,
    val pendingImportDocs: List<ManifestDocument>? = null,
    val satelliteStyle: Boolean = true,
    val personalDoorCount: Int = 0,
    val errorMessage: String? = null,
    val placesRevision: Long = 0L
)

data class PersonalBackupPayload(
    val schemaVersion: Int = 1,
    val exportedAtEpochMs: Long = System.currentTimeMillis(),
    val personalPlaces: List<Place> = emptyList(),
    val rules: List<ApartmentAddressRule> = emptyList()
)

class AppRepository(context: Context) {
    private val appContext = context.applicationContext
    private val db = CatalogDatabase(appContext)
    private val catalogClient = PublicCatalogClient(appContext)
    private val gson = Gson()
    private val prettyGson = GsonBuilder().setPrettyPrinting().create()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(AppUiState())
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    // Lightweight per-community place cache invalidated on catalog update or door save
    private val placesCache = LinkedHashMap<String, List<Place>>()

    init {
        scope.launch { bootstrap() }
    }

    private suspend fun bootstrap() {
        val loaded = withContext(Dispatchers.IO) {
            val info = db.getInstalledCatalogInfo()
            val communities = if (info != null) db.loadAllCommunities() else emptyList()
            val manifest = db.getPersonalKv(KEY_MANIFEST)?.let {
                runCatching { gson.fromJson(it, ActiveManifest::class.java) }.getOrNull()
            }
            val stops: List<DeliveryStop> = db.getPersonalKv(KEY_STOPS)?.let {
                runCatching {
                    gson.fromJson<List<DeliveryStop>>(it, object : TypeToken<List<DeliveryStop>>() {}.type)
                }.getOrNull()
            } ?: emptyList()
            val rules: List<ApartmentAddressRule> = db.getPersonalKv(KEY_RULES)?.let {
                runCatching {
                    gson.fromJson<List<ApartmentAddressRule>>(it, object : TypeToken<List<ApartmentAddressRule>>() {}.type)
                }.getOrNull()
            } ?: emptyList()
            val corrections: Map<String, String> = db.getPersonalKv(KEY_CORRECTIONS)?.let {
                runCatching {
                    gson.fromJson<Map<String, String>>(it, object : TypeToken<Map<String, String>>() {}.type)
                }.getOrNull()
            } ?: emptyMap()
            val doorSels: Map<String, String> = db.getPersonalKv(KEY_DOOR_SELECTIONS)?.let {
                runCatching {
                    gson.fromJson<Map<String, String>>(it, object : TypeToken<Map<String, String>>() {}.type)
                }.getOrNull()
            } ?: emptyMap()
            val sat = db.getPersonalKv(KEY_SATELLITE)?.toBooleanStrictOrNull() ?: true
            val personalDoors = db.loadAllPersonalPlaces()
            val personalCommIds = personalDoors.map { it.communityId }.toSet()
            val plan = ApartmentRoutePlanner.buildPlan(stops, communities, rules, corrections, personalCommIds)
            TupleBootstrap(info, communities, manifest, stops, rules, corrections, doorSels, sat, personalDoors.size, plan)
        }

        _state.update {
            it.copy(
                initialized = true,
                catalogInfo = loaded.info,
                communities = loaded.communities,
                activeManifest = loaded.manifest,
                stops = loaded.stops,
                rules = loaded.rules,
                unitCorrections = loaded.corrections,
                selectedDoorIds = loaded.doorSels,
                plan = loaded.plan,
                satelliteStyle = loaded.sat,
                personalDoorCount = loaded.personalDoorCount
            )
        }

        // Automatically check/install Wave 35+ public catalog on launch!
        checkAndUpdateCatalog(autoInstall = true)
    }

    private data class TupleBootstrap(
        val info: InstalledCatalogInfo?,
        val communities: List<Community>,
        val manifest: ActiveManifest?,
        val stops: List<DeliveryStop>,
        val rules: List<ApartmentAddressRule>,
        val corrections: Map<String, String>,
        val doorSels: Map<String, String>,
        val sat: Boolean,
        val personalDoorCount: Int,
        val plan: ApartmentRoutePlan
    )

    fun checkAndUpdateCatalog(autoInstall: Boolean = true, forceReinstall: Boolean = false) {
        if (_state.value.syncProgress.inProgress) return
        scope.launch {
            _state.update {
                it.copy(
                    syncProgress = it.syncProgress.copy(
                        inProgress = true,
                        stage = "Checking Wave 35+ live pin feed…",
                        fraction = 0.03f,
                        error = null
                    )
                )
            }
            try {
                val manifest = catalogClient.fetchLatestManifest()
                val installed = _state.value.catalogInfo
                val needsInstall = forceReinstall || installed == null || manifest.sequence > installed.sequence
                if (!needsInstall) {
                    _state.update {
                        it.copy(
                            syncProgress = CatalogSyncProgress(
                                inProgress = false,
                                stage = "Up to date (${manifest.revision})",
                                fraction = 1f,
                                availableRevision = manifest.revision,
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        )
                    }
                    return@launch
                }
                if (!autoInstall) {
                    _state.update {
                        it.copy(
                            syncProgress = CatalogSyncProgress(
                                inProgress = false,
                                stage = "Update available: ${manifest.revision}",
                                fraction = 0f,
                                availableRevision = manifest.revision,
                                lastCheckedEpochMs = System.currentTimeMillis()
                            )
                        )
                    }
                    return@launch
                }

                val newInfo = catalogClient.downloadAndInstall(manifest, db) { stage, frac ->
                    _state.update { st ->
                        st.copy(syncProgress = st.syncProgress.copy(inProgress = true, stage = stage, fraction = frac))
                    }
                }
                val communities = withContext(Dispatchers.IO) { db.loadAllCommunities() }
                synchronized(placesCache) { placesCache.clear() }
                recomputePlan(communities = communities, catalogInfo = newInfo, bumpPlacesRevision = true)
                _state.update {
                    it.copy(
                        syncProgress = CatalogSyncProgress(
                            inProgress = false,
                            stage = "Installed ${newInfo.revision} (${newInfo.communityCount} communities, %,d pins)".format(newInfo.placeCount),
                            fraction = 1f,
                            availableRevision = newInfo.revision,
                            lastCheckedEpochMs = System.currentTimeMillis()
                        )
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        syncProgress = it.syncProgress.copy(
                            inProgress = false,
                            stage = "",
                            error = e.message ?: "Could not check pin updates."
                        )
                    )
                }
            }
        }
    }

    suspend fun getPlacesForCommunity(communityId: String): List<Place> {
        synchronized(placesCache) {
            placesCache[communityId]?.let { return it }
        }
        val loaded = withContext(Dispatchers.IO) { db.loadPlacesForCommunity(communityId) }
        synchronized(placesCache) {
            if (placesCache.size >= 16) {
                val oldest = placesCache.keys.firstOrNull()
                if (oldest != null) placesCache.remove(oldest)
            }
            placesCache[communityId] = loaded
        }
        return loaded
    }

    suspend fun getPlacesForGroup(group: ApartmentGroup): List<Place> {
        val primary = getPlacesForCommunity(group.id)
        val merged = mutableListOf<Place>()
        merged.addAll(primary)
        val streetKey = LocalSearch.addressKey(group.address)
        for (extraId in listOfNotNull(group.legacyCommunityId, group.directoryCommunityId)) {
            if (extraId == group.id) continue
            val extra = getPlacesForCommunity(extraId)
                .filter { LocalSearch.addressKey(it.address) == streetKey }
                .map { it.copy(communityId = group.id) }
            merged.addAll(extra)
        }
        return LocalSearch.merged(merged, emptyList())
    }

    fun openManifestBytes(bytes: ByteArray, filename: String) {
        scope.launch {
            try {
                val known = _state.value.communities.flatMap { it.matchingAddresses } +
                    _state.value.rules.map { it.address }
                val docs = withContext(Dispatchers.IO) { ManifestReader.read(bytes, filename, known) }
                _state.update { it.copy(pendingImportDocs = docs) }
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = e.message ?: "Could not read manifest file.") }
            }
        }
    }

    fun dismissPendingImport() {
        _state.update { it.copy(pendingImportDocs = null) }
    }

    fun commitManifestDocument(doc: ManifestDocument) {
        if (!doc.result.canImport) {
            _state.update { it.copy(errorMessage = doc.result.errors.firstOrNull() ?: "Manifest has errors.") }
            return
        }
        val manifest = ActiveManifest(
            id = UUID.randomUUID().toString(),
            name = doc.name,
            orderWarning = doc.result.warnings.firstOrNull()
        )
        val stops = doc.result.stops
        scope.launch {
            withContext(Dispatchers.IO) {
                db.putPersonalKv(KEY_MANIFEST, gson.toJson(manifest))
                db.putPersonalKv(KEY_STOPS, gson.toJson(stops))
                db.putPersonalKv(KEY_CORRECTIONS, gson.toJson(emptyMap<String, String>()))
                db.putPersonalKv(KEY_DOOR_SELECTIONS, gson.toJson(emptyMap<String, String>()))
            }
            recomputePlan(
                activeManifest = manifest,
                stops = stops,
                unitCorrections = emptyMap(),
                selectedDoorIds = emptyMap(),
                clearPendingImport = true,
                clearUndo = true
            )
        }
    }

    fun setStopCompleted(stopId: String, completed: Boolean) {
        val updatedStops = _state.value.stops.map {
            if (it.id == stopId) it.copy(completed = completed) else it
        }
        persistStopsAndRecompute(updatedStops, clearUndo = true)
    }

    fun completeMiniRoute(group: ApartmentGroup) {
        val remainingIds = group.stops.filter { !it.completed }.map { it.id }
        if (remainingIds.isEmpty()) return
        val idSet = remainingIds.toSet()
        val updatedStops = _state.value.stops.map {
            if (it.id in idSet) it.copy(completed = true) else it
        }
        val receipt = MiniRouteUndoReceipt(
            groupId = group.id,
            manifestId = _state.value.activeManifest?.id,
            completedStopIds = remainingIds
        )
        persistStopsAndRecompute(updatedStops, newUndoReceipt = receipt)
    }

    fun undoMiniRoute(groupId: String) {
        val receipt = _state.value.undoReceipt ?: return
        if (receipt.groupId != groupId || receipt.manifestId != _state.value.activeManifest?.id) return
        val idSet = receipt.completedStopIds.toSet()
        val updatedStops = _state.value.stops.map {
            if (it.id in idSet) it.copy(completed = false) else it
        }
        persistStopsAndRecompute(updatedStops, clearUndo = true)
    }

    fun addStopToGroup(group: ApartmentGroup, address: String, unit: String, notes: String) {
        val nextSeq = (_state.value.stops.maxOfOrNull { it.sequence } ?: 0) + 1
        val newStop = DeliveryStop(
            sequence = nextSeq,
            address = address.trim().ifEmpty { group.address },
            unit = unit.trim(),
            notes = notes.trim()
        )
        persistStopsAndRecompute(_state.value.stops + newStop, clearUndo = false)
    }

    fun updateStopUnit(stopId: String, newUnit: String) {
        val updated = _state.value.unitCorrections.toMutableMap()
        updated[stopId] = newUnit.trim()
        scope.launch {
            withContext(Dispatchers.IO) { db.putPersonalKv(KEY_CORRECTIONS, gson.toJson(updated)) }
            recomputePlan(unitCorrections = updated)
        }
    }

    fun saveAddressRule(rule: ApartmentAddressRule) {
        val updated = _state.value.rules.filterNot { it.key == rule.key } + rule
        scope.launch {
            withContext(Dispatchers.IO) { db.putPersonalKv(KEY_RULES, gson.toJson(updated)) }
            recomputePlan(rules = updated)
        }
    }

    fun saveCheckedDoorPin(
        communityId: String,
        address: String,
        unit: String,
        building: String,
        floor: String,
        coordinate: Coordinate,
        stopId: String? = null,
        existingPlace: Place? = null
    ) {
        val confirmed = if (existingPlace != null) {
            existingPlace.confirmedDoor(
                point = coordinate,
                newBuilding = building.trim(),
                newFloor = floor.trim(),
                newAddress = address.trim(),
                newUnit = unit.trim()
            )
        } else {
            Place(
                id = "personal-door-${UUID.randomUUID()}",
                communityId = communityId,
                address = address.trim(),
                building = building.trim(),
                unit = unit.trim(),
                floor = floor.trim(),
                kind = LocationKind.DOOR,
                verification = Verification.FIELD_VERIFIED,
                coordinate = coordinate,
                notes = "Door pin checked by owner."
            )
        }

        val updatedDoorSels = _state.value.selectedDoorIds.toMutableMap()
        if (stopId != null) updatedDoorSels[stopId] = confirmed.id

        scope.launch {
            val totalPersonal = withContext(Dispatchers.IO) {
                db.savePersonalPlace(confirmed)
                db.putPersonalKv(KEY_DOOR_SELECTIONS, gson.toJson(updatedDoorSels))
                db.loadAllPersonalPlaces().size
            }
            synchronized(placesCache) { placesCache.remove(communityId) }
            recomputePlan(
                selectedDoorIds = updatedDoorSels,
                personalDoorCount = totalPersonal,
                bumpPlacesRevision = true
            )
        }
    }

    fun setSatelliteStyle(satellite: Boolean) {
        _state.update { it.copy(satelliteStyle = satellite) }
        scope.launch(Dispatchers.IO) {
            db.putPersonalKv(KEY_SATELLITE, satellite.toString())
        }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    suspend fun exportPersonalBackupJson(): String = withContext(Dispatchers.IO) {
        val payload = PersonalBackupPayload(
            personalPlaces = db.loadAllPersonalPlaces(),
            rules = _state.value.rules
        )
        prettyGson.toJson(payload)
    }

    fun importPersonalBackupJson(jsonText: String) {
        scope.launch {
            try {
                val payload = withContext(Dispatchers.IO) {
                    gson.fromJson(jsonText, PersonalBackupPayload::class.java)
                }
                require(payload != null) { "Invalid backup file." }
                withContext(Dispatchers.IO) {
                    db.replaceAllPersonalPlaces(payload.personalPlaces)
                    db.putPersonalKv(KEY_RULES, gson.toJson(payload.rules))
                }
                synchronized(placesCache) { placesCache.clear() }
                recomputePlan(
                    rules = payload.rules,
                    personalDoorCount = payload.personalPlaces.size,
                    bumpPlacesRevision = true
                )
            } catch (e: Exception) {
                _state.update { it.copy(errorMessage = "Backup import failed: ${e.message}") }
            }
        }
    }

    private fun persistStopsAndRecompute(
        updatedStops: List<DeliveryStop>,
        clearUndo: Boolean = false,
        newUndoReceipt: MiniRouteUndoReceipt? = null
    ) {
        scope.launch {
            withContext(Dispatchers.IO) { db.putPersonalKv(KEY_STOPS, gson.toJson(updatedStops)) }
            recomputePlan(stops = updatedStops, clearUndo = clearUndo, newUndoReceipt = newUndoReceipt)
        }
    }

    private suspend fun recomputePlan(
        communities: List<Community> = _state.value.communities,
        catalogInfo: InstalledCatalogInfo? = _state.value.catalogInfo,
        activeManifest: ActiveManifest? = _state.value.activeManifest,
        stops: List<DeliveryStop> = _state.value.stops,
        rules: List<ApartmentAddressRule> = _state.value.rules,
        unitCorrections: Map<String, String> = _state.value.unitCorrections,
        selectedDoorIds: Map<String, String> = _state.value.selectedDoorIds,
        personalDoorCount: Int = _state.value.personalDoorCount,
        clearPendingImport: Boolean = false,
        clearUndo: Boolean = false,
        newUndoReceipt: MiniRouteUndoReceipt? = null,
        bumpPlacesRevision: Boolean = false
    ) {
        val personalCommIds = withContext(Dispatchers.IO) {
            db.loadAllPersonalPlaces().map { it.communityId }.toSet()
        }
        val plan = withContext(Dispatchers.Default) {
            ApartmentRoutePlanner.buildPlan(stops, communities, rules, unitCorrections, personalCommIds)
        }
        _state.update { st ->
            st.copy(
                catalogInfo = catalogInfo,
                communities = communities,
                activeManifest = activeManifest,
                stops = stops,
                rules = rules,
                unitCorrections = unitCorrections,
                selectedDoorIds = selectedDoorIds,
                plan = plan,
                personalDoorCount = personalDoorCount,
                pendingImportDocs = if (clearPendingImport) null else st.pendingImportDocs,
                undoReceipt = when {
                    newUndoReceipt != null -> newUndoReceipt
                    clearUndo -> null
                    else -> st.undoReceipt
                },
                placesRevision = if (bumpPlacesRevision) st.placesRevision + 1 else st.placesRevision
            )
        }
    }

    companion object {
        private const val KEY_MANIFEST = "active_manifest"
        private const val KEY_STOPS = "active_stops"
        private const val KEY_RULES = "address_rules"
        private const val KEY_CORRECTIONS = "unit_corrections"
        private const val KEY_DOOR_SELECTIONS = "door_selections"
        private const val KEY_SATELLITE = "map_style_satellite"
    }
}
