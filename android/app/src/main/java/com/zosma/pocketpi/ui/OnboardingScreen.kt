package com.zosma.pocketpi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zosma.pocketpi.R
import com.zosma.pocketpi.pi.Bootstrapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Three-step onboarding:
 *   1. Extract bootstrap zip + run postinstall.sh (npm i -g, pip install,
 *      apply hermes-evolve patch).
 *   2. Provider auth (handled inside the pi-mobile PWA's Settings tab).
 *   3. Permissions + battery-optimisation exemption.
 *
 * Step 1 is the only one we run automatically; 2 and 3 are surfaced as
 * follow-up screens. This file handles step 1 and hands off to the chat UI
 * once the device is bootstrapped — Settings can always be revisited later.
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf("Preparing…") }
    var done by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) {
                status = "Extracting bootstrap…"
                Bootstrapper.extract(ctx) { line -> log.value = line }
                status = "Installing packages (this can take a few minutes on first run)…"
                val code = Bootstrapper.runPostinstall(ctx) { line ->
                    log.value = line
                }
                if (code != 0) failed = "Postinstall failed (exit $code). Tap log for details."
                else done = true
            }
        } catch (t: Throwable) {
            failed = t.message ?: "Unknown error"
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.onboarding_subtitle), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        if (failed == null && !done) {
            LinearProgressIndicator(modifier = Modifier.fillMaxSize().height(6.dp))
            Text(status)
            Text(log.value, style = MaterialTheme.typography.bodySmall)
        } else if (failed != null) {
            Text("Setup failed: $failed", color = MaterialTheme.colorScheme.error)
            Text(log.value, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("All set.")
            Button(onClick = onComplete) { Text("Open chat") }
        }
    }
}
