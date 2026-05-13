package com.zosma.pocketpi.api

import android.system.Os
import android.util.Log
import java.io.File
import java.security.SecureRandom
import java.util.Base64

/**
 * Per-launch bearer token gating the localhost HTTP API. Lives at
 * `$PREFIX/etc/pocket-pi/api-token` (mode 0600 — same UID as Termux, so file
 * perms gate access cleanly). Regenerated on every service start so a force-
 * stop / relaunch invalidates any old curl invocations still hanging around
 * in a shell's history.
 */
object Token {
    private const val TAG = "PocketPiToken"

    /** Generate, persist, and return a new token. */
    fun rotate(prefixDir: File): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val tokenFile = File(prefixDir, "etc/pocket-pi/api-token")
        tokenFile.parentFile?.mkdirs()
        tokenFile.writeText(token)
        runCatching { Os.chmod(tokenFile.absolutePath, 0b110_000_000) } // 0600
            .onFailure { Log.w(TAG, "chmod api-token failed: $it") }
        return token
    }
}
