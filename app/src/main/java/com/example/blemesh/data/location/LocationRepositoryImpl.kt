package com.example.blemesh.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import com.example.blemesh.domain.model.GeoPoint
import com.example.blemesh.domain.repository.LocationRepository
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

@SuppressLint("MissingPermission")
class LocationRepositoryImpl(private val context: Context) : LocationRepository {

    private val manager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override suspend fun currentLocation(): GeoPoint? {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null

        val location: Location? = try {
            withTimeoutOrNull(LOCATION_TIMEOUT_MS) { fetch() } ?: lastKnown()
        } catch (e: SecurityException) {
            null
        }
        return location?.let { GeoPoint(it.latitude, it.longitude) }
    }

    private suspend fun fetch(): Location? {
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return lastKnown()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return lastKnown()

        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { loc ->
                if (cont.isActive) cont.resume(loc)
            }
        }
    }

    private fun lastKnown(): Location? =
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.time }

    private companion object {
        const val LOCATION_TIMEOUT_MS = 15_000L
    }
}
