package com.zosma.pocketpi.api

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zosma.pocketpi.PocketPiApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Per-request handlers. Each one consumes a JSON body and returns an
 * [HttpResponse]. Methods are `suspend` because some routes (mic record,
 * location wait) block on async Android callbacks; the simpler ones return
 * synchronously.
 *
 * Threading: most Android APIs we touch (NotificationManager, ClipboardManager,
 * Intent, BatteryManager) are thread-safe and we run them on the IO dispatcher
 * the API server is using. Things that need main thread (Toast,
 * TextToSpeech.speak after init) hop via withContext(Dispatchers.Main).
 */
internal class Handlers(private val ctx: Context) {

    // --- /notify --------------------------------------------------------------
    suspend fun notify(body: JsonObject?): HttpResponse {
        val title = body.stringOrNull("title") ?: return badRequest("title required")
        val content = body.stringOrNull("content")
            ?: body.stringOrNull("body")
            ?: return badRequest("content required")
        val priority = when (body.stringOrNull("priority")) {
            "min" -> NotificationCompat.PRIORITY_MIN
            "low" -> NotificationCompat.PRIORITY_LOW
            "high" -> NotificationCompat.PRIORITY_HIGH
            "max" -> NotificationCompat.PRIORITY_MAX
            else -> NotificationCompat.PRIORITY_DEFAULT
        }
        val id = System.currentTimeMillis().toInt()
        val n = NotificationCompat.Builder(ctx, PocketPiApp.NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        return runCatching {
            NotificationManagerCompat.from(ctx).notify(id, n)
            HttpResponse(200, buildJsonObject { put("id", id) })
        }.getOrElse { HttpResponse(500, PocketPiApiServer.errorJson(it.message ?: "notify failed")) }
    }

    // --- /toast ---------------------------------------------------------------
    suspend fun toast(body: JsonObject?): HttpResponse {
        val text = body.stringOrNull("text") ?: return badRequest("text required")
        withContext(Dispatchers.Main) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
        }
        return HttpResponse(200, okMessage("toasted"))
    }

    // --- /tts -----------------------------------------------------------------
    private var tts: TextToSpeech? = null

    @Volatile
    private var ttsReady: Boolean = false

    private suspend fun ensureTts(): Boolean = withContext(Dispatchers.Main) {
        if (ttsReady) return@withContext true
        var initOk = false
        val latch = java.util.concurrent.CountDownLatch(1)
        tts = TextToSpeech(ctx) { status ->
            initOk = status == TextToSpeech.SUCCESS
            ttsReady = initOk
            latch.countDown()
        }
        // Initialization completes off-thread; wait briefly to let the engine
        // settle so the first speak() call doesn't no-op.
        runCatching { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) }
        if (initOk) tts?.language = Locale.getDefault()
        initOk
    }

    suspend fun ttsSpeak(body: JsonObject?): HttpResponse {
        val text = body.stringOrNull("text") ?: return badRequest("text required")
        if (!ensureTts()) {
            return HttpResponse(503, PocketPiApiServer.errorJson("TTS engine unavailable"))
        }
        withContext(Dispatchers.Main) {
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "pocketpi-${System.currentTimeMillis()}")
        }
        return HttpResponse(200, okMessage("spoken"))
    }

    // --- /share ---------------------------------------------------------------
    suspend fun share(body: JsonObject?): HttpResponse {
        val text = body.stringOrNull("text")
        val path = body.stringOrNull("path")
        val title = body.stringOrNull("title") ?: "Share"
        val mime = body.stringOrNull("type") ?: if (path != null) "*/*" else "text/plain"

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            if (text != null) putExtra(Intent.EXTRA_TEXT, text)
            if (path != null) {
                putExtra(Intent.EXTRA_STREAM, Uri.parse("file://$path"))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        val chooser = Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(chooser)
            HttpResponse(200, okMessage(if (path != null) "shared: $path" else "shared text"))
        }.getOrElse { HttpResponse(500, PocketPiApiServer.errorJson("startActivity failed: ${it.message}")) }
    }

    // --- /open-url ------------------------------------------------------------
    suspend fun openUrl(body: JsonObject?): HttpResponse {
        val url = body.stringOrNull("url") ?: return badRequest("url required")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            ctx.startActivity(intent)
            HttpResponse(200, okMessage("opened: $url"))
        }.getOrElse { HttpResponse(500, PocketPiApiServer.errorJson("startActivity failed: ${it.message}")) }
    }

    // --- /intent --------------------------------------------------------------
    suspend fun intent(body: JsonObject?): HttpResponse {
        if (body == null) return badRequest("intent body required")
        val action = body.stringOrNull("action")
            ?: return badRequest("action required")
        val intent = Intent(action)
        body.stringOrNull("data")?.let { intent.data = Uri.parse(it) }
        body.stringOrNull("type")?.let {
            if (intent.data != null) intent.setDataAndType(intent.data, it) else intent.type = it
        }
        body.stringOrNull("package")?.let { intent.setPackage(it) }
        val componentPackage = body.stringOrNull("componentPackage")
        val componentClass = body.stringOrNull("componentClass")
        if (componentPackage != null && componentClass != null) {
            intent.component = ComponentName(componentPackage, componentClass)
        }
        val extras = body["extras"] as? JsonObject
        if (extras != null) intent.putExtras(extras.toBundle())
        val categories = body["categories"] as? JsonArray
        categories?.forEach { c ->
            (c as? JsonPrimitive)?.contentOrNull()?.let { intent.addCategory(it) }
        }
        // FLAG_ACTIVITY_NEW_TASK is required when starting an activity from a
        // non-Activity context (we're inside a Service-owned coroutine).
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val flags = (body["flags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull()?.toIntOrNull() }
            ?: emptyList()
        flags.forEach { intent.addFlags(it) }
        return runCatching {
            ctx.startActivity(intent)
            HttpResponse(200, okMessage("started: $action"))
        }.getOrElse {
            HttpResponse(500, PocketPiApiServer.errorJson("startActivity failed: ${it.message}"))
        }
    }

    // --- /clipboard/get -------------------------------------------------------
    suspend fun clipboardGet(): HttpResponse = withContext(Dispatchers.Main) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(ctx)?.toString() ?: ""
        HttpResponse(200, buildJsonObject { put("text", text) })
    }

    // --- /clipboard/set -------------------------------------------------------
    suspend fun clipboardSet(body: JsonObject?): HttpResponse {
        val text = body.stringOrNull("text") ?: return badRequest("text required")
        withContext(Dispatchers.Main) {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("pocket-pi", text))
        }
        return HttpResponse(200, okMessage("copied"))
    }

    // --- /battery -------------------------------------------------------------
    @Suppress("DEPRECATION")
    fun battery(): HttpResponse {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val sticky: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val tempTenths = sticky?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val health = sticky?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val plugged = sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return HttpResponse(200, buildJsonObject {
            put("percent", capacity)
            put("charging", charging)
            put("status", statusName(status))
            put("health", healthName(health))
            put("plugged", pluggedName(plugged))
            put("temperatureC", if (tempTenths >= 0) tempTenths / 10.0 else null as Double?)
        })
    }

    private fun statusName(s: Int): String = when (s) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not-charging"
        else -> "unknown"
    }
    private fun healthName(h: Int): String = when (h) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }
    private fun pluggedName(p: Int): String = when (p) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        0 -> "unplugged"
        else -> "unknown"
    }

    // --- /location ------------------------------------------------------------
    suspend fun location(body: JsonObject?): HttpResponse {
        return com.zosma.pocketpi.service.LocationFgService.requestSingleFix(
            ctx,
            body.stringOrNull("provider") ?: "fused",
            (body.numberOrNull("timeoutSeconds") ?: 15.0).toInt(),
        )
    }

    // --- /camera/photo --------------------------------------------------------
    suspend fun cameraPhoto(body: JsonObject?): HttpResponse {
        return com.zosma.pocketpi.service.CameraFgService.captureStill(
            ctx,
            body.stringOrNull("camera") ?: "back",
            body.stringOrNull("name"),
        )
    }

    // --- /mic/record ----------------------------------------------------------
    suspend fun micRecord(body: JsonObject?): HttpResponse {
        val seconds = (body.numberOrNull("seconds") ?: 5.0).toInt().coerceIn(1, 300)
        return com.zosma.pocketpi.service.MicFgService.recordOnce(
            ctx,
            seconds,
            body.stringOrNull("name"),
        )
    }

    // --- /inbox/list, /inbox/pop ----------------------------------------------
    suspend fun inboxList(): HttpResponse = Inbox.list(ctx)
    suspend fun inboxPop(): HttpResponse = Inbox.pop(ctx)

    // --- helpers --------------------------------------------------------------
    private fun badRequest(message: String) = HttpResponse(400, PocketPiApiServer.errorJson(message))
    private fun okMessage(text: String) = buildJsonObject { put("ok", true); put("message", text) }
}

internal fun JsonObject?.stringOrNull(key: String): String? =
    this?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull() }

internal fun JsonObject?.numberOrNull(key: String): Double? =
    this?.get(key)?.let { (it as? JsonPrimitive)?.doubleOrNullSafe() }

private fun JsonPrimitive.contentOrNull(): String? =
    if (isString) content else content.takeIf { it.isNotEmpty() }

private fun JsonPrimitive.doubleOrNullSafe(): Double? =
    content.toDoubleOrNull()

internal fun JsonObject.toBundle(): Bundle {
    val b = Bundle()
    for ((k, v) in this) {
        val p = v as? JsonPrimitive ?: continue
        when {
            p.isString -> b.putString(k, p.content)
            p.content == "true" || p.content == "false" -> b.putBoolean(k, p.content == "true")
            p.content.toLongOrNull() != null -> b.putLong(k, p.content.toLong())
            p.content.toDoubleOrNull() != null -> b.putDouble(k, p.content.toDouble())
            else -> b.putString(k, p.content)
        }
    }
    return b
}
