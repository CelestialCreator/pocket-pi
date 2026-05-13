package com.zosma.pocketpi.api

import android.content.Context
import android.util.Log
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Localhost HTTP/1.1 server that exposes Android capabilities (notifications,
 * intents, location, camera, mic, clipboard, …) to the Pi agent running inside
 * the bundled Termux runtime. Same UID, file-perm-gated bearer token, bound
 * to 127.0.0.1 only — no exposure to anything off-device.
 *
 * Lifecycle: owned by [com.zosma.pocketpi.service.PocketPiService]. start()
 * binds + spawns the accept loop, stop() closes the socket + cancels the
 * scope. Token is rotated on every start.
 */
class PocketPiApiServer(private val ctx: Context) {
    private var serverSocket: ServerSocket? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handlers = Handlers(ctx)

    @Volatile
    private var token: String = ""

    fun start() {
        if (serverSocket != null) return
        token = Token.rotate(Bootstrapper.prefixDir(ctx))
        val socket = ServerSocket(PORT, BACKLOG, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        Log.i(TAG, "Listening on 127.0.0.1:$PORT")
        scope.launch { acceptLoop(socket) }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope.cancel()
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                break
            }
            scope.launch { handleClient(client) }
        }
    }

    private suspend fun handleClient(client: Socket) {
        client.use { sock ->
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
                val req = parseRequest(reader) ?: run {
                    write(sock.getOutputStream(), 400, "bad request"); return
                }
                if (!authOk(req)) {
                    writeJson(sock.getOutputStream(), 401, errorJson("unauthorized")); return
                }
                val response = dispatch(req)
                writeJson(sock.getOutputStream(), response.status, response.body)
            } catch (e: Throwable) {
                Log.w(TAG, "request handler error: $e")
                runCatching { writeJson(sock.getOutputStream(), 500, errorJson(e.message ?: "internal error")) }
            }
        }
    }

    private fun authOk(req: HttpRequest): Boolean {
        // /health is unauthenticated so a shell can probe liveness without
        // needing the token file readable.
        if (req.path == "/health") return true
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val provided = header.substring(7).trim()
        return provided == token && provided.isNotEmpty()
    }

    private suspend fun dispatch(req: HttpRequest): HttpResponse {
        val body = if (req.body.isEmpty()) null else runCatching {
            Json.parseToJsonElement(req.body) as? JsonObject
        }.getOrNull()
        return when (req.path) {
            "/health" -> HttpResponse(200, buildJsonObject { put("ok", true) })
            "/notify" -> handlers.notify(body)
            "/toast" -> handlers.toast(body)
            "/tts" -> handlers.ttsSpeak(body)
            "/share" -> handlers.share(body)
            "/open-url" -> handlers.openUrl(body)
            "/intent" -> handlers.intent(body)
            "/clipboard/get" -> handlers.clipboardGet()
            "/clipboard/set" -> handlers.clipboardSet(body)
            "/battery" -> handlers.battery()
            "/location" -> handlers.location(body)
            "/camera/photo" -> handlers.cameraPhoto(body)
            "/mic/record" -> handlers.micRecord(body)
            "/inbox/list" -> handlers.inboxList()
            "/inbox/pop" -> handlers.inboxPop()
            // ---- UI automation (vendored orb-eye, gated by AccessibilityService) ----
            "/ui/screen" -> handlers.uiScreen(body)
            "/ui/tree" -> handlers.uiTree(body)
            "/ui/focused" -> handlers.uiFocused()
            "/ui/info" -> handlers.uiInfo()
            "/ui/screenshot" -> handlers.uiScreenshot()
            "/ui/find" -> handlers.uiFind(body)
            "/ui/tap" -> handlers.uiTap(body)
            "/ui/click" -> handlers.uiClick(body)
            "/ui/type" -> handlers.uiType(body)
            "/ui/swipe" -> handlers.uiSwipe(body)
            "/ui/longpress" -> handlers.uiLongPress(body)
            "/ui/scroll" -> handlers.uiScroll(body)
            "/ui/gesture" -> handlers.uiGesture(body)
            "/ui/global" -> handlers.uiGlobal(body)
            "/ui/wait" -> handlers.uiWait(body)
            "/ui/notifications" -> handlers.uiNotifications(body)
            "/ui/clipboard/get" -> handlers.uiClipboardGet()
            "/ui/clipboard/set" -> handlers.uiClipboardSet(body)
            "/ui/events/poll" -> handlers.uiEventsPoll(body)
            else -> HttpResponse(404, errorJson("no such route: ${req.path}"))
        }
    }

    private fun parseRequest(reader: BufferedReader): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val sep = line.indexOf(':')
            if (sep < 0) continue
            headers[line.substring(0, sep).trim().lowercase()] = line.substring(sep + 1).trim()
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        return HttpRequest(method, path, headers, body)
    }

    private fun write(out: OutputStream, status: Int, text: String) {
        val statusLine = "HTTP/1.1 $status ${statusText(status)}\r\n"
        val headers = "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${text.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(statusLine.toByteArray(Charsets.UTF_8))
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeJson(out: OutputStream, status: Int, body: JsonObject) {
        val text = body.toString()
        val statusLine = "HTTP/1.1 $status ${statusText(status)}\r\n"
        val headers = "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Length: ${text.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(statusLine.toByteArray(Charsets.UTF_8))
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(text.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        408 -> "Request Timeout"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    companion object {
        private const val TAG = "PocketPiApi"
        const val PORT = 9998
        private const val BACKLOG = 16

        fun errorJson(message: String): JsonObject = buildJsonObject {
            put("error", message)
        }

        fun okJson(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
            buildJsonObject(build)
    }
}

data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String,
)

data class HttpResponse(
    val status: Int,
    val body: JsonObject,
)
