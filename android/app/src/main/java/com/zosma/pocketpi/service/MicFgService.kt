package com.zosma.pocketpi.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zosma.pocketpi.PocketPiApp
import com.zosma.pocketpi.api.HttpResponse
import com.zosma.pocketpi.api.PocketPiApiServer
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Transient foreground service for one-shot audio captures. MediaRecorder
 * with AAC/MPEG_4 — small files, broad codec compatibility. Files land in
 * `$HOME/.pi/agent/captures/<ts>.m4a`.
 */
class MicFgService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notif = NotificationCompat.Builder(this, PocketPiApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(com.zosma.pocketpi.R.string.app_name))
            .setContentText("Recording audio…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "MicFgService"
        private const val NOTIF_ID = 2003
        private val ISO = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        suspend fun recordOnce(ctx: Context, seconds: Int, name: String?): HttpResponse {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED) {
                return HttpResponse(403, PocketPiApiServer.errorJson(
                    "RECORD_AUDIO not granted. Open the app and accept the permission."
                ))
            }
            ContextCompat.startForegroundService(ctx, Intent(ctx, MicFgService::class.java))
            val outDir = File(Bootstrapper.homeDir(ctx), ".pi/agent/captures").apply { mkdirs() }
            val outFile = File(outDir, (name?.removeSuffix(".m4a") ?: ISO.format(Date())) + ".m4a")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(ctx)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            return try {
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(64_000)
                    setAudioSamplingRate(44_100)
                    setOutputFile(outFile.absolutePath)
                    prepare()
                    start()
                }
                delay(seconds * 1000L)
                recorder.stop()
                recorder.release()
                HttpResponse(200, buildJsonObject {
                    put("path", outFile.absolutePath)
                    put("durationSeconds", seconds)
                    put("format", "m4a")
                })
            } catch (e: Throwable) {
                runCatching { recorder.release() }
                Log.w(TAG, "mic record failed: $e")
                HttpResponse(500, PocketPiApiServer.errorJson("Mic record failed: ${e.message}"))
            } finally {
                ctx.stopService(Intent(ctx, MicFgService::class.java))
            }
        }
    }
}
