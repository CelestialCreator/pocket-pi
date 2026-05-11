package com.zosma.pocketpi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Single-WebView surface. Two UI options can be the chat front-end:
 *
 *   - pi-agent-dashboard (preferred, this branch) — binds :8000, root path,
 *     no auth needed for localhost. Built-in slash commands, session history,
 *     model picker. Spawned by PiBridge alongside pi --mode rpc.
 *   - pi-mobile (fallback) — binds :4100, requires apiToken POST to
 *     /_auth/login before /mobile.
 *
 * Lifecycle states:
 *   1. Server not yet up (first run = postinstall, returning users = bind wait)
 *      → live tail of postinstall.log + status + Retry button
 *   2. Server up
 *      → WebView loads http://127.0.0.1:8000/ (dashboard) or, if 8000 isn't
 *        bound after the wait, posts apiToken to :4100/_auth/login as before.
 */
private const val DASHBOARD_PORT = 8000
@Composable
fun WebViewScreen() {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<WebserverInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("Starting Pi…") }
    var logTail by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        info = null; error = null
        val resolved = withContext(Dispatchers.IO) {
            runWaitLoop(ctx) { p, t -> phase = p; logTail = t }
        }
        resolved.onSuccess { info = it }.onFailure { error = it.message }
    }

    var showConfig by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            error != null -> ErrorPane(error!!, logTail, onRetry = { attempt++ }, onConfig = { showConfig = true })
            info == null -> LoadingPane(phase, logTail, onConfig = { showConfig = true })
            else -> Web(info!!)
        }
        // ⚙ FAB is always rendered, in every state, so a user can reach
        // Re-run setup / Restart Pi from the loading screen too — the most
        // common bind-stuck recovery path.
        FilledIconButton(
            onClick = { showConfig = true },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(36.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Text("⚙", style = MaterialTheme.typography.titleMedium)
        }
        if (showConfig) {
            ConfigSheet(onDismiss = { showConfig = false })
        }
    }
}

private enum class UiMode { DASHBOARD, PI_MOBILE }
private data class WebserverInfo(val mode: UiMode, val port: Int, val token: String)

private suspend fun runWaitLoop(
    ctx: Context,
    onProgress: (phase: String, logTail: String) -> Unit,
): Result<WebserverInfo> {
    val infoFile = File(Bootstrapper.homeDir(ctx), ".pi/agent/webserver-info.json")
    val logFile = File(Bootstrapper.homeDir(ctx), ".pi/agent/postinstall.log")
    val deadline = System.currentTimeMillis() + 25 * 60 * 1000L
    // Give the dashboard a generous 20s after pi-webserver binds before we
    // give up and fall back to pi-mobile. On a cold start the bridge takes a
    // few seconds to dial out and the dashboard server even longer to bind.
    var pmFirstSeenMs: Long = 0
    while (System.currentTimeMillis() < deadline) {
        val tail = readTail(logFile, 18)
        if (probe(DASHBOARD_PORT)) {
            onProgress("Connected (dashboard)", tail)
            return Result.success(WebserverInfo(UiMode.DASHBOARD, DASHBOARD_PORT, ""))
        }
        val phase = when {
            !infoFile.exists() && !logFile.exists() -> "Preparing first-run install…"
            !infoFile.exists() -> "Installing packages (apt + npm). 5–10 min on first launch."
            else -> {
                val parsed = parseInfo(infoFile)
                if (parsed != null && probe(parsed.port)) {
                    if (pmFirstSeenMs == 0L) pmFirstSeenMs = System.currentTimeMillis()
                    val waitedMs = System.currentTimeMillis() - pmFirstSeenMs
                    if (waitedMs > 20_000L) {
                        onProgress("Connected (pi-mobile fallback)", tail)
                        return Result.success(parsed)
                    }
                    "pi-mobile up — waiting ${20 - waitedMs / 1000}s for dashboard…"
                } else "Pi installed. Waiting for the web server to bind…"
            }
        }
        onProgress(phase, tail)
        delay(2000)
    }
    return Result.failure(IllegalStateException(
        "Pi didn't come up within 25 minutes. Check the log below; tap Retry once it looks finished."
    ))
}

private fun parseInfo(f: File): WebserverInfo? = runCatching {
    val obj = JSONObject(f.readText())
    WebserverInfo(
        mode = UiMode.PI_MOBILE,
        port = obj.optInt("port", 4100),
        token = obj.optString("token"),
    )
}.getOrNull()?.takeIf { it.token.isNotEmpty() }

private fun readTail(f: File, n: Int): String {
    if (!f.exists()) return ""
    return try {
        val lines = f.readLines()
        lines.takeLast(n).joinToString("\n")
    } catch (_: Throwable) { "" }
}

private fun probe(port: Int): Boolean = runCatching {
    val c = URL("http://127.0.0.1:$port/").openConnection() as HttpURLConnection
    c.connectTimeout = 1500
    c.readTimeout = 1500
    c.instanceFollowRedirects = false
    c.connect()
    c.responseCode in 200..399
}.getOrDefault(false)

@Composable
private fun LoadingPane(phase: String, logTail: String, onConfig: () -> Unit) {
    // After 15s the bind wait has crossed the "something is probably wrong"
    // threshold (returning users: pi-webserver should bind within a few
    // seconds of service start). Surface the recovery escape hatch so the
    // user doesn't have to discover the corner FAB.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(15_000)
        stalled = true
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pocket Pi", style = MaterialTheme.typography.headlineSmall)
        CircularProgressIndicator()
        Text(phase, style = MaterialTheme.typography.bodyMedium)
        if (logTail.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("Install log (latest):", style = MaterialTheme.typography.labelSmall)
            Text(
                logTail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            )
        }
        if (stalled) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Taking longer than expected. Open setup options to restart Pi or re-run the installer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onConfig) { Text("Open setup options") }
        }
    }
}

@Composable
private fun ErrorPane(
    message: String,
    logTail: String,
    onRetry: () -> Unit,
    onConfig: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pi didn't start", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text("Retry") }
        OutlinedButton(onClick = onConfig) { Text("Open setup options") }
        if (logTail.isNotEmpty()) {
            Text("Install log (last 18 lines):", style = MaterialTheme.typography.labelSmall)
            Text(logTail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun Web(info: WebserverInfo) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mediaPlaybackRequiresUserGesture = false
                    setSupportZoom(false)
                    builtInZoomControls = false
                    allowFileAccess = false
                    allowContentAccess = false
                    // Standard mobile-viewport settings. pi-mobile's
                    // `html,body { height: 100% }` chain would normally
                    // collapse to 0px in WebView, but the onPageFinished
                    // injection below anchors the layout to window.innerHeight.
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Strip the `; wv` identifier so pi-mobile's UA detection
                    // treats us like regular Chrome (some PWAs render a
                    // degraded layout for in-app WebViews).
                    userAgentString = (userAgentString ?: "")
                        .replace(Regex("; wv\\)"), ")")
                        .plus(" PocketPi/0.1")
                }
                setBackgroundColor(0xFF000000.toInt())
                // Allow chrome://inspect → DevTools from desktop Chrome.
                WebView.setWebContentsDebuggingEnabled(true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // pi-mobile's `html,body,#app { height: 100% }` chain
                        // resolves to 0 in Android WebView. Anchor it to a
                        // real pixel height derived from window.innerHeight,
                        // and refresh on visualViewport changes so the input
                        // bar tracks the keyboard.
                        view?.evaluateJavascript(
                            """
                            (function(){
                              if (document.getElementById('pocketpi-fix')) return;
                              var s = document.createElement('style');
                              s.id = 'pocketpi-fix';
                              // Android WebView ghosts the bottom-anchored
                              // scrollable tab bar to the top of the viewport
                              // (GPU compositing layer leak). Three things to
                              // suppress it:
                              //   1. Kill the legacy momentum-scroll layer
                              //      that's the actual trigger.
                              //   2. Hard-clip the tab content so the leaked
                              //      pixels can't escape upward.
                              //   3. Put the tab bar in its own painting
                              //      context with overflow:clip on the
                              //      body, which blocks Chromium's full-page
                              //      compositor pass.
                              s.textContent = [
                                'html,body,#app{height:100dvh;min-height:100dvh;',
                                  'height:var(--pp-h);min-height:var(--pp-h);}',
                                'html,body{overflow:clip;}',
                                '#app{overflow:hidden;contain:strict;',
                                  'transform:translateZ(0);}',
                                '.tab-content{overflow-y:auto;overflow-x:hidden;',
                                  'contain:paint;}',
                                '.tab-bar{-webkit-overflow-scrolling:auto!important;',
                                  'overflow-x:auto;overflow-y:hidden;',
                                  'transform:translateZ(0);isolation:isolate;',
                                  'contain:strict;height:56px;',
                                  'position:relative;z-index:2;}',
                              ].join('');
                              document.head.appendChild(s);
                              function set(){
                                document.documentElement.style.setProperty(
                                  '--pp-h', window.innerHeight + 'px'
                                );
                              }
                              set();
                              window.addEventListener('resize', set);
                              if (window.visualViewport) {
                                window.visualViewport.addEventListener('resize', set);
                              }
                            })();
                            """.trimIndent(),
                            null,
                        )
                        view?.evaluateJavascript(
                            """
                            (function(){
                              function r(sel){var e=document.querySelector(sel);if(!e)return null;var b=e.getBoundingClientRect();return {x:b.x|0,y:b.y|0,w:b.width|0,h:b.height|0};}
                              function dumpAll(sel){var out=[];document.querySelectorAll(sel).forEach(function(e){var b=e.getBoundingClientRect();out.push({x:b.x|0,y:b.y|0,w:b.width|0,h:b.height|0});});return out;}
                              function probe(x,y){var e=document.elementFromPoint(x,y);if(!e)return null;var b=e.getBoundingClientRect();return {tag:e.tagName,cls:(e.className||'').toString().slice(0,40),txt:(e.textContent||'').replace(/\s+/g,' ').slice(0,30),x:b.x|0,y:b.y|0,w:b.width|0,h:b.height|0};}
                              var dump = {
                                url: location.href,
                                ww: window.innerWidth, wh: window.innerHeight,
                                vv: window.visualViewport ? {w:window.visualViewport.width|0, h:window.visualViewport.height|0} : null,
                                html_h: getComputedStyle(document.documentElement).height,
                                body_h: getComputedStyle(document.body).height,
                                body_disp: getComputedStyle(document.body).display,
                                body: r('body'),
                                app: r('#app'),
                                header: r('.header'),
                                tab_content: r('.tab-content'),
                                tab_bar: r('.tab-bar'),
                                input_bar: r('.chat-input-bar'),
                                all_tab_bars: dumpAll('.tab-bar'),
                                all_tab_items: dumpAll('.tab-item').length,
                                ghost_60_185: probe(60, 185),
                                ghost_130_185: probe(130, 185),
                                ghost_200_185: probe(200, 185),
                                ghost_270_185: probe(270, 185),
                                ghost_340_185: probe(340, 185),
                              };
                              console.log('LAYOUT_DUMP=' + JSON.stringify(dump));
                            })();
                            """.trimIndent(),
                            null,
                        )
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                        android.util.Log.i("PiWebViewConsole",
                            "${msg.messageLevel()} [${msg.lineNumber()}] ${msg.message()}")
                        return true
                    }
                }
                addJavascriptInterface(NativeBridge(ctx), "PocketPi")

                when (info.mode) {
                    UiMode.DASHBOARD -> {
                        // Localhost is unguarded for the dashboard — no auth POST.
                        loadUrl("http://127.0.0.1:${info.port}/")
                    }
                    UiMode.PI_MOBILE -> {
                        val body = "token=${URLEncoder.encode(info.token, "UTF-8")}&redirect=/mobile"
                        postUrl("http://127.0.0.1:${info.port}/_auth/login", body.toByteArray(Charsets.UTF_8))
                    }
                }
            }
        },
    )
}

private class NativeBridge(private val ctx: Context) {
    @JavascriptInterface
    fun notify(title: String, body: String) {
        val n = NotificationCompat.Builder(ctx, com.zosma.pocketpi.PocketPiApp.NOTIF_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(System.currentTimeMillis().toInt(), n) }
    }

    @JavascriptInterface
    fun share(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(Intent.createChooser(intent, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    @JavascriptInterface
    fun openExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(intent) }
    }

    @JavascriptInterface
    fun toast(text: String) {
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
}
