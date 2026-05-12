package com.zosma.pocketpi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.zosma.pocketpi.pi.Bootstrapper
import com.zosma.pocketpi.service.PocketPiService
import com.zosma.pocketpi.ui.OnboardingScreen
import com.zosma.pocketpi.ui.WebViewScreen
import com.zosma.pocketpi.ui.theme.PocketPiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the system insets ours — the dashboard handles its own
        // safe-area math via the viewport meta and dvh units.
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContent {
            PocketPiTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Root()
                }
            }
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
