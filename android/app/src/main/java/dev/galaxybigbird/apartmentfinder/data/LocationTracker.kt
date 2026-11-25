package dev.galaxybigbird.apartmentfinder.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import dev.galaxybigbird.apartmentfinder.core.Coordinate
import dev.galaxybigbird.apartmentfinder.core.UserLocationFix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LiveLocationState(
    val fix: UserLocationFix? = null,
    val compassHeading: Float? = null,
    val hasPermission: Boolean = false,
    val active: Boolean = false,
    val statusText: String = "Location ready"
) {
    val effectiveBearing: Float?
        get() = fix?.takeIf { it.isFresh() }?.bearingDegrees ?: compassHeading
}

/**
 * Pure Android LocationManager + Rotation Vector compass tracker (works on all Android phones,
 * no Google Play Services hard requirement).
 */
class LocationTracker(context: Context) : LocationListener, SensorEventListener {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val _state = MutableStateFlow(LiveLocationState(hasPermission = checkPermission()))
    val state: StateFlow<LiveLocationState> = _state.asStateFlow()

    private var smoothedHeading: Float? = null
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun checkPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val permitted = checkPermission()
        if (!permitted) {
            _state.value = _state.value.copy(
                hasPermission = false,
                active = false,
                statusText = "Location permission needed"
            )
            return
        }
        if (_state.value.active) return

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var bestLast: Location? = null
        for (p in providers) {
            runCatching {
                if (locationManager.isProviderEnabled(p)) {
                    locationManager.requestLocationUpdates(p, 1500L, 1.5f, this)
                    val last = locationManager.getLastKnownLocation(p)
                    if (last != null && (bestLast == null || last.time > bestLast!!.time)) {
                        bestLast = last
                    }
                }
            }
        }

        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }

        val initialFix = bestLast?.toFix()
        _state.value = _state.value.copy(
            hasPermission = true,
            active = true,
            fix = initialFix ?: _state.value.fix,
            statusText = if (initialFix != null) "GPS ±${initialFix.accuracyMeters.toInt()} m" else "Waiting for GPS…"
        )
    }

    fun stop() {
        runCatching { locationManager.removeUpdates(this) }
        runCatching { sensorManager.unregisterListener(this) }
        _state.value = _state.value.copy(active = false)
    }

    override fun onLocationChanged(location: Location) {
        val fix = location.toFix()
        _state.value = _state.value.copy(
            hasPermission = true,
            fix = fix,
            statusText = "GPS ±${fix.accuracyMeters.toInt()} m"
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        val rawDeg = ((Math.toDegrees(orientationAngles[0].toDouble()) + 360.0) % 360.0).toFloat()
        val prev = smoothedHeading
        val next = if (prev == null) {
            rawDeg
        } else {
            var delta = rawDeg - prev
            if (delta > 180f) delta -= 360f
            if (delta < -180f) delta += 360f
            (prev + delta * 0.2f + 360f) % 360f
        }
        smoothedHeading = next
        _state.value = _state.value.copy(compassHeading = next)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    @Deprecated("Deprecated in Android API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private fun Location.toFix(): UserLocationFix = UserLocationFix(
        coordinate = Coordinate(latitude, longitude),
        accuracyMeters = accuracy,
        bearingDegrees = if (hasBearing() && speed > 0.8f) bearing else null,
        timestampMs = time.takeIf { it > 0 } ?: System.currentTimeMillis()
    )
}
