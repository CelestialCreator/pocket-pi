package com.zosma.pocketpi.api

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Inbound intent queue. Anything `MainActivity.onNewIntent` receives that
 * looks like a share or a `pi://agent/…` deep link gets serialized to a JSON
 * file under `$HOME/.pi/agent/inbox/`. The Pi extension reads/drains the
 * directory via `/inbox/list` and `/inbox/pop`.
 *
 * File naming: `<ISO-millis>-<rand4>.json`. Sorted alphabetically =
 * chronological. `pop` returns the oldest and deletes it.
 */
object Inbox {
    private const val TAG = "PocketPiInbox"
    private val ISO = SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    private fun dir(ctx: Context): File =
        File(Bootstrapper.homeDir(ctx), ".pi/agent/inbox").apply { mkdirs() }

    /** Called from MainActivity.onNewIntent. Quietly drops anything we can't serialize. */
    fun writeInboxEntry(ctx: Context, intent: Intent) {
        runCatching {
            val payload = serialize(intent) ?: return@runCatching
            val ts = ISO.format(Date())
            val rand = SecureRandom().nextInt(0xFFFF).toString(16).padStart(4, '0')
            val out = File(dir(ctx), "$ts-$rand.json")
            out.writeText(payload.toString())
            Log.i(TAG, "wrote inbox entry: ${out.name}")
        }.onFailure { Log.w(TAG, "inbox write failed: $it") }
    }

    private fun serialize(intent: Intent): JsonObject? {
        // Skip the launcher MAIN intent so it doesn't pollute the queue.
        if (intent.action == Intent.ACTION_MAIN) return null
        val action = intent.action ?: return null
        return buildJsonObject {
            put("action", action)
            intent.dataString?.let { put("data", it) }
            intent.type?.let { put("type", it) }
            intent.scheme?.let { put("scheme", it) }
            intent.`package`?.let { put("package", it) }
            val extras = intent.extras
            if (extras != null && !extras.isEmpty) {
                put("extras", buildJsonObject {
                    for (key in extras.keySet()) {
                        when (val v = extras.get(key)) {
                            is String -> put(key, v)
                            is Int -> put(key, v)
                            is Long -> put(key, v)
                            is Boolean -> put(key, v)
                            is Float -> put(key, v)
                            is Double -> put(key, v)
                            is Uri -> put(key, v.toString())
                            null -> put(key, null as String?)
                            else -> put(key, v.toString())
                        }
                    }
                })
            }
            put("receivedAt", System.currentTimeMillis())
        }
    }

    suspend fun list(ctx: Context): HttpResponse {
        val files = dir(ctx).listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()
        val entries = buildJsonArray {
            for (f in files) {
                add(buildJsonObject {
                    put("name", f.name)
                    put("size", f.length())
                })
            }
        }
        return HttpResponse(200, buildJsonObject { put("entries", entries) })
    }

    suspend fun pop(ctx: Context): HttpResponse {
        val files = dir(ctx).listFiles { f -> f.isFile && f.extension == "json" }
            ?.sortedBy { it.name } ?: emptyList()
        val first = files.firstOrNull() ?: return HttpResponse(200, buildJsonObject {
            put("empty", true)
        })
        val body = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(first.readText()) as? JsonObject
        }.getOrNull() ?: return HttpResponse(500, PocketPiApiServer.errorJson("malformed inbox file: ${first.name}"))
        first.delete()
        return HttpResponse(200, buildJsonObject {
            put("name", first.name)
            put("entry", body)
        })
    }
}
