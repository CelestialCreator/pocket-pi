package com.zosma.pocketpi.service

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zosma.pocketpi.PocketPiApp
import com.zosma.pocketpi.api.HttpResponse
import com.zosma.pocketpi.api.PocketPiApiServer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

/**
 * Transient foreground service for one-shot location fixes. Lifetime: started
 * at the top of [requestSingleFix], stopped as soon as the fix lands or the
 * caller times out. Using a separate FGS (instead of leaning on
 * PocketPiService) so the location-icon privacy dot only shows when we're
 * actually listening.
 *
 * Uses android.location.LocationManager (not Google Play Services'
 * FusedLocationProvider) so the build has no GMS dependency — works on AOSP
 * emulators and degoogled phones, where FusedLocationProviderClient hangs.
 */
class LocationFgService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notif = NotificationCompat.Builder(this, PocketPiApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(com.zosma.pocketpi.R.string.app_name))
            .setContentText("Getting location…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "LocationFgService"
        private const val NOTIF_ID = 2001

        /**
         * Suspends until a fix lands or [timeoutSeconds] elapses. The
         * provider hint accepts "gps", "network", "passive", or "fused"
         * (the latter just means we try gps and network in parallel).
         */
        suspend fun requestSingleFix(
            ctx: Context,
            providerHint: String,
            timeoutSeconds: Int,
        ): HttpResponse {
            if (!hasLocationPermission(ctx)) {
                return HttpResponse(403, PocketPiApiServer.errorJson(
                    "ACCESS_FINE_LOCATION not granted. Open the app and accept the permission."
                ))
            }
            ContextCompat.startForegroundService(ctx, Intent(ctx, LocationFgService::class.java))
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = pickProviders(lm, providerHint)
            if (providers.isEmpty()) {
                ctx.stopService(Intent(ctx, LocationFgService::class.java))
                return HttpResponse(503, PocketPiApiServer.errorJson(
                    "No usable location providers (gps/network disabled)"
                ))
            }
            val fix = withTimeoutOrNull(timeoutSeconds * 1000L) {
                awaitFix(lm, providers)
            }
            ctx.stopService(Intent(ctx, LocationFgService::class.java))
            return if (fix != null) {
                HttpResponse(200, buildJsonObject {
                    put("latitude", fix.latitude)
                    put("longitude", fix.longitude)
                    put("accuracyMeters", fix.accuracy.toDouble())
                    put("altitudeMeters", fix.altitude)
                    put("provider", fix.provider ?: "unknown")
                    put("timestamp", fix.time)
                })
            } else {
                HttpResponse(408, PocketPiApiServer.errorJson("Location timed out after ${timeoutSeconds}s"))
            }
        }

        private fun hasLocationPermission(ctx: Context): Boolean {
            return ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }

        private fun pickProviders(lm: LocationManager, hint: String): List<String> {
            val available = lm.allProviders ?: emptyList()
            val enabled = available.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
            return when (hint) {
                "gps" -> listOf(LocationManager.GPS_PROVIDER).filter { it in enabled }
                "network" -> listOf(LocationManager.NETWORK_PROVIDER).filter { it in enabled }
                "passive" -> listOf(LocationManager.PASSIVE_PROVIDER).filter { it in available }
                else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                    .filter { it in enabled }
            }
        }

        private suspend fun awaitFix(lm: LocationManager, providers: List<String>): Location {
            return suspendCancellableCoroutine { cont ->
                val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                val listeners = mutableListOf<LocationListener>()
                val onFix: (Location) -> Unit = { loc ->
                    if (cont.isActive) {
                        listeners.forEach {
                            runCatching { lm.removeUpdates(it) }
                        }
                        cont.resume(loc)
                    }
                }
                mainHandler.post {
                    try {
                        for (p in providers) {
                            // Try last known first — often instant, no need to wait for a fresh fix.
                            @Suppress("MissingPermission")
                            val cached = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                            if (cached != null && System.currentTimeMillis() - cached.time < 60_000) {
                                onFix(cached); return@post
                            }
                        }
                        for (p in providers) {
                            val listener = object : LocationListener {
                                override fun onLocationChanged(location: Location) { onFix(location) }
                                override fun onProviderDisabled(provider: String) {}
                                override fun onProviderEnabled(provider: String) {}
                                @Deprecated("Deprecated in Java")
                                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                            }
                            listeners.add(listener)
                            @Suppress("MissingPermission")
                            lm.requestLocationUpdates(p, 0L, 0f, listener, android.os.Looper.getMainLooper())
                        }
                    } catch (e: SecurityException) {
                        cont.resume(Location("unknown").apply { latitude = 0.0; longitude = 0.0 })
                    }
                }
                cont.invokeOnCancellation {
                    mainHandler.post {
                        listeners.forEach { runCatching { lm.removeUpdates(it) } }
                    }
                }
            }
        }
    }
}
