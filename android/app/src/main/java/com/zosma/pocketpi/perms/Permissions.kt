package com.zosma.pocketpi.perms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Runtime permission helpers. The actual UI flow is in OnboardingScreen;
 * this file is the boundary between Compose code and the Android perms API.
 */
object Permissions {
    val RUNTIME = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
            add(Manifest.permission.READ_MEDIA_IMAGES)
        }
    }.toTypedArray()

    fun missing(ctx: Context): List<String> =
        RUNTIME.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }

    fun request(activity: Activity) {
        val m = missing(activity).toTypedArray()
        if (m.isNotEmpty()) ActivityCompat.requestPermissions(activity, m, 1001)
    }

    fun isBatteryOptimised(ctx: Context): Boolean {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(ctx.packageName)
    }

    /** Open the system "ignore battery optimisations" prompt. */
    fun requestIgnoreBattery(ctx: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
    }
}
