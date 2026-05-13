package com.zosma.pocketpi

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.zosma.pocketpi.api.Inbox
import com.zosma.pocketpi.pi.Bootstrapper
import com.zosma.pocketpi.service.PocketPiService
import com.zosma.pocketpi.ui.OnboardingScreen
import com.zosma.pocketpi.ui.WebViewScreen
import com.zosma.pocketpi.ui.theme.PocketPiTheme

class MainActivity : ComponentActivity() {
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* best-effort: tools that need a missing perm return 403 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the system insets ours — the dashboard handles its own
        // safe-area math via the viewport meta and dvh units.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        // First-launch permission ask — non-blocking, the WebView/onboarding
        // flow renders regardless. Tools whose permission was denied just
        // return a 403 the user can fix in System Settings later.
        requestRuntimePermissionsIfNeeded()
        // Route the launching intent into the inbox if it's a share or
        // pi:// deep-link target.
        Inbox.writeInboxEntry(applicationContext, intent)
        setContent {
            PocketPiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Inbox.writeInboxEntry(applicationContext, intent)
    }

    private fun requestRuntimePermissionsIfNeeded() {
        val want = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val missing = want.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionsLauncher.launch(missing.toTypedArray())
        }
    }

    @Composable
    private fun Root() {
        val activity = this
        var bootstrapped by remember { mutableStateOf(Bootstrapper.isReady(activity)) }

        if (!bootstrapped) {
            OnboardingScreen(
                onComplete = {
                    bootstrapped = true
                    activity.startService(Intent(activity, PocketPiService::class.java))
                },
            )
        } else {
            LaunchedEffect(Unit) { activity.startService(Intent(activity, PocketPiService::class.java)) }
            WebViewScreen()
        }
    }
}
