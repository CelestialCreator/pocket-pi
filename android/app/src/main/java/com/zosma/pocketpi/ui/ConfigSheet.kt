package com.zosma.pocketpi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.zosma.pocketpi.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for all on-device Pi config that pi-mobile's Settings tab
 * doesn't expose:
 *   - per-provider API keys (NVIDIA, OpenAI, Anthropic, …)
 *   - AGENTS.md (free-form context Pi sees in every conversation)
 *   - models.json (raw provider/model registry, advanced)
 *   - Restart Pi (re-read env + config)
 *
 * Save buttons are per-section so partial edits don't leak. Restart Pi is the
 * last step — it kills and respawns the pi --mode rpc process, which is what
 * actually picks up new env vars or new files. The WebView keeps its
 * connection (pi-webserver stays bound to the same port + token).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigSheet(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val keys: SnapshotStateMap<String, String> = remember { mutableStateMapOf() }
    var agentsMd by remember { mutableStateOf("") }
    var modelsJson by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var setupLog by remember { mutableStateOf("") }
    var setupRunning by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            ConfigStore.WELL_KNOWN_PROVIDERS.forEach { keys[it] = ConfigStore.readKey(ctx, it) }
            agentsMd = ConfigStore.readAgentsMd(ctx)
            modelsJson = ConfigStore.readModelsJson(ctx)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Pocket Pi config", style = MaterialTheme.typography.headlineSmall)
            Text(
                "All settings are stored on-device. Restart Pi after saving for changes to take effect.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            SectionTitle("Provider API keys")
            Text(
                "Each key is written to ~/.config/<provider>/api-key and exported to Pi as <PROVIDER>_API_KEY.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ConfigStore.WELL_KNOWN_PROVIDERS.forEach { p ->
                OutlinedTextField(
                    value = keys[p] ?: "",
                    onValueChange = { keys[p] = it },
                    label = { Text(p.replaceFirstChar { c -> c.uppercase() }) },
                    placeholder = { Text("paste ${p.uppercase()}_API_KEY here") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            keys.forEach { (p, v) -> ConfigStore.saveKey(ctx, p, v) }
                        }
                        status = "Saved keys"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save keys") }

            HorizontalDivider()
            SectionTitle("AGENTS.md (Pi's always-on context)")
            OutlinedTextField(
                value = agentsMd,
                onValueChange = { agentsMd = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp),
                placeholder = { Text("# You are running on a phone via Pocket Pi…") },
            )
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ConfigStore.saveAgentsMd(ctx, agentsMd) }
                        status = "Saved AGENTS.md"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save AGENTS.md") }

            HorizontalDivider()
            SectionTitle("models.json (advanced)")
            Text(
                "Provider + model registry. Edit only if you know the schema — invalid JSON breaks Pi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = modelsJson,
                onValueChange = { modelsJson = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 320.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                placeholder = { Text("{ \"providers\": { … } }") },
            )
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) { ConfigStore.saveModelsJson(ctx, modelsJson) }
                        status = "Saved models.json"
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save models.json") }

            HorizontalDivider()
            SectionTitle("Apply changes")
            Text(
                "Restarts the Pi process so it re-reads env vars (API keys) and config files. The web UI stays connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { ConfigStore.restartPi() }
                            status = "Restarted Pi"
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Restart Pi") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("Close")
                }
            }
            if (status.isNotEmpty()) {
                Text(status, style = MaterialTheme.typography.labelMedium)
            }

            HorizontalDivider()
            SectionTitle("Re-run setup (advanced)")
            Text(
                "Pulls the latest bundled scripts from this APK and re-runs postinstall. " +
                    "Use this after updating Pocket Pi if existing installs are missing extensions, " +
                    "new providers, or other postinstall changes. Safe — idempotent and preserves user data.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = {
                    scope.launch {
                        setupRunning = true
                        setupLog = ""
                        withContext(Dispatchers.IO) {
                            ConfigStore.rerunSetup(ctx) { line ->
                                setupLog = (setupLog + "\n" + line).takeLast(2000)
                            }
                        }
                        setupRunning = false
                        ConfigStore.restartPi()
                        status = "Setup complete — Pi restarted"
                    }
                },
                enabled = !setupRunning,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (setupRunning) "Running setup…" else "Re-run setup") }
            if (setupLog.isNotEmpty()) {
                Text(
                    setupLog,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}
