package dev.galaxybigbird.apartmentfinder.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.galaxybigbird.apartmentfinder.data.AppUiState
import dev.galaxybigbird.apartmentfinder.ui.theme.BrandOrange
import dev.galaxybigbird.apartmentfinder.ui.theme.BrandTeal

@Composable
fun CatalogUpdatesDialog(
    state: AppUiState,
    onCheckNow: () -> Unit,
    onForceReinstall: () -> Unit,
    onDismiss: () -> Unit
) {
    val info = state.catalogInfo
    val sync = state.syncProgress

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Las Vegas Valley Pin Data") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (info != null) {
                    Text("Installed revision: ${info.revision} (seq ${info.sequence})", fontWeight = FontWeight.SemiBold)
                    Text(
                        "%,d communities · %,d precision pins (65 Las Vegas Valley ZIPs)".format(
                            info.communityCount,
                            info.placeCount
                        ),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("No pin catalog installed yet.", color = BrandOrange, fontWeight = FontWeight.SemiBold)
                }

                if (sync.inProgress) {
                    Text(sync.stage, fontSize = 12.5.sp, color = BrandTeal)
                    LinearProgressIndicator(
                        progress = { sync.fraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = BrandTeal
                    )
                } else if (sync.stage.isNotBlank()) {
                    Text(sync.stage, fontSize = 12.5.sp, color = BrandTeal)
                }

                if (sync.error != null) {
                    Text(sync.error, fontSize = 12.sp, color = BrandOrange)
                }

                Text(
                    "Feed: Galaxybigbird/ApartmentFinder-public (catalog-data · latest-v3.json)",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(enabled = !sync.inProgress, onClick = onCheckNow) {
                Text("Check & Update")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (info != null && !sync.inProgress) {
                    TextButton(onClick = onForceReinstall) { Text("Reinstall") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}

@Composable
fun SettingsDialog(
    state: AppUiState,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings & Backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Personal Checked Doors: ${state.personalDoorCount}", fontWeight = FontWeight.SemiBold)
                Text("Saved Address Rules: ${state.rules.size}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Your checked door pins and address rules are stored locally and stay safe across catalog updates.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onExportBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("Export Personal Backup (JSON)")
                }
                OutlinedButton(onClick = onImportBackup, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore Personal Backup (JSON)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}
