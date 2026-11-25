package dev.galaxybigbird.apartmentfinder.ui.map

import android.graphics.Color
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.galaxybigbird.apartmentfinder.core.Coordinate
import dev.galaxybigbird.apartmentfinder.core.DigitalLockerEvidence
import dev.galaxybigbird.apartmentfinder.core.UserLocationFix
import dev.galaxybigbird.apartmentfinder.ui.theme.BrandTeal
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.cos
import kotlin.math.sin

data class MapPinModel(
    val id: String,
    val stopId: String,
    val placeId: String,
    val coordinate: Coordinate,
    val sequence: String,
    val primaryLabel: String,
    val ready: Boolean,
    val completed: Boolean,
    val selected: Boolean,
    val estimated: Boolean,
    val zoneRadiusMeters: Double,
    val frontage: List<Coordinate>,
    val compactDotOnly: Boolean = false
)

/**
 * Native MapLibre GL delivery & finder map with 100% iOS Build 31.1 precision pin parity:
 *  - ZERO plain teardrop pins anywhere
 *  - Hybrid Satellite (Esri World Imagery + Road Labels) & Street Map toggle (zero API key!)
 *  - True geodesic highlighted building-zone circle + deeper 2.2m solid inner precision core
 *    + deeper 3.5-4.5dp rounded-cap frontage wall highlight line + sleek floating capsule + anchor dot
 *  - Heading-up follow / pause / overview framing + optional tap-to-drop pin editor mode
 */
@Composable
fun PrecisionDeliveryMap(
    pins: List<MapPinModel>,
    guides: List<List<Coordinate>> = emptyList(),
    fallbackCenter: Coordinate? = null,
    locker: DigitalLockerEvidence? = null,
    userFix: UserLocationFix? = null,
    userBearing: Float? = null,
    satelliteStyle: Boolean = true,
    coverageTitle: String? = null,
    coverageSubtitle: String? = null,
    locationStatusText: String? = null,
    pickerCoordinate: Coordinate? = null,
    cameraFocusKey: Any? = null,
    focusCoordinate: Coordinate? = null,
    onToggleSatellite: (() -> Unit)? = null,
    onSelectPin: (MapPinModel) -> Unit = {},
    onMapTapCoordinate: ((Coordinate) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density

    remember(context) { MapLibre.getInstance(context) }

    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var styleReady by remember { mutableStateOf(false) }
    var followingUser by remember { mutableStateOf(false) }
    var hasFramedInitial by remember { mutableStateOf(false) }
    var lastStyleSatellite by remember { mutableStateOf<Boolean?>(null) }

    val latestPins by rememberUpdatedState(pins)
    val latestGuides by rememberUpdatedState(guides)
    val latestLocker by rememberUpdatedState(locker)
    val latestFix by rememberUpdatedState(userFix)
    val latestPicker by rememberUpdatedState(pickerCoordinate)
    val latestOnSelectPin by rememberUpdatedState(onSelectPin)
    val latestOnTapCoord by rememberUpdatedState(onMapTapCoordinate)

    fun fitAllPins(map: MapLibreMap, includeUser: Boolean = false) {
        val coords = mutableListOf<Coordinate>()
        latestPins.forEach { if (it.coordinate.isValid) coords.add(it.coordinate) }
        latestPicker?.takeIf { it.isValid }?.let { coords.add(it) }
        if (coords.isEmpty()) {
            fallbackCenter?.takeIf { it.isValid }?.let { coords.add(it) }
        }
        latestLocker?.takeIf { it.isPresent }?.point?.coordinate?.takeIf { it.isValid }?.let {
            if (coords.isEmpty()) coords.add(it)
        }
        if (includeUser) {
            latestFix?.takeIf { it.isFresh() }?.let { fix ->
                val ref = coords.firstOrNull()
                if (ref == null || dev.galaxybigbird.apartmentfinder.core.LocalSearch.haversineMeters(ref, fix.coordinate) < 1200.0) {
                    coords.add(fix.coordinate)
                }
            }
        }
        if (coords.isEmpty()) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(36.1147, -115.1728))
                        .zoom(12.5)
                        .bearing(0.0)
                        .build()
                ),
                400
            )
            return
        }
        if (coords.size == 1) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(LatLng(coords[0].latitude, coords[0].longitude))
                        .zoom(18.2)
                        .bearing(0.0)
                        .build()
                ),
                350
            )
            return
        }
        val b = LatLngBounds.Builder()
        coords.forEach { b.include(LatLng(it.latitude, it.longitude)) }
        val bounds = b.build()
        val spanLat = bounds.latitudeSpan
        val spanLon = bounds.longitudeSpan
        if (spanLat < 0.00015 && spanLon < 0.00015) {
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder()
                        .target(bounds.center)
                        .zoom(18.2)
                        .bearing(0.0)
                        .build()
                ),
                350
            )
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, (56 * density).toInt()), 400)
        }
    }

    fun syncStyleData(style: Style) {
        if (!style.isFullyLoaded) return

        // 1. Build outer zones, inner 2.2m precision cores, frontage wall lines, capsule symbols & compact dots
        val zoneFeatures = ArrayList<Feature>(latestPins.size)
        val coreFeatures = ArrayList<Feature>(latestPins.size)
        val frontageFeatures = ArrayList<Feature>()
        val symbolFeatures = ArrayList<Feature>()
        val dotFeatures = ArrayList<Feature>()

        val sortedPins = latestPins.sortedBy { if (it.selected) 1 else 0 }
        for (pin in sortedPins) {
            if (!pin.coordinate.isValid) continue

            val showZoneAndCore = !pin.compactDotOnly || pin.selected
            if (showZoneAndCore && pin.zoneRadiusMeters > 0.0) {
                val zoneKey = when {
                    pin.completed -> "zone-completed"
                    pin.ready && pin.selected -> "zone-ready-selected"
                    pin.ready -> "zone-ready"
                    pin.selected -> "zone-selected"
                    else -> "zone"
                }
                val zf = Feature.fromGeometry(geodesicCirclePolygon(pin.coordinate, pin.zoneRadiusMeters, 36))
                zf.addStringProperty("style", zoneKey)
                zoneFeatures.add(zf)

                if (!pin.completed) {
                    val coreR = minOf(2.2, maxOf(1.0, pin.zoneRadiusMeters * 0.45))
                    val coreKey = when {
                        pin.ready && pin.selected -> "core-ready-selected"
                        pin.ready -> "core-ready"
                        pin.selected -> "core-selected"
                        else -> "core"
                    }
                    val cf = Feature.fromGeometry(geodesicCirclePolygon(pin.coordinate, coreR, 24))
                    cf.addStringProperty("style", coreKey)
                    coreFeatures.add(cf)
                }

                if (pin.frontage.size >= 2) {
                    val fKey = when {
                        pin.completed -> "frontage-completed"
                        pin.ready && pin.selected -> "frontage-ready-selected"
                        pin.ready -> "frontage-ready"
                        pin.selected -> "frontage-selected"
                        else -> "frontage"
                    }
                    val ff = Feature.fromGeometry(
                        LineString.fromLngLats(pin.frontage.map { Point.fromLngLat(it.longitude, it.latitude) })
                    )
                    ff.addStringProperty("style", fKey)
                    frontageFeatures.add(ff)
                }
            }

            if (pin.compactDotOnly && !pin.selected) {
                val df = Feature.fromGeometry(Point.fromLngLat(pin.coordinate.longitude, pin.coordinate.latitude))
                df.addStringProperty("pinId", pin.id)
                df.addStringProperty("dotStyle", if (pin.ready) "ready" else "mapped")
                dotFeatures.add(df)
            } else {
                val iconId = "pin_${pin.id}_${pin.selected}_${pin.ready}_${pin.completed}_${pin.primaryLabel.hashCode()}"
                if (style.getImage(iconId) == null) {
                    val bmp = PrecisionPinBitmapFactory.createDeliveryPinBitmap(
                        density = density,
                        sequence = pin.sequence,
                        primaryLabel = pin.primaryLabel,
                        ready = pin.ready,
                        completed = pin.completed,
                        selected = pin.selected,
                        estimated = pin.estimated
                    )
                    style.addImage(iconId, bmp)
                }
                val sf = Feature.fromGeometry(Point.fromLngLat(pin.coordinate.longitude, pin.coordinate.latitude))
                sf.addStringProperty("iconId", iconId)
                sf.addStringProperty("pinId", pin.id)
                sf.addNumberProperty("sortKey", if (pin.selected) 100 else 10)
                symbolFeatures.add(sf)
            }
        }

        // Picker preview target when placing/correcting a checked door pin
        latestPicker?.takeIf { it.isValid }?.let { coord ->
            val zf = Feature.fromGeometry(geodesicCirclePolygon(coord, 6.5, 36))
            zf.addStringProperty("style", "zone-ready-selected")
            zoneFeatures.add(zf)
            val cf = Feature.fromGeometry(geodesicCirclePolygon(coord, 2.2, 24))
            cf.addStringProperty("style", "core-ready-selected")
            coreFeatures.add(cf)
        }

        // Stop-order dashed teal guide lines
        val guideFeatures = latestGuides.filter { it.size >= 2 }.map { seg ->
            Feature.fromGeometry(LineString.fromLngLats(seg.map { Point.fromLngLat(it.longitude, it.latitude) }))
        }

        // Digital locker marker
        val lockerFeatures = mutableListOf<Feature>()
        latestLocker?.takeIf { it.isPresent }?.point?.coordinate?.takeIf { it.isValid }?.let { c ->
            if (style.getImage("locker_pin") == null) {
                style.addImage("locker_pin", PrecisionPinBitmapFactory.createLockerPinBitmap(density))
            }
            lockerFeatures.add(Feature.fromGeometry(Point.fromLngLat(c.longitude, c.latitude)))
        }

        // User GPS blue dot
        val userFeatures = mutableListOf<Feature>()
        latestFix?.takeIf { it.coordinate.isValid }?.let { fix ->
            userFeatures.add(Feature.fromGeometry(Point.fromLngLat(fix.coordinate.longitude, fix.coordinate.latitude)))
        }

        style.getSourceAs<GeoJsonSource>(SRC_ZONE)?.setGeoJson(FeatureCollection.fromFeatures(zoneFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_FRONTAGE)?.setGeoJson(FeatureCollection.fromFeatures(frontageFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_CORE)?.setGeoJson(FeatureCollection.fromFeatures(coreFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_GUIDES)?.setGeoJson(FeatureCollection.fromFeatures(guideFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_DOTS)?.setGeoJson(FeatureCollection.fromFeatures(dotFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(symbolFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_LOCKER)?.setGeoJson(FeatureCollection.fromFeatures(lockerFeatures))
        style.getSourceAs<GeoJsonSource>(SRC_USER)?.setGeoJson(FeatureCollection.fromFeatures(userFeatures))
    }

    fun installLayers(style: Style) {
        style.addSource(GeoJsonSource(SRC_GUIDES))
        style.addSource(GeoJsonSource(SRC_ZONE))
        style.addSource(GeoJsonSource(SRC_FRONTAGE))
        style.addSource(GeoJsonSource(SRC_CORE))
        style.addSource(GeoJsonSource(SRC_DOTS))
        style.addSource(GeoJsonSource(SRC_LOCKER))
        style.addSource(GeoJsonSource(SRC_USER))
        style.addSource(GeoJsonSource(SRC_PINS))

        // Dashed stop-order guide line
        style.addLayer(
            LineLayer(LYR_GUIDES, SRC_GUIDES).withProperties(
                lineColor(Color.parseColor("#CC14B8A6")),
                lineWidth(2.8f),
                lineDasharray(arrayOf(3f, 2.5f)),
                lineCap(Property.LINE_CAP_ROUND)
            )
        )

        // Outer highlighted building-zone circle (fill + dashed ring)
        style.addLayer(
            FillLayer(LYR_ZONE_FILL, SRC_ZONE).withProperties(
                fillColor(
                    match(
                        get("style"),
                        literal("#FF9500"),
                        stop("zone-selected", literal("#FF9500")),
                        stop("zone-ready-selected", literal("#14B8A6")),
                        stop("zone-ready", literal("#14B8A6")),
                        stop("zone-completed", literal("#8E8E93"))
                    )
                ),
                fillOpacity(
                    match(
                        get("style"),
                        literal(0.14f),
                        stop("zone-selected", literal(0.24f)),
                        stop("zone-ready-selected", literal(0.25f)),
                        stop("zone-ready", literal(0.14f)),
                        stop("zone-completed", literal(0.08f))
                    )
                )
            )
        )
        style.addLayer(
            LineLayer(LYR_ZONE_STROKE, SRC_ZONE).withProperties(
                lineColor(
                    match(
                        get("style"),
                        literal("#FF9500"),
                        stop("zone-selected", literal("#FF9500")),
                        stop("zone-ready-selected", literal("#14B8A6")),
                        stop("zone-ready", literal("#14B8A6")),
                        stop("zone-completed", literal("#8E8E93"))
                    )
                ),
                lineOpacity(
                    match(
                        get("style"),
                        literal(0.70f),
                        stop("zone-selected", literal(0.96f)),
                        stop("zone-ready-selected", literal(0.96f)),
                        stop("zone-ready", literal(0.75f)),
                        stop("zone-completed", literal(0.38f))
                    )
                ),
                lineWidth(
                    match(
                        get("style"),
                        literal(1.6f),
                        stop("zone-selected", literal(2.3f)),
                        stop("zone-ready-selected", literal(2.3f)),
                        stop("zone-ready", literal(1.6f)),
                        stop("zone-completed", literal(1.2f))
                    )
                ),
                lineDasharray(arrayOf(3f, 2f))
            )
        )

        // Deeper high-contrast frontage highlight line (3.5-4.5dp rounded-cap)
        style.addLayer(
            LineLayer(LYR_FRONTAGE, SRC_FRONTAGE).withProperties(
                lineColor(
                    match(
                        get("style"),
                        literal("#FF7A00"),
                        stop("frontage-selected", literal("#FF6D00")),
                        stop("frontage-ready-selected", literal("#14B8A6")),
                        stop("frontage-ready", literal("#14B8A6")),
                        stop("frontage-completed", literal("#8E8E93"))
                    )
                ),
                lineOpacity(
                    match(
                        get("style"),
                        literal(0.92f),
                        stop("frontage-selected", literal(0.98f)),
                        stop("frontage-ready-selected", literal(0.98f)),
                        stop("frontage-completed", literal(0.45f))
                    )
                ),
                lineWidth(
                    match(
                        get("style"),
                        literal(3.6f),
                        stop("frontage-selected", literal(4.6f)),
                        stop("frontage-ready-selected", literal(4.6f)),
                        stop("frontage-completed", literal(2.2f))
                    )
                ),
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineDasharray(arrayOf(2.4f, 1.1f))
            )
        )

        // Deeper 2.2m solid-ring inner precision core circle
        style.addLayer(
            FillLayer(LYR_CORE_FILL, SRC_CORE).withProperties(
                fillColor(
                    match(
                        get("style"),
                        literal("#FF8800"),
                        stop("core-selected", literal("#FF6D00")),
                        stop("core-ready-selected", literal("#14B8A6")),
                        stop("core-ready", literal("#14B8A6"))
                    )
                ),
                fillOpacity(
                    match(
                        get("style"),
                        literal(0.40f),
                        stop("core-selected", literal(0.52f)),
                        stop("core-ready-selected", literal(0.54f)),
                        stop("core-ready", literal(0.42f))
                    )
                )
            )
        )
        style.addLayer(
            LineLayer(LYR_CORE_STROKE, SRC_CORE).withProperties(
                lineColor(
                    match(
                        get("style"),
                        literal("#FF9500"),
                        stop("core-selected", literal("#FF6D00")),
                        stop("core-ready-selected", literal("#14B8A6")),
                        stop("core-ready", literal("#14B8A6"))
                    )
                ),
                lineWidth(
                    match(
                        get("style"),
                        literal(1.8f),
                        stop("core-selected", literal(2.3f)),
                        stop("core-ready-selected", literal(2.3f)),
                        stop("core-ready", literal(1.8f))
                    )
                )
            )
        )

        // Compact precision dots for dense Finder views (dark rim + white ring + vivid core — ZERO teardrops!)
        style.addLayer(
            CircleLayer(LYR_DOTS_OUTER, SRC_DOTS).withProperties(
                circleRadius(5.5f),
                circleColor(Color.WHITE),
                circleStrokeColor(Color.parseColor("#CC000000")),
                circleStrokeWidth(1.2f)
            )
        )
        style.addLayer(
            CircleLayer(LYR_DOTS_CORE, SRC_DOTS).withProperties(
                circleRadius(3.7f),
                circleColor(
                    match(
                        get("dotStyle"),
                        literal("#FF9500"),
                        stop("ready", literal("#14B8A6"))
                    )
                )
            )
        )

        // Digital Locker badge
        style.addLayer(
            SymbolLayer(LYR_LOCKER, SRC_LOCKER).withProperties(
                iconImage("locker_pin"),
                iconAllowOverlap(true),
                iconIgnorePlacement(true)
            )
        )

        // Live GPS user blue puck
        style.addLayer(
            CircleLayer(LYR_USER_HALO, SRC_USER).withProperties(
                circleRadius(14f),
                circleColor(Color.parseColor("#332563EB"))
            )
        )
        style.addLayer(
            CircleLayer(LYR_USER_DOT, SRC_USER).withProperties(
                circleRadius(6.5f),
                circleColor(Color.parseColor("#3B82F6")),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(2.4f)
            )
        )

        // Floating dark-glass precision stop capsules + hairline stems + target anchor dots
        style.addLayer(
            SymbolLayer(LYR_PINS, SRC_PINS).withProperties(
                iconImage(get("iconId")),
                iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                iconAllowOverlap(true),
                iconIgnorePlacement(true),
                symbolSortKey(get("sortKey"))
            )
        )
    }

    fun loadMapStyle(map: MapLibreMap, isSatellite: Boolean) {
        styleReady = false
        lastStyleSatellite = isSatellite
        val json = if (isSatellite) HYBRID_SATELLITE_STYLE_JSON else STREET_MAP_STYLE_JSON
        map.setStyle(Style.Builder().fromJson(json)) { style ->
            installLayers(style)
            styleReady = true
            syncStyleData(style)
            if (!hasFramedInitial) {
                hasFramedInitial = true
                fitAllPins(map, includeUser = false)
            }
        }
    }

    val mapView = remember {
        MapView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            onCreate(null)
            getMapAsync { map ->
                map.uiSettings.isTiltGesturesEnabled = false
                map.uiSettings.isCompassEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.uiSettings.isAttributionEnabled = false
                map.addOnCameraMoveStartedListener { reason ->
                    if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                        followingUser = false
                    }
                }
                map.addOnMapClickListener { latLng ->
                    val screenPt = map.projection.toScreenLocation(latLng)
                    val hitFeatures = map.queryRenderedFeatures(screenPt, LYR_PINS, LYR_DOTS_CORE, LYR_DOTS_OUTER)
                    val hitPinId = hitFeatures.firstOrNull()?.getStringProperty("pinId")
                    if (hitPinId != null) {
                        val matched = latestPins.firstOrNull { it.id == hitPinId }
                        if (matched != null) {
                            latestOnSelectPin(matched)
                            return@addOnMapClickListener true
                        }
                    }
                    latestOnTapCoord?.invoke(Coordinate(latLng.latitude, latLng.longitude))
                    latestOnTapCoord != null
                }
                mapRef = map
                loadMapStyle(map, satelliteStyle)
            }
        }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            if (destroyed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> runCatching { mapView.onStart() }
                Lifecycle.Event.ON_RESUME -> runCatching { mapView.onResume() }
                Lifecycle.Event.ON_PAUSE -> runCatching { mapView.onPause() }
                Lifecycle.Event.ON_STOP -> runCatching { mapView.onStop() }
                Lifecycle.Event.ON_DESTROY -> {
                    destroyed = true
                    runCatching { mapView.onDestroy() }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (!destroyed) {
                destroyed = true
                runCatching { mapView.onPause() }
                runCatching { mapView.onStop() }
                runCatching { mapView.onDestroy() }
            }
        }
    }

    // Re-frame camera when switching community or mini-route group
    LaunchedEffect(mapRef, styleReady, cameraFocusKey) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        followingUser = false
        fitAllPins(map, includeUser = false)
    }

    // Smoothly focus selected unit / stop coordinate
    LaunchedEffect(mapRef, styleReady, focusCoordinate) {
        val map = mapRef ?: return@LaunchedEffect
        val target = focusCoordinate?.takeIf { it.isValid } ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        followingUser = false
        val targetZoom = maxOf(map.cameraPosition.zoom, 18.2)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(target.latitude, target.longitude))
                    .zoom(targetZoom)
                    .build()
            ),
            350
        )
    }

    // Switch style when satellite toggle changes
    LaunchedEffect(mapRef, satelliteStyle) {
        val map = mapRef ?: return@LaunchedEffect
        if (lastStyleSatellite != satelliteStyle) {
            loadMapStyle(map, satelliteStyle)
        }
    }

    // Re-sync GeoJSON layers whenever pins, selection, guides, locker, picker, or GPS changes
    LaunchedEffect(mapRef, styleReady, pins, guides, locker, userFix, pickerCoordinate) {
        val map = mapRef ?: return@LaunchedEffect
        if (!styleReady) return@LaunchedEffect
        map.style?.let { syncStyleData(it) }
    }

    // Smooth follow-me heading-up camera updates
    LaunchedEffect(followingUser, userFix, userBearing) {
        if (!followingUser) return@LaunchedEffect
        val map = mapRef ?: return@LaunchedEffect
        val fix = userFix?.takeIf { it.isFresh() } ?: return@LaunchedEffect
        val targetZoom = maxOf(map.cameraPosition.zoom, 18.0)
        map.animateCamera(
            CameraUpdateFactory.newCameraPosition(
                CameraPosition.Builder()
                    .target(LatLng(fix.coordinate.latitude, fix.coordinate.longitude))
                    .zoom(targetZoom)
                    .bearing((userBearing ?: 0f).toDouble())
                    .build()
            ),
            350
        )
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize()
        )

        // Top-left coverage pill (matching iOS ApartmentDeliveryMap)
        if (!coverageTitle.isNullOrBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color(0xD90E141E))
                    .padding(horizontal = 11.dp, vertical = 8.dp)
            ) {
                Text(
                    text = coverageTitle,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Bold
                )
                if (!coverageSubtitle.isNullOrBlank()) {
                    Text(
                        text = coverageSubtitle,
                        color = androidx.compose.ui.graphics.Color(0xFFB6C2D4),
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Bottom-left GPS status chip
        if (!locationStatusText.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(0xD90E141E))
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = locationStatusText,
                    color = androidx.compose.ui.graphics.Color(0xFFD5DFEC),
                    fontSize = 11.5.sp
                )
            }
        }

        // Bottom-right compact circular map controls (matching iOS Satellite/Map, Show All, Follow/Recenter)
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (onToggleSatellite != null) {
                MapControlCircleButton(
                    icon = Icons.Default.Layers,
                    contentDescription = if (satelliteStyle) "Switch to street map" else "Switch to satellite",
                    highlighted = satelliteStyle,
                    onClick = onToggleSatellite
                )
            }
            MapControlCircleButton(
                icon = Icons.Default.CropFree,
                contentDescription = "Show all stops",
                highlighted = false,
                onClick = {
                    followingUser = false
                    mapRef?.let { fitAllPins(it, includeUser = true) }
                }
            )
            MapControlCircleButton(
                icon = if (followingUser) Icons.Default.Navigation else Icons.Default.MyLocation,
                contentDescription = if (followingUser) "Following your location" else "Center on location",
                highlighted = followingUser,
                onClick = {
                    val fix = latestFix?.takeIf { it.isFresh() }
                    if (fix != null) {
                        followingUser = !followingUser
                        if (followingUser) {
                            mapRef?.animateCamera(
                                CameraUpdateFactory.newCameraPosition(
                                    CameraPosition.Builder()
                                        .target(LatLng(fix.coordinate.latitude, fix.coordinate.longitude))
                                        .zoom(18.5)
                                        .bearing((userBearing ?: 0f).toDouble())
                                        .build()
                                ),
                                400
                            )
                        }
                    } else {
                        mapRef?.let { fitAllPins(it, includeUser = false) }
                    }
                }
            )
        }
    }
}

@Composable
private fun MapControlCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    highlighted: Boolean,
    onClick: () -> Unit
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = androidx.compose.ui.graphics.Color(0xE0111824),
            contentColor = if (highlighted) BrandTeal else androidx.compose.ui.graphics.Color.White
        )
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(20.dp))
    }
}

private fun geodesicCirclePolygon(center: Coordinate, radiusMeters: Double, sides: Int): Polygon {
    val latRad = Math.toRadians(center.latitude)
    val dLat = radiusMeters / 111_195.0
    val dLon = dLat / cos(latRad).coerceAtLeast(0.01)
    val ring = ArrayList<Point>(sides + 1)
    for (i in 0..sides) {
        val theta = 2.0 * Math.PI * (i % sides) / sides
        val lat = center.latitude + dLat * sin(theta)
        val lon = center.longitude + dLon * cos(theta)
        ring.add(Point.fromLngLat(lon, lat))
    }
    return Polygon.fromLngLats(listOf(ring))
}

private const val SRC_GUIDES = "af-guides-src"
private const val SRC_ZONE = "af-zone-src"
private const val SRC_FRONTAGE = "af-frontage-src"
private const val SRC_CORE = "af-core-src"
private const val SRC_DOTS = "af-dots-src"
private const val SRC_PINS = "af-pins-src"
private const val SRC_LOCKER = "af-locker-src"
private const val SRC_USER = "af-user-src"

private const val LYR_GUIDES = "af-guides-lyr"
private const val LYR_ZONE_FILL = "af-zone-fill"
private const val LYR_ZONE_STROKE = "af-zone-stroke"
private const val LYR_FRONTAGE = "af-frontage-lyr"
private const val LYR_CORE_FILL = "af-core-fill"
private const val LYR_CORE_STROKE = "af-core-stroke"
private const val LYR_DOTS_OUTER = "af-dots-outer"
private const val LYR_DOTS_CORE = "af-dots-core"
private const val LYR_PINS = "af-pins-lyr"
private const val LYR_LOCKER = "af-locker-lyr"
private const val LYR_USER_HALO = "af-user-halo"
private const val LYR_USER_DOT = "af-user-dot"

private const val HYBRID_SATELLITE_STYLE_JSON = """{
  "version": 8,
  "sources": {
    "esri-imagery": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    },
    "esri-labels": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/Reference/World_Transportation/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    {"id": "sat-base", "type": "raster", "source": "esri-imagery"},
    {"id": "sat-labels", "type": "raster", "source": "esri-labels"}
  ]
}"""

private const val STREET_MAP_STYLE_JSON = """{
  "version": 8,
  "sources": {
    "carto-voyager": {
      "type": "raster",
      "tiles": [
        "https://a.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png",
        "https://b.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}@2x.png"
      ],
      "tileSize": 256,
      "maxzoom": 19
    }
  },
  "layers": [
    {"id": "street-base", "type": "raster", "source": "carto-voyager"}
  ]
}"""
