package io.github.nir0k.jotter.data.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import io.github.nir0k.jotter.core.format.GeoLocation
import io.github.nir0k.jotter.core.format.now
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** The permissions a geotag needs; the person may grant the approximate one only. */
val LocationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

/** How long a new note waits for a position before going without one. */
private const val LocateTimeoutMs = 10_000L

/** Tells whether the application may read the position, precise or approximate. */
fun hasLocationPermission(context: Context): Boolean =
    LocationPermissions.any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** Rounds a coordinate to five decimals, as geotags store them (about a metre). */
private fun rounded(value: Double): Double = BigDecimal(value).setScale(5, RoundingMode.HALF_UP).toDouble()

/**
 * Asks the platform's location service for the current position: the fused provider, otherwise the network or GPS provider, without Google Play services. A
 * position not available within ten seconds, a disabled service and a missing permission give none.
 *
 * @return The geotag with its coordinates rounded to five decimals and the time it was taken, or null.
 */
suspend fun currentLocation(context: Context): GeoLocation? {
    if (!hasLocationPermission(context)) return null
    val manager = context.getSystemService(LocationManager::class.java) ?: return null
    val enabled = manager.getProviders(true)
    val provider = listOfNotNull(
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER,
    ).firstOrNull { it in enabled } ?: return null
    val location = withTimeoutOrNull(LocateTimeoutMs) {
        suspendCancellableCoroutine<Location?> { continuation ->
            val cancel = CancellationSignal()
            continuation.invokeOnCancellation { cancel.cancel() }
            try {
                manager.getCurrentLocation(provider, cancel, context.mainExecutor) {
                    if (continuation.isActive) continuation.resume(it)
                }
            } catch (_: SecurityException) {
                // The permission was withdrawn between the check and the request.
                continuation.resume(null)
            }
        }
    } ?: return null
    return GeoLocation(
        rounded(location.latitude),
        rounded(location.longitude),
        // The platform reports a float; its shortest decimal form keeps the file free of float noise.
        location.accuracy.takeIf { location.hasAccuracy() }?.toString()?.toDouble(),
        now(),
    )
}
