package com.zosma.pocketpi.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.zosma.pocketpi.PocketPiApp
import com.zosma.pocketpi.api.HttpResponse
import com.zosma.pocketpi.api.PocketPiApiServer
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Transient foreground service for one-shot still captures. CameraX
 * ImageCapture bound to a synthetic LifecycleOwner so the camera HAL is held
 * open only while the capture is in flight.
 */
class CameraFgService : Service() {

    override fun onCreate() {
        super.onCreate()
        val notif = NotificationCompat.Builder(this, PocketPiApp.NOTIF_CHANNEL_ID)
            .setContentTitle(getString(com.zosma.pocketpi.R.string.app_name))
            .setContentText("Capturing photo…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    companion object {
        private const val TAG = "CameraFgService"
        private const val NOTIF_ID = 2002
        private val ISO = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        suspend fun captureStill(ctx: Context, camera: String, name: String?): HttpResponse {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) !=
                PackageManager.PERMISSION_GRANTED) {
                return HttpResponse(403, PocketPiApiServer.errorJson(
                    "CAMERA not granted. Open the app and accept the permission."
                ))
            }
            ContextCompat.startForegroundService(ctx, Intent(ctx, CameraFgService::class.java))
            val captureDir = File(Bootstrapper.homeDir(ctx), ".pi/agent/captures").apply { mkdirs() }
            val outFile = File(captureDir, (name?.removeSuffix(".jpg") ?: ISO.format(Date())) + ".jpg")
            val selector = if (camera == "front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            val result = withTimeoutOrNull(20_000L) {
                doCapture(ctx, selector, outFile)
            }
            ctx.stopService(Intent(ctx, CameraFgService::class.java))
            return when {
                result == null -> HttpResponse(408, PocketPiApiServer.errorJson("Camera capture timed out"))
                result is CaptureResult.Ok -> HttpResponse(200, buildJsonObject {
                    put("path", result.path)
                    put("camera", camera)
                })
                result is CaptureResult.Err -> HttpResponse(500,
                    PocketPiApiServer.errorJson("Camera capture failed: ${result.message}"))
                else -> HttpResponse(500, PocketPiApiServer.errorJson("unknown capture error"))
            }
        }

        private sealed interface CaptureResult {
            data class Ok(val path: String) : CaptureResult
            data class Err(val message: String) : CaptureResult
        }

        private suspend fun doCapture(
            ctx: Context,
            selector: CameraSelector,
            outFile: File,
        ): CaptureResult = withContext(Dispatchers.Main) {
            val owner = ManualLifecycleOwner()
            owner.start()
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            val provider = try {
                providerFuture.get()
            } catch (e: Throwable) {
                owner.stop()
                return@withContext CaptureResult.Err("CameraProvider init: ${e.message}")
            }
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            return@withContext try {
                provider.unbindAll()
                provider.bindToLifecycle(owner, selector, imageCapture)
                suspendCancellableCoroutine<CaptureResult> { cont ->
                    val opts = ImageCapture.OutputFileOptions.Builder(outFile).build()
                    imageCapture.takePicture(
                        opts,
                        Executors.newSingleThreadExecutor(),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                if (cont.isActive) cont.resume(CaptureResult.Ok(outFile.absolutePath))
                            }
                            override fun onError(exception: ImageCaptureException) {
                                if (cont.isActive) cont.resume(CaptureResult.Err(exception.message ?: "unknown"))
                            }
                        },
                    )
                }
            } catch (e: Throwable) {
                CaptureResult.Err(e.message ?: "bind failed")
            } finally {
                runCatching { provider.unbindAll() }
                owner.stop()
            }
        }

        /**
         * Minimal LifecycleOwner that's driven manually. CameraX uses the
         * Lifecycle.State to decide when to open/close the camera HAL — we
         * advance it through CREATED → STARTED → RESUMED on bind and back
         * down to DESTROYED on tear-down.
         */
        private class ManualLifecycleOwner : LifecycleOwner {
            private val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle = registry
            fun start() {
                registry.currentState = Lifecycle.State.RESUMED
            }
            fun stop() {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }
}
