package dev.galaxybigbird.apartmentfinder.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
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

@Composable
fun FinderScreen(
    state: AppUiState,
    locationState: LiveLocationState,
    repository: AppRepository
) {
    val context = LocalContext.current
    var selectedCommunityId by remember { mutableStateOf<String?>(null) }
    var showCommunityPicker by remember { mutableStateOf(false) }
    var unitQuery by remember { mutableStateOf("") }
    var communityPlaces by remember { mutableStateOf<List<Place>>(emptyList()) }
    var selectedPlaceId by remember { mutableStateOf<String?>(null) }
    var showDoorEditor by remember { mutableStateOf(false) }

    val community = remember(state.communities, selectedCommunityId) {
        state.communities.firstOrNull { it.id == selectedCommunityId }
            ?: state.communities.firstOrNull()
    }

    LaunchedEffect(community?.id, state.placesRevision) {
        if (community != null) {
            communityPlaces = repository.getPlacesForCommunity(community.id)
                .filter { !it.isBuildingReference && it.coordinate.isValid }
            if (communityPlaces.none { it.id == selectedPlaceId }) {
                selectedPlaceId = null
            }
        } else {
            communityPlaces = emptyList()
            selectedPlaceId = null
        }
    }

    val filteredPlaces = remember(communityPlaces, unitQuery) {
        val q = LocalSearch.normalized(unitQuery)
        if (q.isEmpty()) communityPlaces
        else communityPlaces.filter {
            LocalSearch.normalized(it.unit).contains(q) ||
                LocalSearch.normalized(it.building).contains(q) ||
                LocalSearch.normalized(it.address).contains(q)
        }
    }

    val selectedPlace = remember(filteredPlaces, selectedPlaceId) {
        filteredPlaces.firstOrNull { it.id == selectedPlaceId }
            ?: if (unitQuery.isNotBlank()) filteredPlaces.firstOrNull() else null
    }

    // Render dense community units as sleek precision bullseye dots (ZERO teardrops!)
    // and upgrade the selected unit to full building-zone circle + 2.2m solid core + frontage + floating capsule!
    val mapPins = remember(filteredPlaces, selectedPlace?.id) {
        val displayList = if (filteredPlaces.size > 600) {
            val sel = selectedPlace
            val capped = filteredPlaces.take(599).toMutableList()
            if (sel != null && capped.none { it.id == sel.id }) capped.add(sel)
            capped
        } else {
            filteredPlaces
        }
        displayList.map { p ->
            val isReady = p.kind == LocationKind.DOOR && p.verification == Verification.FIELD_VERIFIED
            val isSel = p.id == selectedPlace?.id
            val lbl = if (p.building.isNotBlank()) "${p.unit.ifBlank { p.kind.label }} · B${p.building}"
            else p.unit.ifBlank { p.kind.label }
            MapPinModel(
                id = p.id,
                stopId = p.id,
                placeId = p.id,
                coordinate = p.coordinate,
                sequence = if (isReady) "✓" else "•",
                primaryLabel = lbl,
                ready = isReady,
                completed = false,
                selected = isSel,
                estimated = p.verification == Verification.ESTIMATED,
                zoneRadiusMeters = p.highlightZoneRadiusMeters(completed = false, ready = isReady),
                frontage = p.entranceSideEstimate?.frontage.orEmpty(),
                compactDotOnly = !isSel
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Community Picker Button
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showCommunityPicker = true },
            colors = CardDefaults.cardColors(containerColor = BrandTeal.copy(alpha = 0.16f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Apartment, contentDescription = null, tint = BrandTeal)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        community?.name ?: "Choose a community (${state.communities.size})",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (community != null) {
                        Text(
                            "${community.address} · ${community.zip} · ${communityPlaces.size} pins",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
            }
        }

        // Unit search box
        OutlinedTextField(
            value = unitQuery,
            onValueChange = { unitQuery = it },
            placeholder = { Text("Search unit # or building (e.g. 2104)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (unitQuery.isNotEmpty()) {
                    IconButton(onClick = { unitQuery = "" }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        community?.digitalLocker?.takeIf { it.isPresent }?.let { locker ->
            LockerStatusBanner(locker)
        }

        // Full precision satellite map
        PrecisionDeliveryMap(
            pins = mapPins,
            fallbackCenter = selectedPlace?.coordinate ?: community?.coordinate,
            locker = community?.digitalLocker,
            userFix = locationState.fix,
            userBearing = locationState.effectiveBearing,
            satelliteStyle = state.satelliteStyle,
            coverageTitle = "${filteredPlaces.size} mapped units",
            coverageSubtitle = selectedPlace?.pinEvidenceLabel ?: "Tap any precision dot to inspect",
            locationStatusText = locationState.statusText,
            cameraFocusKey = community?.id,
            focusCoordinate = selectedPlace?.coordinate,
            onToggleSatellite = { repository.setSatelliteStyle(!state.satelliteStyle) },
            onSelectPin = { pin -> selectedPlaceId = pin.placeId },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
        )

        // Selected unit detail card
        if (selectedPlace != null && community != null) {
            Card(colors = CardDefaults.cardColors(containerColor = SurfaceCard), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selectedPlace.unit.isBlank()) selectedPlace.kind.label else "Unit ${selectedPlace.unit}",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(Modifier.width(8.dp))
                        val tag = listOfNotNull(
                            selectedPlace.building.takeIf { it.isNotBlank() }?.let { "Bldg $it" },
                            selectedPlace.floor.takeIf { it.isNotBlank() }?.let { "Fl $it" }
                        ).joinToString(" · ")
                        if (tag.isNotBlank()) {
                            Text(tag, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            selectedPlace.pinEvidenceLabel,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selectedPlace.verification == Verification.FIELD_VERIFIED) BrandTeal else BrandOrange
                        )
                    }
                    Text(selectedPlace.address, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    selectedPlace.entranceSideEstimate?.let { est ->
                        Text(est.compactNotice, fontSize = 11.5.sp, color = BrandOrange)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { showDoorEditor = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Check / Save Door")
                        }
                        Button(
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
                                            Uri.parse("geo:${c.latitude},${c.longitude}?q=${c.latitude},${c.longitude}(Unit+${selectedPlace.unit})")
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Walk Here")
                        }
                    }
                }
            }
        }
    }

    if (showCommunityPicker) {
        CommunityPickerDialog(
            communities = state.communities,
            onSelect = {
                selectedCommunityId = it.id
                unitQuery = ""
                showCommunityPicker = false
            },
            onDismiss = { showCommunityPicker = false }
        )
    }

    if (showDoorEditor && selectedPlace != null && community != null) {
        DoorPinEditorDialog(
            title = "Save Checked Door · ${community.name}",
            initialUnit = selectedPlace.unit,
            initialBuilding = selectedPlace.building,
            initialFloor = selectedPlace.floor,
            initialCoordinate = selectedPlace.coordinate,
            satelliteStyle = state.satelliteStyle,
            onDismiss = { showDoorEditor = false },
            onSave = { u, b, f, coord ->
                repository.saveCheckedDoorPin(
                    communityId = community.id,
                    address = selectedPlace.address,
                    unit = u,
                    building = b,
                    floor = f,
                    coordinate = coord,
                    existingPlace = selectedPlace
                )
                showDoorEditor = false
            }
        )
    }
}

@Composable
private fun CommunityPickerDialog(
    communities: List<Community>,
    onSelect: (Community) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val matches = remember(communities, query) {
        val q = LocalSearch.normalized(query)
        if (q.isEmpty()) communities.take(80)
        else communities.filter { c ->
            LocalSearch.normalized(c.name).contains(q) ||
                LocalSearch.normalized(c.address).contains(q) ||
                c.zip.contains(q) ||
                c.aliases.any { LocalSearch.normalized(it).contains(q) } ||
                c.deliveryAddresses.any { LocalSearch.normalized(it).contains(q) }
        }.take(80)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Community (${communities.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 440.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search name, street, or ZIP (e.g. 89183)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(matches, key = { it.id }) { c ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceElevated)
                                .clickable { onSelect(c) }
                                .padding(10.dp)
                        ) {
                            Text(c.name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${c.address} · ${c.zip}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
