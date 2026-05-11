package com.zosma.pocketpi.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.zosma.pocketpi.MainActivity
import com.zosma.pocketpi.PocketPiApp
import com.zosma.pocketpi.R
import com.zosma.pocketpi.pi.PiBridge

/**
 * Foreground service that owns the long-lived Pi process. Without this Android
 * will kill the agent when the app is backgrounded.
 *
 * The service exposes a static [PiBridge] that the UI consumes. We use a
 * static reference (rather than a bound service) because Pi's process and
 * its event stream are the only meaningful state — there is no IPC surface
 * worth marshalling across.
 */
class PocketPiService : Service() {
    override fun onCreate() {
        super.onCreate()
        val notif = buildNotification("Starting Pi…")
        startForeground(NOTIF_ID, notif)
        bridge = PiBridge(this).also { it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bridge?.stop()
        bridge = null
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, PocketPiApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.service_running))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(tap)
            .build()
    }

    companion object {
        const val NOTIF_ID = 1001
        @JvmStatic var bridge: PiBridge? = null
            private set
    }
}
