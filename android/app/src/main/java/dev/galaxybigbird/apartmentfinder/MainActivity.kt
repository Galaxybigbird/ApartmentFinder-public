package dev.galaxybigbird.apartmentfinder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.galaxybigbird.apartmentfinder.data.AppRepository
import dev.galaxybigbird.apartmentfinder.data.LocationTracker
import dev.galaxybigbird.apartmentfinder.ui.screens.*
import dev.galaxybigbird.apartmentfinder.ui.theme.ApartmentFinderTheme
import dev.galaxybigbird.apartmentfinder.ui.theme.BrandOrange
import dev.galaxybigbird.apartmentfinder.ui.theme.BrandTeal
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var repository: AppRepository
    private lateinit var locationTracker: LocationTracker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = AppRepository(applicationContext)
        locationTracker = LocationTracker(applicationContext)

        handleIncomingManifestIntent(intent)

        setContent {
            ApartmentFinderTheme {
                ApartmentFinderRoot(
                    repository = repository,
                    locationTracker = locationTracker,
                    onReadUriBytes = ::readUriBytes
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingManifestIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (locationTracker.checkPermission()) locationTracker.start()
    }

    override fun onStop() {
        super.onStop()
        locationTracker.stop()
    }

    private fun handleIncomingManifestIntent(intent: Intent?) {
        if (intent == null) return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }
            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
        if (uri != null) {
            readUriBytes(uri)?.let { (name, bytes) ->
                repository.openManifestBytes(bytes, name)
            }
        }
    }

    private fun readUriBytes(uri: Uri): Pair<String, ByteArray>? {
        return runCatching {
            val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: uri.lastPathSegment ?: "manifest.csv"
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
            name to bytes
        }.getOrNull()
    }
}

private enum class RootTab { APARTMENTS, FINDER }

private sealed class ApartmentsRoute {
    data object List : ApartmentsRoute()
    data object Review : ApartmentsRoute()
    data class MiniRoute(val groupId: String, val startActive: Boolean) : ApartmentsRoute()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApartmentFinderRoot(
    repository: AppRepository,
    locationTracker: LocationTracker,
    onReadUriBytes: (Uri) -> Pair<String, ByteArray>?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsStateWithLifecycle()
    val locationState by locationTracker.state.collectAsStateWithLifecycle()

    var currentTab by remember { mutableStateOf(RootTab.APARTMENTS) }
    var apartmentsRoute by remember { mutableStateOf<ApartmentsRoute>(ApartmentsRoute.List) }
    var showCatalogDialog by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) locationTracker.start()
    }

    LaunchedEffect(Unit) {
        if (!locationTracker.checkPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            locationTracker.start()
        }
    }

    val manifestPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onReadUriBytes(uri)?.let { (name, bytes) ->
                repository.openManifestBytes(bytes, name)
            }
        }
    }

    val backupExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val json = repository.exportPersonalBackupJson()
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
        }
    }

    val backupImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onReadUriBytes(uri)?.let { (_, bytes) ->
                repository.importPersonalBackupJson(bytes.toString(Charsets.UTF_8))
            }
        }
    }

    // Reset pushed mini-route if active manifest changes
    LaunchedEffect(state.activeManifest?.id) {
        apartmentsRoute = ApartmentsRoute.List
    }

    val inSubScreen = currentTab == RootTab.APARTMENTS && apartmentsRoute != ApartmentsRoute.List

    when (val route = apartmentsRoute) {
        is ApartmentsRoute.MiniRoute -> if (currentTab == RootTab.APARTMENTS) {
            MiniRouteScreen(
                groupId = route.groupId,
                startActive = route.startActive,
                state = state,
                locationState = locationState,
                repository = repository,
                onBack = { apartmentsRoute = ApartmentsRoute.List },
                onSwitchGroup = { nextId ->
                    apartmentsRoute = ApartmentsRoute.MiniRoute(nextId, startActive = true)
                }
            )
        }
        is ApartmentsRoute.Review -> if (currentTab == RootTab.APARTMENTS) {
            ReviewAddressesScreen(
                state = state,
                onBack = { apartmentsRoute = ApartmentsRoute.List },
                onSaveRule = { repository.saveAddressRule(it) }
            )
        }
        ApartmentsRoute.List -> Unit
    }

    if (!inSubScreen) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (currentTab == RootTab.APARTMENTS) "Apartments" else "Finder")
                    },
                    actions = {
                        IconButton(onClick = { showCatalogDialog = true }) {
                            Icon(
                                Icons.Default.Sync,
                                contentDescription = "Pin Data Updates",
                                tint = if (state.syncProgress.inProgress) BrandOrange else BrandTeal
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == RootTab.APARTMENTS,
                        onClick = { currentTab = RootTab.APARTMENTS },
                        icon = { Icon(Icons.Default.Apartment, contentDescription = null) },
                        label = { Text("Apartments") }
                    )
                    NavigationBarItem(
                        selected = currentTab == RootTab.FINDER,
                        onClick = { currentTab = RootTab.FINDER },
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        label = { Text("Finder") }
                    )
                }
            }
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (currentTab) {
                    RootTab.APARTMENTS -> ApartmentsScreen(
                        state = state,
                        onPickManifestFile = {
                            manifestPicker.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "application/zip", "*/*"))
                        },
                        onOpenGroup = { g -> apartmentsRoute = ApartmentsRoute.MiniRoute(g.id, startActive = false) },
                        onOpenReview = { apartmentsRoute = ApartmentsRoute.Review },
                        onOpenCatalogUpdates = { showCatalogDialog = true }
                    )
                    RootTab.FINDER -> FinderScreen(
                        state = state,
                        locationState = locationState,
                        repository = repository
                    )
                }
            }
        }
    }

    // Pending manifest import preview sheet
    state.pendingImportDocs?.let { docs ->
        ManifestImportDialog(
            documents = docs,
            communities = state.communities,
            rules = state.rules,
            onImport = { repository.commitManifestDocument(it) },
            onDismiss = { repository.dismissPendingImport() }
        )
    }

    if (showCatalogDialog) {
        CatalogUpdatesDialog(
            state = state,
            onCheckNow = { repository.checkAndUpdateCatalog(autoInstall = true, forceReinstall = false) },
            onForceReinstall = { repository.checkAndUpdateCatalog(autoInstall = true, forceReinstall = true) },
            onDismiss = { showCatalogDialog = false }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            state = state,
            onExportBackup = {
                showSettingsDialog = false
                backupExportLauncher.launch("ApartmentFinder-backup.json")
            },
            onImportBackup = {
                showSettingsDialog = false
                backupImportLauncher.launch(arrayOf("application/json", "*/*"))
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    state.errorMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { repository.clearError() },
            title = { Text("ApartmentFinder") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { repository.clearError() }) { Text("OK") }
            }
        )
    }
}
