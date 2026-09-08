package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

/** Translates Codex Responses requests with tools using the provider library, without starting a pi agent. */
internal class CodexProviderBridge private constructor(private val process: Process, val providerId: String, val configuration: JsonObject) : AutoCloseable {
    override fun close() {
        try { process.outputStream.close() }
        finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
    companion object {
        suspend fun start(node: File, script: File, library: File, profile: LlmProfile): CodexProviderBridge = withContext(Dispatchers.IO) {
            val key = UUID.randomUUID().toString()
            val providerId = "magicpaper-api"
            val provider = PiModelsConfig.root(profile)["providers"]!!.jsonObject[PiModelsConfig.PROVIDER_ID]!!.jsonObject
            val model = JsonObject(provider["models"]!!.jsonArray.first().jsonObject + mapOf(
                // pi's registry fills these defaults; the direct library bridge must do the same.
                "input" to (provider["models"]!!.jsonArray.first().jsonObject["input"] ?: buildJsonArray { add("text") }),
                "cost" to buildJsonObject { put("input", 0); put("output", 0); put("cacheRead", 0); put("cacheWrite", 0) },
                "api" to provider["api"]!!, "baseUrl" to provider["baseUrl"]!!,
                "provider" to JsonPrimitive(when (profile.provider) { ProviderType.ANTHROPIC -> "anthropic"; ProviderType.GOOGLE -> "google"; ProviderType.OPENROUTER -> "openrouter"; else -> "magicpaper" }),
                "compat" to (provider["compat"] ?: JsonObject(emptyMap())),
            ))
            val config = buildJsonObject {
                put("key", key); put("model", model); put("apiKey", profile.apiKey.ifBlank { "anonymous" })
                put("parameters", PiModelOptions.parameters(profile))
                PiModelsConfig.thinkingLevel(profile)?.let { put("reasoning", it) }
            }
            val child = ProcessBuilder(node.absolutePath, script.absolutePath)
                .directory(script.parentFile).redirectError(ProcessBuilder.Redirect.DISCARD)
                .apply { environment()["MAGICPAPER_PI_AI"] = library.toURI().toString() }.start()
            try {
                CodingProcessLifetime.attach(child)
                child.outputStream.write((config.toString() + "\n").toByteArray()); child.outputStream.flush()
                val stdout = child.inputStream.bufferedReader()
                val ready = withTimeout(20_000) {
                    while (!stdout.ready()) { check(child.isAlive) { "Адаптер провайдера не запустился" }; delay(25) }
                    Json.parseToJsonElement(stdout.readLine()).jsonObject
                }
                val port = ready["port"]!!.jsonPrimitive.int
                CodexProviderBridge(child, providerId, buildJsonObject {
                    put("model_provider", providerId)
                    put("model_providers.$providerId", buildJsonObject {
                        put("name", "MagicPaper API"); put("base_url", "http://127.0.0.1:$port")
                        put("wire_api", "responses"); put("experimental_bearer_token", key)
                        put("requires_openai_auth", false); put("supports_websockets", false)
                    })
                    put("web_search", "disabled")
                })
            } catch (e: Throwable) { child.destroyForcibly(); throw e }
        }
    }
}
