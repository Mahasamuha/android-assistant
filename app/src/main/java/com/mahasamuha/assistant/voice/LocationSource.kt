package com.mahasamuha.assistant.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** City-level location for web search and the prompt. Never more precise than that leaves the phone. */
data class ApproximateLocation(val city: String?, val region: String?, val countryCode: String?) {
    val description: String? get() = listOfNotNull(city, region).joinToString(", ").takeIf { it.isNotEmpty() }
}

interface LocationSource {
    /** Null when permission is off or no location is available in time. */
    suspend fun current(): ApproximateLocation?
}

/** Coarse fused location, reverse-geocoded to a city. */
class PlatformLocationSource(context: Context) : LocationSource {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)

    override suspend fun current(): ApproximateLocation? {
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val location = recentLocation() ?: withTimeoutOrNull(3_000) { freshLocation() } ?: return null
        val address = withTimeoutOrNull(3_000) { geocode(location) } ?: return null
        return ApproximateLocation(
            city = address.locality ?: address.subAdminArea,
            region = address.adminArea,
            countryCode = address.countryCode,
        ).also { Log.i(TAG, "Location: ${it.description}") }
    }

    @Suppress("MissingPermission") // checked in current()
    private fun recentLocation(): Location? =
        locationManager.getLastKnownLocation(LocationManager.FUSED_PROVIDER)
            ?.takeIf { System.currentTimeMillis() - it.time < 30 * 60_000 }

    @Suppress("MissingPermission") // checked in current()
    private suspend fun freshLocation(): Location? = suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        cont.invokeOnCancellation { signal.cancel() }
        locationManager.getCurrentLocation(LocationManager.FUSED_PROVIDER, signal, appContext.mainExecutor) { cont.resume(it) }
    }

    private suspend fun geocode(location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        return suspendCancellableCoroutine { cont ->
            Geocoder(appContext, Locale.getDefault()).getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) = cont.resume(addresses.firstOrNull())

                override fun onError(errorMessage: String?) {
                    Log.w(TAG, "Geocoding failed: $errorMessage")
                    cont.resume(null)
                }
            })
        }
    }
}
