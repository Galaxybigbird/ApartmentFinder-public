package dev.galaxybigbird.apartmentfinder.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.galaxybigbird.apartmentfinder.core.*
import dev.galaxybigbird.apartmentfinder.data.AppUiState
import dev.galaxybigbird.apartmentfinder.ui.theme.*

@Composable
fun ApartmentsScreen(
    state: AppUiState,
    onPickManifestFile: () -> Unit,
    onOpenGroup: (ApartmentGroup) -> Unit,
    onOpenReview: () -> Unit,
    onOpenCatalogUpdates: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Live catalog install / update progress banner
        if (state.syncProgress.inProgress || state.catalogInfo == null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = SurfaceElevated),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = null, tint = BrandTeal, modifier = Modifier.size(18.dp))
                            Text(
                                text = if (state.syncProgress.inProgress) "Updating Las Vegas Valley Pin Data"
                                else "Las Vegas Valley Pin Catalog Needed",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                        if (state.syncProgress.inProgress) {
                            Text(state.syncProgress.stage, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            LinearProgressIndicator(
                                progress = { state.syncProgress.fraction.coerceIn(0f, 1f) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = BrandTeal
                            )
                        } else if (state.syncProgress.error != null) {
                            Text(state.syncProgress.error, fontSize = 12.sp, color = BrandOrange)
                            Button(onClick = onOpenCatalogUpdates) { Text("Retry Download") }
                        }
                    }
                }
            }
        }

        // Manifest summary header
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                shape = RoundedCornerShape(18.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (state.stops.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BrandTeal.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Apartment, contentDescription = null, tint = BrandTeal)
                            }
                            Column {
                                Text("Your apartment deliveries", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Import an OnTrac ZIP/CSV manifest or share it straight into ApartmentFinder.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    } else {
                        Text(
                            "${state.plan.groups.size} apartments",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        val subtitle = state.activeManifest?.orderWarning
                            ?: "Earliest stop first · Tap an apartment to start"
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.activeManifest?.let { m ->
                            Text(
                                "${m.name} · ${state.stops.size} total stops",
                                style = MaterialTheme.typography.labelMedium,
                                color = BrandTeal
                            )
                        }
                    }

                    Button(
                        onClick = onPickManifestFile,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Import Manifest (ZIP / CSV)")
                    }
                }
            }
        }

        // Matched apartment groups
        items(state.plan.groups, key = { it.id }) { group ->
            ApartmentGroupCard(group = group, onClick = { onOpenGroup(group) })
        }

        // Unmatched / review section
        if (state.stops.isNotEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenReview),
                    colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.FilterList, contentDescription = null, tint = BrandOrange)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "${state.plan.unmatched.size} other stops to review",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "Identify unknown addresses or mark houses & businesses.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
        }
    }
}

@Composable
private fun ApartmentGroupCard(group: ApartmentGroup, onClick: () -> Unit) {
    val complete = group.remainingCount == 0
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .widthIn(min = 44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background((if (complete) BrandGreen else BrandTeal).copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = group.firstSequenceLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = if (complete) BrandGreen else BrandTeal
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = group.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = group.address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (complete) "Complete" else "${group.remainingCount} of ${group.stops.size} deliveries left",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (complete) BrandGreen else BrandOrange
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewAddressesScreen(
    state: AppUiState,
    onBack: () -> Unit,
    onSaveRule: (ApartmentAddressRule) -> Unit
) {
    var editingStop by remember { mutableStateOf<DeliveryStop?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review Addresses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    "Tap any unmatched stop to assign it to an apartment community or hide it as a house / business.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.plan.unmatched.isEmpty()) {
                item {
                    Text(
                        "All addresses reviewed.",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandGreen,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            } else {
                items(state.plan.unmatched, key = { it.id }) { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingStop = stop },
                        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Stop ${stop.sequenceLabel} · ${stop.address}", fontWeight = FontWeight.SemiBold)
                                if (stop.unit.isNotBlank()) {
                                    Text("Unit ${stop.unit}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Text("Classify", color = BrandTeal, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        }
                    }
                }
            }

            if (state.plan.excluded.isNotEmpty()) {
                item {
                    Text(
                        "Excluded houses / businesses (${state.plan.excluded.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
                items(state.plan.excluded, key = { "ex_${it.id}" }) { stop ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editingStop = stop },
                        colors = CardDefaults.cardColors(containerColor = SurfaceElevated.copy(alpha = 0.6f)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Stop ${stop.sequenceLabel} · ${stop.address}",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 13.sp
                            )
                            Text("Edit", color = BrandOrange, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    editingStop?.let { stop ->
        var customName by remember(stop.id) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { editingStop = null },
            title = { Text("Classify Stop ${stop.sequenceLabel}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stop.address, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it },
                        label = { Text("Apartment name (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onSaveRule(
                        ApartmentAddressRule(
                            address = stop.address,
                            kind = AddressRuleKind.APARTMENT,
                            name = customName.trim()
                        )
                    )
                    editingStop = null
                }) {
                    Text("Mark Apartment")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    onSaveRule(
                        ApartmentAddressRule(
                            address = stop.address,
                            kind = AddressRuleKind.NON_APARTMENT
                        )
                    )
                    editingStop = null
                }) {
                    Text("House / Business", color = BrandOrange)
                }
            }
        )
    }
}

@Composable
fun ManifestImportDialog(
    documents: List<ManifestDocument>,
    communities: List<Community>,
    rules: List<ApartmentAddressRule>,
    onImport: (ManifestDocument) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedDocId by remember(documents) { mutableStateOf(documents.firstOrNull()?.id) }
    val selected = documents.firstOrNull { it.id == selectedDocId } ?: documents.firstOrNull()
    val previewPlan = remember(selected, communities, rules) {
        if (selected != null && selected.result.canImport) {
            ApartmentRoutePlanner.buildPlan(selected.result.stops, communities, rules)
        } else null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Manifest") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (documents.size > 1) {
                    Text("Choose day / CSV from ZIP:", style = MaterialTheme.typography.labelLarge)
                    documents.take(6).forEach { doc ->
                        val active = doc.id == selected?.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (active) BrandTeal.copy(alpha = 0.2f) else SurfaceElevated)
                                .clickable { selectedDocId = doc.id }
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = doc.name.substringAfterLast('/'),
                                modifier = Modifier.weight(1f),
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text("${doc.result.stops.size} stops", fontSize = 12.sp, color = BrandTeal)
                        }
                    }
                }
                if (selected != null) {
                    if (selected.result.errors.isNotEmpty()) {
                        Text(
                            selected.result.errors.take(3).joinToString("\n"),
                            color = BrandRed,
                            fontSize = 12.sp
                        )
                    } else if (previewPlan != null) {
                        Text(
                            "${selected.result.stops.size} stops → ${previewPlan.groups.size} matched apartments (${previewPlan.unmatched.size} other stops)",
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        selected.result.warnings.forEach { w ->
                            Text(w, fontSize = 12.sp, color = BrandOrange)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = selected?.result?.canImport == true,
                onClick = { selected?.let(onImport) }
            ) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
