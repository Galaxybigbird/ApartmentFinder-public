package dev.galaxybigbird.apartmentfinder.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.galaxybigbird.apartmentfinder.core.*
import dev.galaxybigbird.apartmentfinder.data.AppRepository
import dev.galaxybigbird.apartmentfinder.data.AppUiState
import dev.galaxybigbird.apartmentfinder.data.LiveLocationState
import dev.galaxybigbird.apartmentfinder.ui.map.MapPinModel
import dev.galaxybigbird.apartmentfinder.ui.map.PrecisionDeliveryMap
import dev.galaxybigbird.apartmentfinder.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiniRouteScreen(
    groupId: String,
    startActive: Boolean,
    state: AppUiState,
    locationState: LiveLocationState,
    repository: AppRepository,
    onBack: () -> Unit,
    onSwitchGroup: (String) -> Unit
) {
    val context = LocalContext.current
    val group = state.plan.groups.firstOrNull { it.id == groupId }
    val community = state.communities.firstOrNull { it.id == groupId }

    var activeMapMode by remember(groupId) { mutableStateOf(startActive) }
    var selectedStopId by remember(groupId) { mutableStateOf(group?.nextStop?.id ?: group?.stops?.firstOrNull()?.id) }
    var places by remember(groupId) { mutableStateOf<List<Place>>(emptyList()) }
    var showEvidenceDetails by remember(selectedStopId) { mutableStateOf(false) }
    var showAddStopDialog by remember { mutableStateOf(false) }
    var editingPinStop by remember { mutableStateOf<DeliveryStop?>(null) }

    LaunchedEffect(group?.id, state.placesRevision) {
        if (group != null) {
            places = repository.getPlacesForGroup(group)
        }
    }

    // Keep selected stop valid as stops complete
    LaunchedEffect(group?.nextStop?.id) {
        if (group != null && group.stops.none { it.id == selectedStopId }) {
            selectedStopId = group.nextStop?.id ?: group.stops.firstOrNull()?.id
        }
    }

    val nextApartmentChoice = remember(state.plan.groups, groupId, locationState.fix) {
        ApartmentRoutePlanner.selectNextApartment(state.plan.groups, groupId, locationState.fix)
    }

    if (group == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Mini Route") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        ) { p ->
            Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) {
                Text("Manifest changed. Return to Apartments.")
            }
        }
        return
    }

    val matchesByStopId = remember(group.stops, places, state.selectedDoorIds, community) {
        val communityAddresses = community?.matchingAddresses.orEmpty()
        group.stops.associate { stop ->
            stop.id to ApartmentRoutePlanner.resolveDoorMatch(
                stop = stop,
                communityId = group.id,
                places = places,
                selectedPlaceId = state.selectedDoorIds[stop.id],
                communityAddresses = communityAddresses
            )
        }
    }

    val focusedStopId = selectedStopId ?: group.nextStop?.id ?: group.stops.firstOrNull()?.id

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp)
                        Text(group.address, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (activeMapMode && !startActive) activeMapMode = false else onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showAddStopDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add stop or unit")
                    }
                    if (nextApartmentChoice != null) {
                        TextButton(onClick = { onSwitchGroup(nextApartmentChoice.group.id) }) {
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    if (nextApartmentChoice.isNearestByGps) "Next apt" else "Next stop",
                                    fontSize = 12.sp,
                                    color = BrandTeal,
                                    fontWeight = FontWeight.Bold
                                )
                                nextApartmentChoice.distanceMeters?.let { d ->
                                    Text("${d.toInt()} m", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (!activeMapMode) {
            // Overview mode (matching iOS overview(group))
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(16.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            community?.digitalLocker?.takeIf { it.isPresent }?.let { locker ->
                                LockerStatusBanner(locker)
                            }
                            Text(
                                if (group.remainingCount == 1) "1 delivery left" else "${group.remainingCount} deliveries left",
                                style = MaterialTheme.typography.headlineSmall
                            )
                            Button(
                                onClick = {
                                    selectedStopId = group.nextStop?.id ?: group.stops.firstOrNull()?.id
                                    activeMapMode = true
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(if (group.nextStop == null) "View Mini Route Map" else "Start Mini Route")
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Delivery Order",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                items(group.stops, key = { it.id }) { stop ->
                    val match = matchesByStopId[stop.id] ?: ApartmentDoorMatch()
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedStopId = stop.id
                                activeMapMode = true
                            },
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = if (stop.completed) Icons.Default.CheckCircle else Icons.Default.MyLocation,
                                contentDescription = null,
                                tint = when {
                                    stop.completed -> BrandGreen
                                    match.readyDoor != null -> BrandTeal
                                    else -> BrandOrange
                                }
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Stop ${stop.sequenceLabel} · ${if (stop.unit.isBlank()) "Add unit" else "Unit ${stop.unit}"}",
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    if (stop.completed) "Delivered" else match.statusActionLabel,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (stop.completed) {
                                TextButton(onClick = { repository.setStopCompleted(stop.id, false) }) {
                                    Text("Undo", color = BrandOrange)
                                }
                            } else {
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                            }
                        }
                    }
                }
            }
        } else {
            // Active Precision Map + Bottom Delivery Action Sheet
            val mapPins = remember(group.stops, matchesByStopId, focusedStopId) {
                val out = mutableListOf<MapPinModel>()
                for (stop in group.stops) {
                    val match = matchesByStopId[stop.id] ?: continue
                    val chosenPlaces = match.readyDoor?.let { listOf(it) }
                        ?: match.candidates.ifEmpty { match.buildingReferences.take(1) }
                    for (place in chosenPlaces) {
                        val isReady = match.readyDoor?.id == place.id
                        val label = when {
                            place.isBuildingReference -> "Unit ${stop.unit} · B${place.building} area"
                            place.building.isNotBlank() -> "${stop.unit.ifBlank { "?" }} · B${place.building}"
                            else -> stop.unit.ifBlank { "Unit ?" }
                        }
                        out.add(
                            MapPinModel(
                                id = "${stop.id}|${place.id}",
                                stopId = stop.id,
                                placeId = place.id,
                                coordinate = place.coordinate,
                                sequence = stop.sequenceLabel,
                                primaryLabel = label,
                                ready = isReady,
                                completed = stop.completed,
                                selected = stop.id == focusedStopId,
                                estimated = place.verification == Verification.ESTIMATED,
                                zoneRadiusMeters = place.highlightZoneRadiusMeters(stop.completed, isReady),
                                frontage = place.entranceSideEstimate?.frontage.orEmpty()
                            )
                        )
                    }
                }
                out
            }

            // Stop-order dashed teal guide connectors between adjacent unambiguous non-ESE stops
            val guides = remember(group.stops, matchesByStopId) {
                val unambiguous = group.stops.associate { s ->
                    val m = matchesByStopId[s.id]
                    val single = m?.readyDoor ?: m?.candidates?.singleOrNull()?.takeIf { it.entranceSideEstimate == null && !it.isBuildingReference }
                    s.id to single?.coordinate
                }
                group.stops.zipWithNext().mapNotNull { (a, b) ->
                    val ca = unambiguous[a.id]
                    val cb = unambiguous[b.id]
                    if (ca != null && cb != null) listOf(ca, cb) else null
                }
            }

            val coverageTitle = remember(group, mapPins) {
                val locatedStopIds = mapPins.map { it.stopId }.toSet()
                val missing = group.stops.count { !it.completed && it.id !in locatedStopIds }
                when {
                    group.remainingCount == 0 -> "Completed locations"
                    missing > 0 -> "$missing ${if (missing == 1) "unit" else "units"} not mapped"
                    mapPins.any { !it.ready && !it.completed } -> "Mapped locations"
                    else -> "Checked doors"
                }
            }

            val selectedStop = group.stops.firstOrNull { it.id == focusedStopId }
            val selectedMatch = selectedStop?.let { matchesByStopId[it.id] }
            val selectedPlace = selectedMatch?.readyDoor ?: selectedMatch?.candidates?.firstOrNull()

            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                PrecisionDeliveryMap(
                    pins = mapPins,
                    guides = guides,
                    fallbackCenter = group.coordinate,
                    locker = community?.digitalLocker,
                    userFix = locationState.fix,
                    userBearing = locationState.effectiveBearing,
                    satelliteStyle = state.satelliteStyle,
                    coverageTitle = coverageTitle,
                    coverageSubtitle = if (guides.isNotEmpty()) "Dashed teal line: stop order" else "Outer ring: building zone · Solid core: target",
                    locationStatusText = locationState.statusText,
                    cameraFocusKey = group.id,
                    focusCoordinate = selectedPlace?.coordinate,
                    onToggleSatellite = { repository.setSatelliteStyle(!state.satelliteStyle) },
                    onSelectPin = { pin -> selectedStopId = pin.stopId },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )

                // Bottom active delivery dock
                Surface(
                    color = SurfaceCard,
                    tonalElevation = 6.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val next = group.nextStop
                            if (next != null) {
                                Text("Next · Stop ${next.sequenceLabel}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BrandGreen, modifier = Modifier.size(18.dp))
                                    Text("Apartment complete", fontWeight = FontWeight.Bold, color = BrandGreen)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${group.stops.size - group.remainingCount} / ${group.stops.size} delivered",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        // Horizontal stop chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (stop in group.stops) {
                                val isSel = stop.id == focusedStopId
                                val chipBg = when {
                                    isSel -> BrandTeal.copy(alpha = 0.25f)
                                    stop.completed -> SurfaceElevated.copy(alpha = 0.55f)
                                    else -> SurfaceElevated
                                }
                                Row(
                                    modifier = Modifier
                                        .clip(CircleShape)
                                        .background(chipBg)
                                        .clickable { selectedStopId = stop.id }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Icon(
                                        if (stop.completed) Icons.Default.CheckCircle else Icons.Default.MyLocation,
                                        contentDescription = null,
                                        tint = if (stop.completed) BrandGreen else if (isSel) BrandTeal else BrandOrange,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        "${stop.sequenceLabel} · ${stop.unit.ifBlank { "Add unit" }}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Selected stop detail + primary actions
                        if (selectedStop != null && selectedMatch != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (selectedStop.unit.isBlank()) "Add apartment unit" else "Unit ${selectedStop.unit}",
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Spacer(Modifier.width(8.dp))
                                val meta = listOfNotNull(
                                    selectedPlace?.building?.takeIf { it.isNotBlank() }?.let { "Bldg $it" },
                                    selectedPlace?.floor?.takeIf { it.isNotBlank() }?.let { "Fl $it" }
                                ).joinToString(" · ")
                                if (meta.isNotBlank()) {
                                    Text(meta, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            // Expandable pin evidence row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { showEvidenceDetails = !showEvidenceDetails },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    selectedMatch.pinEvidenceLabel,
                                    fontSize = 12.sp,
                                    color = if (selectedMatch.readyDoor != null) BrandTeal else BrandOrange,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    if (showEvidenceDetails) "Hide details" else "Pin details",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (showEvidenceDetails) {
                                selectedPlace?.entranceSideEstimate?.let { est ->
                                    Text(est.compactNotice, fontSize = 11.5.sp, color = BrandOrange)
                                }
                                if (!selectedPlace?.notes.isNullOrBlank()) {
                                    Text(selectedPlace!!.notes, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }

                            if (selectedStop.notes.isNotBlank()) {
                                Text(
                                    "Notes: ${selectedStop.notes}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            // Action row: Check/Edit Pin + Walk with Google Maps + Delivered
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { editingPinStop = selectedStop },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                ) {
                                    Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        if (selectedMatch.readyDoor != null) "Edit Pin" else "Check Pin",
                                        fontSize = 13.sp,
                                        maxLines = 1
                                    )
                                }

                                if (selectedPlace != null) {
                                    OutlinedButton(
                                        onClick = {
                                            val c = selectedPlace.coordinate
                                            val uri = Uri.parse("google.navigation:q=${c.latitude},${c.longitude}&mode=w")
                                            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                                setPackage("com.google.android.apps.maps")
                                            }
                                            runCatching { context.startActivity(intent) }.onFailure {
                                                context.startActivity(
                                                    Intent(
                                                        Intent.ACTION_VIEW,
                                                        Uri.parse("geo:${c.latitude},${c.longitude}?q=${c.latitude},${c.longitude}(Unit+${selectedStop.unit})")
                                                    )
                                                )
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Walk", fontSize = 13.sp)
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (selectedStop.completed) {
                                    OutlinedButton(
                                        onClick = { repository.setStopCompleted(selectedStop.id, false) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Text("Undo Delivered", color = BrandOrange)
                                    }
                                } else {
                                    Button(
                                        onClick = {
                                            val nextAfter = group.stops.firstOrNull { !it.completed && it.id != selectedStop.id }
                                            repository.setStopCompleted(selectedStop.id, true)
                                            if (nextAfter != null) selectedStopId = nextAfter.id
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Delivered · Next Stop")
                                    }
                                }
                            }
                        }

                        // Complete mini-route / Undo mini-route footer
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (group.remainingCount > 0) {
                                TextButton(onClick = { repository.completeMiniRoute(group) }) {
                                    Text("Complete mini-route (${group.remainingCount})", fontSize = 12.sp, color = BrandTeal)
                                }
                            }
                            if (state.undoReceipt?.groupId == group.id && state.undoReceipt.manifestId == state.activeManifest?.id) {
                                TextButton(onClick = { repository.undoMiniRoute(group.id) }) {
                                    Text("Undo mini-route", fontSize = 12.sp, color = BrandOrange)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Add Stop / Unit Dialog
    if (showAddStopDialog) {
        var newUnit by remember { mutableStateOf("") }
        var newNotes by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddStopDialog = false },
            title = { Text("Add Stop to ${group.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newUnit,
                        onValueChange = { newUnit = it },
                        label = { Text("Unit number") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newNotes,
                        onValueChange = { newNotes = it },
                        label = { Text("Notes (optional)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = newUnit.isNotBlank(),
                    onClick = {
                        repository.addStopToGroup(group, group.address, newUnit, newNotes)
                        showAddStopDialog = false
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showAddStopDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Interactive Check / Correct Door Pin Dialog (tap exact door on satellite imagery!)
    editingPinStop?.let { stop ->
        val match = matchesByStopId[stop.id] ?: ApartmentDoorMatch()
        val initialPlace = match.readyDoor ?: match.candidates.firstOrNull()
        DoorPinEditorDialog(
            title = "Check Door Pin · Stop ${stop.sequenceLabel}",
            initialUnit = stop.unit,
            initialBuilding = initialPlace?.building.orEmpty(),
            initialFloor = initialPlace?.floor.orEmpty(),
            initialCoordinate = initialPlace?.coordinate ?: group.coordinate ?: Coordinate(36.1147, -115.1728),
            satelliteStyle = state.satelliteStyle,
            onDismiss = { editingPinStop = null },
            onSave = { unitVal, bldgVal, floorVal, coord ->
                if (unitVal.trim() != stop.unit.trim()) {
                    repository.updateStopUnit(stop.id, unitVal.trim())
                }
                repository.saveCheckedDoorPin(
                    communityId = group.id,
                    address = stop.address,
                    unit = unitVal.trim(),
                    building = bldgVal.trim(),
                    floor = floorVal.trim(),
                    coordinate = coord,
                    stopId = stop.id,
                    existingPlace = initialPlace
                )
                editingPinStop = null
            }
        )
    }
}

@Composable
fun LockerStatusBanner(locker: DigitalLockerEvidence) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrandPurple.copy(alpha = 0.16f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(Icons.Default.Inventory2, contentDescription = null, tint = BrandPurple, modifier = Modifier.size(18.dp))
        Column {
            Text(locker.statusLabel, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
            if (!locker.placementDescription.isNullOrBlank()) {
                Text(locker.placementDescription, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun DoorPinEditorDialog(
    title: String,
    initialUnit: String,
    initialBuilding: String,
    initialFloor: String,
    initialCoordinate: Coordinate,
    satelliteStyle: Boolean,
    onDismiss: () -> Unit,
    onSave: (unit: String, building: String, floor: String, coord: Coordinate) -> Unit
) {
    var unit by remember { mutableStateOf(initialUnit) }
    var building by remember { mutableStateOf(initialBuilding) }
    var floor by remember { mutableStateOf(initialFloor) }
    var pickedCoord by remember { mutableStateOf(initialCoordinate) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 16.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Tap the satellite map to place the exact verified door pin (turns Teal Checked Door).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PrecisionDeliveryMap(
                    pins = emptyList(),
                    fallbackCenter = pickedCoord,
                    pickerCoordinate = pickedCoord,
                    satelliteStyle = satelliteStyle,
                    onMapTapCoordinate = { pickedCoord = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                        .clip(RoundedCornerShape(14.dp))
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it },
                        label = { Text("Unit") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = building,
                        onValueChange = { building = it },
                        label = { Text("Bldg") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = floor,
                        onValueChange = { floor = it },
                        label = { Text("Floor") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = unit.isNotBlank() && pickedCoord.isValid,
                onClick = { onSave(unit, building, floor, pickedCoord) }
            ) {
                Text("Save Checked Door")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
