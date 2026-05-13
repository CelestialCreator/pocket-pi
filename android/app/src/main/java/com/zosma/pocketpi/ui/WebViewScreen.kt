package com.zosma.pocketpi.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zosma.pocketpi.config.ConfigStore
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Single-WebView surface backed by pi-agent-dashboard on :8000.
 *
 * Lifecycle states:
 *   1. Server not yet up (first run = postinstall, returning users = bind wait)
 *      → live tail of postinstall.log + status + Retry button
 *   2. Server up
 *      → WebView loads http://127.0.0.1:8000/ directly (no auth — localhost
 *        is unguarded for the dashboard).
 */
private const val DASHBOARD_PORT = 8000
private const val PREFS = "pocketpi"
private const val PREF_ACCESSIBILITY_DISMISSED = "accessibility_pane_dismissed"

@Composable
fun WebViewScreen() {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf<WebserverInfo?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var phase by remember { mutableStateOf("Starting Pi…") }
    var logTail by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf(0) }

    // Accessibility service polled every 2s. Re-evaluates the AccessibilityPane
    // visibility — the user may toggle it on in Settings while the app is open.
    var accessibilityOn by remember {
        mutableStateOf(com.zosma.pocketpi.orbeye.PocketPiAccessibilityService.getInstance() != null)
    }
    var accessibilityDismissed by remember {
        mutableStateOf(
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(PREF_ACCESSIBILITY_DISMISSED, false),
        )
    }
    LaunchedEffect(Unit) {
        while (true) {
            accessibilityOn = com.zosma.pocketpi.orbeye.PocketPiAccessibilityService.getInstance() != null
            delay(2000)
        }
    }

    LaunchedEffect(attempt) {
        info = null; error = null
        val resolved = withContext(Dispatchers.IO) {
            runWaitLoop(ctx) { p, t -> phase = p; logTail = t }
        }
        resolved.onSuccess { info = it }.onFailure { error = it.message }
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        when {
            error != null -> ErrorPane(error!!, logTail, onRetry = { attempt++ })
            info == null -> LoadingPane(phase, logTail)
            // Once the dashboard is up, if Accessibility isn't enabled and the
            // user hasn't dismissed the nudge, show the pane. Skippable — chat
            // works without it; UI tools just return 403 until toggled on.
            !accessibilityOn && !accessibilityDismissed -> AccessibilityPane(
                onOpenSettings = {
                    runCatching {
                        ctx.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                },
                onSkip = {
                    ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_ACCESSIBILITY_DISMISSED, true)
                        .apply()
                    accessibilityDismissed = true
                },
            )
            else -> Web(info!!)
        }
    }
}

private data class WebserverInfo(val port: Int)

private suspend fun runWaitLoop(
    ctx: Context,
    onProgress: (phase: String, logTail: String) -> Unit,
): Result<WebserverInfo> {
    val logFile = File(Bootstrapper.homeDir(ctx), ".pi/agent/postinstall.log")
    val readyMarker = File(Bootstrapper.homeDir(ctx), ".pi/agent/postinstall.log") // postinstall log exists once setup ran
    val deadline = System.currentTimeMillis() + 25 * 60 * 1000L
    while (System.currentTimeMillis() < deadline) {
        val tail = readTail(logFile, 18)
        if (probe(DASHBOARD_PORT)) {
            onProgress("Connected", tail)
            return Result.success(WebserverInfo(DASHBOARD_PORT))
        }
        val phase = when {
            !logFile.exists() -> "Preparing first-run install…"
            !readyMarker.exists() -> "Installing packages (apt + npm). 5–10 min on first launch."
            else -> "Pi installed. Waiting for the dashboard to bind :8000…"
        }
        onProgress(phase, tail)
        delay(2000)
    }
    return Result.failure(IllegalStateException(
        "Pi didn't come up within 25 minutes. Check the log below; tap Retry once it looks finished."
    ))
}

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
private fun LoadingPane(phase: String, logTail: String) {
    // After 15s the bind wait has crossed the "something is probably wrong"
    // threshold (returning users: the dashboard should bind within a few
    // seconds of service start). Surface the recovery buttons so a wedged
    // bootstrap isn't a dead end.
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
                "Taking longer than expected. Restart Pi or re-run the installer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecoveryActions()
        }
    }
}

@Composable
private fun ErrorPane(
    message: String,
    logTail: String,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Pi didn't start", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text("Retry") }
        RecoveryActions()
        if (logTail.isNotEmpty()) {
            Text("Install log (last 18 lines):", style = MaterialTheme.typography.labelSmall)
            Text(logTail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * One-time nudge shown after the dashboard binds, if the user hasn't yet
 * enabled the Pocket Pi Accessibility service. Pocket Pi's UI-automation
 * tools (taps, swipes, screen reading, screenshot, notification listening)
 * require the user to manually toggle this on in Settings → Accessibility —
 * Android forbids any runtime-dialog shortcut for accessibility permissions.
 *
 * Skippable: chat still works without it, UI tools just return 403. The
 * pane resurfaces only after a fresh install (PREF_ACCESSIBILITY_DISMISSED
 * is set on skip).
 */
@Composable
private fun AccessibilityPane(onOpenSettings: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Phone control — one tap to enable", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Pi can read and act on other apps' screens — open WhatsApp + send a " +
                "message, summarize what's on the current screen, react to incoming " +
                "notifications, take screenshots, etc. This needs Accessibility " +
                "permission, which Android only grants via the system Settings " +
                "screen (no in-app dialog).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "What you'll do: tap Open Settings → tap \"Pocket Pi\" in the list → " +
                "toggle on → confirm. One-time setup; the toggle stays on across reboots.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenSettings, modifier = Modifier.fillMaxSize().height(48.dp)) {
            Text("Open Settings → Accessibility")
        }
        OutlinedButton(onClick = onSkip, modifier = Modifier.fillMaxSize().height(48.dp)) {
            Text("Skip for now — chat without phone control")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Nothing leaves the device. The accessibility surface is vendored from " +
                "github.com/KarryViber/orb-eye (MIT) and gated by Pocket Pi's per-launch " +
                "bearer token. You can disable it any time in Settings → Accessibility.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Two-button row that replaces the old ⚙ → ConfigSheet recovery path.
 * "Restart Pi" kicks the service so it respawns pi + dashboard. "Re-run setup"
 * refreshes the bundled scripts from the APK asset and runs postinstall again
 * (streaming progress to [setupLog]). Both actions are idempotent — chat
 * sessions persist on disk via pi's session store.
 */
@Composable
private fun RecoveryActions() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("") }
    var setupLog by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { ConfigStore.restartPi() }
                    status = "Pi restarted"
                }
            },
        ) { Text("Restart Pi") }
        OutlinedButton(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    setupLog = ""
                    status = "Running setup…"
                    withContext(Dispatchers.IO) {
                        ConfigStore.rerunSetup(ctx) { line ->
                            setupLog = (setupLog + "\n" + line).takeLast(2000)
                        }
                    }
                    withContext(Dispatchers.IO) { ConfigStore.restartPi() }
                    busy = false
                    status = "Setup complete — Pi restarted"
                }
            },
        ) { Text("Re-run setup") }
    }
    if (status.isNotEmpty()) {
        Text(status, style = MaterialTheme.typography.bodySmall)
    }
    if (setupLog.isNotEmpty()) {
        Text("Setup log:", style = MaterialTheme.typography.labelSmall)
        Text(
            setupLog,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
        )
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
                    useWideViewPort = true
                    loadWithOverviewMode = true
                    // Strip the `; wv` identifier so the dashboard's UA
                    // detection treats us like regular Chrome — some web
                    // apps render a degraded layout when they spot a WebView.
                    userAgentString = (userAgentString ?: "")
                        .replace(Regex("; wv\\)"), ")")
                        .plus(" PocketPi/0.2")
                }
                setBackgroundColor(0xFF000000.toInt())
                // Allow chrome://inspect → DevTools from desktop Chrome.
                WebView.setWebContentsDebuggingEnabled(true)
                // The dashboard's root container resolves to height:0 in
                // Android WebView (same `html,body{height:100%}` collapse
                // we fought on pi-mobile). Anchor it to a real pixel
                // height derived from window.innerHeight, and refresh on
                // visualViewport changes so the input bar tracks the keyboard.
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(
                            """
                            (function(){
                              if (document.getElementById('pocketpi-fix')) return;
                              var s = document.createElement('style');
                              s.id = 'pocketpi-fix';
                              // Force a real pixel height on every node that
                              // would otherwise rely on `100dvh` (Tailwind's
                              // `h-[100dvh]` resolves to 0 in WebView 113
                              // even though CSS.supports returns true).
                              s.textContent =
                                'html,body,#root,#root>*{height:var(--pp-h)!important;min-height:var(--pp-h)!important;}'+
                                'html,body{margin:0;overflow:hidden;}'+
                                '[class*="h-[100dvh]"],[class*="h-[100vh]"],[class*="min-h-[100dvh]"],[class*="min-h-[100vh]"]{height:var(--pp-h)!important;min-height:var(--pp-h)!important;}';
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

                // Localhost is unguarded for pi-agent-dashboard; no auth POST.
                loadUrl("http://127.0.0.1:${info.port}/")
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
