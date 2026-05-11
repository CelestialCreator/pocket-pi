package com.zosma.pocketpi.pi

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * First-run bootstrap. Extracts assets/bootstrap-aarch64.zip into
 * `$appFiles/usr` (mirroring Termux's $PREFIX), then runs the bundled
 * postinstall.sh which does `npm i -g` and `pip install`.
 *
 * The bootstrap zip uses Termux's standard format:
 *   - Regular files at zip-relative paths
 *   - A SYMLINKS.txt manifest at the root, '\x01'-separated target/linkname
 *     pairs (one per line) — extracted last so all files are present first.
 *
 * Once `usr/bin/pi` exists and `~/.pi/agent/.ready` is present, we treat
 * the device as bootstrapped.
 */
object Bootstrapper {
    private const val TAG = "Bootstrapper"
    private const val BOOTSTRAP_ASSET = "bootstrap-aarch64.zip"

    // Termux's upstream bootstrap zip is built for the official Termux
    // package and bakes this absolute path into SYMLINKS.txt entries (and
    // some scripts). Rewrite to our app's path at extraction time.
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    fun prefixDir(ctx: Context): File = File(ctx.filesDir, "usr")
    fun homeDir(ctx: Context): File = File(ctx.filesDir, "home")
    private fun readyMarker(ctx: Context) = File(homeDir(ctx), ".pi/agent/.ready")
    private fun piBinary(ctx: Context) = File(prefixDir(ctx), "bin/pi")

    private fun rewritePath(s: String, ctx: Context): String {
        val prefix = prefixDir(ctx).absolutePath
        val home = homeDir(ctx).absolutePath
        return s
            .replace(TERMUX_PREFIX, prefix)
            .replace(TERMUX_HOME, home)
    }

    fun isReady(ctx: Context): Boolean = readyMarker(ctx).exists() && piBinary(ctx).canExecute()

    /**
     * Extract the bootstrap zip. Idempotent: skips if already extracted.
     * Returns the prefix directory.
     */
    fun extract(ctx: Context, onProgress: (String) -> Unit = {}): File {
        val prefix = prefixDir(ctx)
        val home = homeDir(ctx)
        if (piBinary(ctx).exists()) {
            onProgress("Already extracted")
            return prefix
        }
        prefix.mkdirs()
        home.mkdirs()

        var symlinksManifest: String? = null

        ctx.assets.open(BOOTSTRAP_ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val out = File(prefix, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    if (entry.name == "SYMLINKS.txt") {
                        // Buffer the manifest; create symlinks once all real files exist.
                        symlinksManifest = zip.readBytes().toString(Charsets.UTF_8)
                        Log.i(TAG, "SYMLINKS.txt buffered, ${symlinksManifest!!.length} bytes")
                        continue
                    }
                    FileOutputStream(out).use { fo -> zip.copyTo(fo) }
                    // Java's ZipInputStream doesn't preserve POSIX mode bits.
                    // Termux's zip stores files with 0700/0755 modes that we
                    // need to recreate. Easiest correct thing: chmod 0700
                    // for every file we extract (private to our uid, +rwx).
                    // 0700 = 0b111_000_000 = 448 — Android sets the gid
                    // on data files automatically; +x is what we need.
                    try {
                        Os.chmod(out.absolutePath, 0b111_000_000)
                    } catch (_: Throwable) {
                        out.setExecutable(true, true)
                    }
                }
            }
        }

        // Apply SYMLINKS.txt last — Termux's bootstrap relies on this to wire
        // up the dozens of links that make up `bin/` (busybox-style multi-call
        // binaries, pkg→apt aliases, etc.). Format: target\x01linkname per line.
        var ok = 0
        var bad = 0
        symlinksManifest?.let { manifest ->
            for (raw in manifest.split('\n')) {
                val line = raw.trim('\r', '\n', ' ')
                if (line.isEmpty()) continue
                val sep = line.indexOf('←')  // U+2190 left-arrow separator
                if (sep <= 0) continue
                val target = rewritePath(line.substring(0, sep), ctx)
                val linknameRel = line.substring(sep + 1).removePrefix("./")
                val link = File(prefix, linknameRel)
                link.parentFile?.mkdirs()
                if (link.exists()) link.delete()
                try {
                    Os.symlink(target, link.absolutePath); ok++
                } catch (e: Throwable) {
                    bad++
                    if (bad < 5) Log.w(TAG, "symlink failed: $linknameRel → $target ($e)")
                }
            }
        }
        Log.i(TAG, "extract done — symlinks ok=$ok bad=$bad")

        // Rewrite hardcoded /data/data/com.termux/files/usr paths in script
        // shebangs and #! interpreters across bin/, libexec/, and etc/. The
        // upstream Termux bootstrap zip bakes this prefix into many scripts
        // (pkg, apt wrappers, helper scripts). Without this, the kernel
        // fails the shebang lookup for any script that uses an absolute path.
        val rewritten = rewriteShebangs(prefix, ctx)
        Log.i(TAG, "shebangs rewritten: $rewritten files")
        onProgress("Bootstrap extracted (symlinks $ok, shebangs $rewritten)")
        return prefix
    }

    /** Walk script-y dirs and rewrite #! lines that reference Termux's prefix. */
    private fun rewriteShebangs(prefix: File, ctx: Context): Int {
        val ourPrefix = prefix.absolutePath
        val patterns = listOf(
            TERMUX_PREFIX to ourPrefix,
            TERMUX_HOME to homeDir(ctx).absolutePath,
        )
        var changed = 0
        val dirs = listOf("bin", "libexec", "etc", "share/man").map { File(prefix, it) }
        for (dir in dirs) {
            if (!dir.exists()) continue
            dir.walkTopDown().forEach { f ->
                if (!f.isFile || f.length() > 256_000) return@forEach
                val head = ByteArray(2)
                try {
                    f.inputStream().use { it.read(head) }
                } catch (_: Throwable) { return@forEach }
                if (head[0] != '#'.code.toByte() || head[1] != '!'.code.toByte()) return@forEach
                val text = try { f.readText(Charsets.UTF_8) } catch (_: Throwable) { return@forEach }
                var out = text
                for ((from, to) in patterns) {
                    if (from in out) out = out.replace(from, to)
                }
                if (out != text) {
                    try {
                        f.writeText(out, Charsets.UTF_8)
                        changed++
                    } catch (e: Throwable) {
                        Log.w(TAG, "shebang rewrite failed for ${f.path}: $e")
                    }
                }
            }
        }
        return changed
    }

    /**
     * Refresh just the etc/pocket-pi tree from the packaged bootstrap asset,
     * overwriting whatever's on disk. Lets us update postinstall.sh,
     * npm-packages.txt, AGENTS.md, the patches dir, etc. between releases
     * without forcing a full reinstall or losing user data in $HOME. Skips
     * everything that isn't under etc/pocket-pi, so binaries / libs stay
     * untouched. Safe to call after the device is already bootstrapped.
     */
    fun refreshPayload(ctx: Context): Int {
        val prefix = prefixDir(ctx)
        var count = 0
        ctx.assets.open(BOOTSTRAP_ASSET).use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue
                    if (!entry.name.startsWith("etc/pocket-pi/")) continue
                    val out = File(prefix, entry.name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fo -> zip.copyTo(fo) }
                    try { Os.chmod(out.absolutePath, 0b111_000_000) } catch (_: Throwable) {}
                    count++
                }
            }
        }
        Log.i(TAG, "refreshPayload: refreshed $count files under etc/pocket-pi/")
        return count
    }

    /**
     * Run the bundled postinstall.sh. Streams stdout/stderr lines to onLine.
     * Returns exit code.
     */
    fun runPostinstall(ctx: Context, onLine: (String) -> Unit): Int {
        val prefix = prefixDir(ctx)
        val home = homeDir(ctx)
        val script = File(prefix, "etc/pocket-pi/postinstall.sh")
        if (!script.exists()) {
            Log.e(TAG, "postinstall.sh missing at $script")
            return 1
        }
        val pb = ProcessBuilder("${prefix}/bin/bash", "--noprofile", "--norc", script.absolutePath)
            .redirectErrorStream(true)
        pb.environment().putAll(termuxEnv(ctx))
        val proc = pb.start()
        proc.inputStream.bufferedReader().forEachLine(onLine)
        val code = proc.waitFor()
        if (code == 0) {
            val marker = readyMarker(ctx)
            marker.parentFile?.mkdirs()
            marker.writeText(System.currentTimeMillis().toString())
        }
        return code
    }

    /**
     * The Termux-style environment that all spawned processes (bash, pi, npm,
     * pip, …) need. Notable bits:
     *
     *  - LD_PRELOAD = libtermux-exec-ld-preload.so. This is the linchpin: the
     *    library hooks execve() and rewrites shebang lines + linker namespace
     *    so binaries built for /data/data/com.termux/files/usr work from any
     *    package's data dir.
     *  - LD_LIBRARY_PATH = $PREFIX/lib so the dynamic linker finds Termux's
     *    bundled .so files (libreadline, libssl, libgmp, …).
     *  - TERMUX_PREFIX / TERMUX_HOME / PREFIX / HOME for shell + npm hygiene.
     *  - PATH points at our $PREFIX/bin only so we don't accidentally exec
     *    the host /system/bin/sh (which doesn't speak our libc).
     */
    fun termuxEnv(ctx: Context): Map<String, String> {
        val prefix = prefixDir(ctx).absolutePath
        val home = homeDir(ctx).absolutePath
        val ldPreload = File(prefix, "lib/libtermux-exec-ld-preload.so")
        val env = mutableMapOf(
            "PREFIX" to prefix,
            "HOME" to home,
            "TERMUX_PREFIX" to prefix,
            "TERMUX_HOME" to home,
            "PATH" to "$prefix/bin",
            "LD_LIBRARY_PATH" to "$prefix/lib",
            "TMPDIR" to "$prefix/tmp",
            "LANG" to "en_US.UTF-8",
            "TERM" to "xterm-256color",
        )
        if (ldPreload.exists()) {
            env["LD_PRELOAD"] = ldPreload.absolutePath
        }
        // Auto-export every `~/.config/<provider>/api-key` as <PROVIDER>_API_KEY
        // (uppercased). The Config sheet writes keys to these files; models.json
        // references them by name. New providers can be added at runtime just
        // by dropping a key file — no Kotlin change needed.
        val cfgDir = File(home, ".config")
        if (cfgDir.isDirectory) {
            cfgDir.listFiles { f -> f.isDirectory }?.forEach { provider ->
                val keyFile = File(provider, "api-key")
                if (!keyFile.exists()) return@forEach
                try {
                    val key = keyFile.readText().trim()
                    if (key.isEmpty()) return@forEach
                    val varName = provider.name.uppercase().replace('-', '_') + "_API_KEY"
                    env[varName] = key
                } catch (_: Throwable) { /* ignore unreadable */ }
            }
        }
        return env
    }
}
