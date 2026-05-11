package com.zosma.pocketpi.pi

import android.content.Context
import android.util.Log

/**
 * Owns the long-lived `pi --mode rpc` child process. With pi-webserver's
 * `autostart: true` set in settings.json, spawning Pi causes the webserver
 * to bind on port 4100 (or whatever's configured) and pi-mobile to mount
 * at /mobile. The Compose UI is just a WebView pointed at that.
 *
 * No event parsing here — all I/O between the UI and the agent happens
 * over HTTP through pi-webserver. PocketPiService keeps this object alive
 * for the duration of the foreground service.
 */
class PiBridge(private val ctx: Context) {
    private var process: Process? = null

    fun start() {
        if (process?.isAlive == true) return
        val prefix = Bootstrapper.prefixDir(ctx)
        // Two children to keep alive:
        //   1. `pi-dashboard start` binds :8000 (browser UI) and :9999 (the
        //      pi extension WS). It self-daemonizes via &; logs go to
        //      ~/.pi/dashboard/dashboard.log via tee.
        //   2. `pi --mode rpc` runs the agent; the bridge extension (installed
        //      via npm:@blackbelt-technology/pi-agent-dashboard at postinstall)
        //      loads at startup and connects out to ws://127.0.0.1:9999.
        // Bash wraps both for two reasons: libtermux-exec needs to intercept
        // the `#!/usr/bin/env node` shebang, and pi --mode rpc requires an
        // attached stdin (sleep infinity keeps it open).
        val pb = ProcessBuilder(
            "${prefix}/bin/bash", "--noprofile", "--norc", "-c",
            // Start the dashboard daemon. Don't fail if it's missing — falling
            // back to pi-mobile on :4100 still works for first-time runs that
            // haven't refreshed the npm bundle yet.
            "mkdir -p \$HOME/.pi/dashboard; " +
            "(command -v pi-dashboard >/dev/null && " +
            "  pi-dashboard start >>\$HOME/.pi/dashboard/dashboard.log 2>&1 &) ; " +
            "exec sleep infinity | exec pi --mode rpc",
        ).redirectErrorStream(true)
        pb.environment().putAll(Bootstrapper.termuxEnv(ctx))
        process = pb.start()
        // Drain stdout/stderr to logcat so a pi crash isn't invisible the
        // next time someone debugs this. Without the drain, the pipe buffer
        // fills up and silent hangs become common.
        Thread {
            process?.inputStream?.bufferedReader()?.useLines { lines ->
                lines.forEach { Log.i(TAG, "pi: $it") }
            }
        }.apply { isDaemon = true; name = "PiBridge-output"; start() }
        Log.i(TAG, "pi spawned (foreground service is keeping it alive)")
    }

    fun stop() {
        // process.destroy() only kills the bash wrapper; pi (spawned via
        // `exec pi` after the pipe) is reparented to init and keeps
        // running, holding port 4100 so the next bind fails. Sweep all
        // our-uid pi/node/sleep processes via `ps` first, then kill the
        // wrapper. We own the uid so SIGKILL is allowed.
        runCatching {
            val prefix = Bootstrapper.prefixDir(ctx)
            val ps = ProcessBuilder("${prefix}/bin/ps", "-A", "-o", "PID,PPID,UID,CMD")
                .redirectErrorStream(true)
                .also { it.environment().putAll(Bootstrapper.termuxEnv(ctx)) }
                .start()
            val myUid = android.os.Process.myUid()
            ps.inputStream.bufferedReader().readLines()
                .mapNotNull { line ->
                    val cols = line.trim().split(Regex("\\s+"))
                    if (cols.size < 4) return@mapNotNull null
                    val pid = cols[0].toIntOrNull() ?: return@mapNotNull null
                    val uid = cols[2].toIntOrNull() ?: return@mapNotNull null
                    val cmd = cols.drop(3).joinToString(" ")
                    if (uid != myUid) return@mapNotNull null
                    // Match the bash/pi/node/sleep we spawned, NOT this app's main process.
                    if (cmd.startsWith("pi") || cmd.contains("node") ||
                        cmd.contains("pi-dashboard") ||
                        cmd.startsWith("sleep") || cmd.startsWith("bash")) pid else null
                }
                .forEach { pid ->
                    try { android.os.Process.killProcess(pid) } catch (_: Throwable) {}
                }
        }
        process?.destroy()
        process = null
    }

    companion object {
        private const val TAG = "PiBridge"
    }
}
