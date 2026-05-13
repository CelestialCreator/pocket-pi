package com.zosma.pocketpi.pi

import android.content.Context
import android.util.Log

/**
 * Owns two long-lived child processes:
 *
 *   1. `pi-dashboard start` — the WebView's UI substrate. Binds :8000
 *      (browser UI) and :9999 (pi extension WS). Spawned via explicit
 *      `node --import tsx-loader` because the dashboard's CLI shebang
 *      (`#!/usr/bin/env -S node --import tsx`) doesn't work under Termux.
 *   2. `pi --mode rpc` — the agent itself. The dashboard bridge extension
 *      (installed via @blackbelt-technology/pi-agent-dashboard and
 *      registered with `pi install`) connects out to ws://127.0.0.1:9999
 *      and the dashboard surfaces the agent's session.
 *
 * No event parsing here — all UI<->agent I/O happens inside the dashboard's
 * WebSocket protocol. PocketPiService keeps this object alive for the
 * duration of the foreground service.
 */
class PiBridge(private val ctx: Context) {
    private var process: Process? = null

    fun start() {
        if (process?.isAlive == true) return
        val prefix = Bootstrapper.prefixDir(ctx)
        // Bash wraps both children for two reasons: libtermux-exec needs to
        // intercept the `#!/usr/bin/env node` shebang, and pi --mode rpc
        // requires an attached stdin (sleep infinity keeps it open).
        //
        // The dashboard server is daemonized (`&`) into a subshell so this
        // bash exits cleanly when pi --mode rpc dies. Its stdout/stderr go
        // to ~/.pi/dashboard/dashboard.log so we can debug from logcat if
        // it never binds :8000.
        //
        // We invoke the `pi-dashboard` shim that ships in $PREFIX/bin
        // (npm-installed via @blackbelt-technology/pi-agent-dashboard). In
        // v0.5+ the shim is a tiny ESM wrapper at packages/server/bin/
        // pi-dashboard.mjs that resolves jiti from pi's tree and re-execs
        // `node --import <jiti-url> cli.ts <args>` — no more hand-rolling
        // a tsx-loader path or guessing where the cli.ts lives. Falls back
        // cleanly with exit code 1 + a clear message on dashboard.log if
        // @earendil-works/pi-coding-agent (which ships jiti) isn't on disk.
        val launch = buildString {
            append("mkdir -p \$HOME/.pi/dashboard; ")
            append("(")
            append("pi-dashboard start ")
            append(">>\$HOME/.pi/dashboard/dashboard.log 2>&1 &")
            append(") ; ")
            append("exec sleep infinity | exec pi --mode rpc")
        }
        val pb = ProcessBuilder(
            "${prefix}/bin/bash", "--noprofile", "--norc", "-c", launch,
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
                        cmd.contains("pi-dashboard") || cmd.contains("cli.ts") ||
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
