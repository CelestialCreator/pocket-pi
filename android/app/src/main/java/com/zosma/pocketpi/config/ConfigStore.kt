package com.zosma.pocketpi.config

import android.content.Context
import android.system.Os
import com.zosma.pocketpi.pi.Bootstrapper
import com.zosma.pocketpi.service.PocketPiService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed config that the in-app Config sheet reads and writes.
 *
 * Layout on disk (under [Bootstrapper.homeDir]):
 *   ~/.config/<provider>/api-key      — one line per provider; mode 0600
 *   ~/.pi/agent/AGENTS.md             — free-form agent context
 *   ~/.pi/agent/models.json           — provider/model registry (raw JSON)
 *
 * [Bootstrapper.termuxEnv] scans ~/.config/<provider>/api-key automatically and
 * exports each as <PROVIDER>_API_KEY, so the only thing the UI has to do is
 * write the file. Pi must be restarted to pick up env changes — call
 * [restartPi] after saving keys.
 */
object ConfigStore {

    /** Providers we always offer a slot for, in display order. */
    val WELL_KNOWN_PROVIDERS = listOf(
        "nvidia",     // → NVIDIA_API_KEY
        "openai",     // → OPENAI_API_KEY
        "anthropic",  // → ANTHROPIC_API_KEY
        "groq",       // → GROQ_API_KEY
        "openrouter", // → OPENROUTER_API_KEY
    )

    private fun keyFile(ctx: Context, provider: String): File =
        File(Bootstrapper.homeDir(ctx), ".config/$provider/api-key")

    private fun agentsMd(ctx: Context): File =
        File(Bootstrapper.homeDir(ctx), ".pi/agent/AGENTS.md")

    private fun modelsJson(ctx: Context): File =
        File(Bootstrapper.homeDir(ctx), ".pi/agent/models.json")

    fun readKey(ctx: Context, provider: String): String =
        runCatching { keyFile(ctx, provider).readText().trim() }.getOrDefault("")

    fun saveKey(ctx: Context, provider: String, key: String) {
        val f = keyFile(ctx, provider)
        f.parentFile?.mkdirs()
        val trimmed = key.trim()
        f.writeText(trimmed)
        runCatching { Os.chmod(f.absolutePath, 0b110_000_000) /* 0600 */ }
        // Auto-add a provider entry to models.json so pi-mobile's model
        // picker surfaces it. Saving a key alone is necessary but not
        // sufficient: pi reads providers from models.json. If the user
        // already customized this provider's block, leave it alone.
        if (trimmed.isNotEmpty() && provider in PROVIDER_TEMPLATES) {
            runCatching { ensureProviderEntry(ctx, provider) }
        }
    }

    private fun ensureProviderEntry(ctx: Context, provider: String) {
        val template = PROVIDER_TEMPLATES[provider] ?: return
        val mj = modelsJson(ctx)
        val root = if (mj.exists()) {
            runCatching { JSONObject(mj.readText()) }.getOrDefault(JSONObject())
        } else JSONObject()
        val providers = root.optJSONObject("providers") ?: JSONObject().also { root.put("providers", it) }
        if (providers.has(provider)) return  // user-managed, don't clobber
        val entry = JSONObject().apply {
            put("baseUrl", template.baseUrl)
            put("apiKey", "${provider.uppercase().replace('-', '_')}_API_KEY")
            put("api", template.api)
            put("authHeader", template.authHeader)
            val models = JSONArray()
            template.models.forEach { (id, name) ->
                models.put(JSONObject().apply { put("id", id); put("name", name) })
            }
            put("models", models)
        }
        providers.put(provider, entry)
        mj.parentFile?.mkdirs()
        mj.writeText(root.toString(2))
    }

    private data class ProviderTemplate(
        val baseUrl: String,
        val api: String,
        val authHeader: Boolean,
        val models: List<Pair<String, String>>,
    )

    private val PROVIDER_TEMPLATES = mapOf(
        "nvidia" to ProviderTemplate(
            baseUrl = "https://integrate.api.nvidia.com/v1",
            api = "openai-completions",
            authHeader = true,
            models = listOf(
                "meta/llama-3.3-70b-instruct" to "Llama 3.3 70B",
                "deepseek-ai/deepseek-v4-flash" to "DeepSeek V4 Flash",
                "qwen/qwen3-coder-480b-a35b-instruct" to "Qwen3 Coder 480B",
                "openai/gpt-oss-120b" to "GPT-OSS 120B",
                "nvidia/llama-3.3-nemotron-super-49b-v1" to "Nemotron Super 49B",
            ),
        ),
        "openai" to ProviderTemplate(
            baseUrl = "https://api.openai.com/v1",
            api = "openai-completions",
            authHeader = true,
            models = listOf(
                "gpt-5" to "GPT-5",
                "gpt-4.1" to "GPT-4.1",
                "gpt-4.1-mini" to "GPT-4.1 Mini",
                "o3" to "o3",
            ),
        ),
        "anthropic" to ProviderTemplate(
            baseUrl = "https://api.anthropic.com/v1",
            api = "anthropic-messages",
            authHeader = false,
            models = listOf(
                "claude-opus-4-7" to "Claude Opus 4.7",
                "claude-sonnet-4-6" to "Claude Sonnet 4.6",
                "claude-haiku-4-5-20251001" to "Claude Haiku 4.5",
            ),
        ),
        "groq" to ProviderTemplate(
            baseUrl = "https://api.groq.com/openai/v1",
            api = "openai-completions",
            authHeader = true,
            models = listOf(
                "llama-3.3-70b-versatile" to "Llama 3.3 70B Versatile",
                "deepseek-r1-distill-llama-70b" to "DeepSeek R1 Distill 70B",
                "qwen-2.5-coder-32b" to "Qwen 2.5 Coder 32B",
            ),
        ),
        "openrouter" to ProviderTemplate(
            baseUrl = "https://openrouter.ai/api/v1",
            api = "openai-completions",
            authHeader = true,
            models = listOf(
                "anthropic/claude-opus-4-7" to "Claude Opus 4.7 (OR)",
                "openai/gpt-5" to "GPT-5 (OR)",
                "google/gemini-2.5-pro" to "Gemini 2.5 Pro (OR)",
                "meta-llama/llama-3.3-70b-instruct" to "Llama 3.3 70B (OR)",
            ),
        ),
    )

    fun readAgentsMd(ctx: Context): String =
        runCatching { agentsMd(ctx).readText() }.getOrDefault("")

    fun saveAgentsMd(ctx: Context, text: String) {
        val f = agentsMd(ctx)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    fun readModelsJson(ctx: Context): String =
        runCatching { modelsJson(ctx).readText() }.getOrDefault("")

    fun saveModelsJson(ctx: Context, text: String) {
        val f = modelsJson(ctx)
        f.parentFile?.mkdirs()
        f.writeText(text)
    }

    /**
     * Force Pi to re-read all config: stop the running pi process and let the
     * foreground service auto-restart it. The service is sticky so onCreate()
     * fires again and respawns through PiBridge. The WebView reconnects on
     * its existing port; webserver-info.json is unchanged so the WebView
     * doesn't need to be reloaded — Pi just comes back fresh.
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
