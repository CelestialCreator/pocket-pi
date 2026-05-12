package com.zosma.pocketpi.config

import android.content.Context
import com.zosma.pocketpi.pi.Bootstrapper
import com.zosma.pocketpi.service.PocketPiService

/**
 * Recovery actions exposed by the WebViewScreen when the dashboard fails to
 * bind on :8000 (the only state where the Compose UI is visible — once the
 * dashboard is up, all config lives inside its own settings UI).
 *
 * Two operations:
 *   - [restartPi]    — kick the foreground service so it respawns pi + dashboard.
 *   - [rerunSetup]   — refresh bundled scripts from the APK asset and re-run
 *                      postinstall.sh. Used when a new APK ships with fixes but
 *                      the device is already bootstrapped (zip extraction is
 *                      skipped on subsequent launches).
 */
object ConfigStore {

    /**
     * Force Pi to re-read all config: stop the running pi + dashboard
     * children and let the foreground service auto-restart them. The
     * service is sticky so onCreate() fires again and respawns through
     * PiBridge. The WebView reconnects to :8000 once the dashboard binds
     * again — usually within a few seconds.
     */
    fun restartPi() {
        PocketPiService.bridge?.let { bridge ->
            bridge.stop()
            bridge.start()
        }
    }

    /**
     * Refresh the bundled scripts (postinstall.sh, npm-packages.txt, skel
     * tree) from the APK asset and re-run postinstall. Used when a new APK
     * ships with a fixed postinstall but the device is already bootstrapped
     * (so the zip extraction step is skipped on launch). Streams each output
     * line to [onLine] for live progress display.
     */
    fun rerunSetup(ctx: Context, onLine: (String) -> Unit): Int {
        val refreshed = Bootstrapper.refreshPayload(ctx)
        onLine("==> Refreshed $refreshed files under etc/pocket-pi/")
        return Bootstrapper.runPostinstall(ctx, onLine)
    }
}
