package com.zosma.pocketpi

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class PocketPiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    NOTIF_CHANNEL_ID,
                    getString(R.string.service_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.service_channel_desc) },
            )
        }
    }

    companion object {
        const val NOTIF_CHANNEL_ID = "pocketpi_service"
    }
}
