package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Assume.assumeTrue

class PiModelOptionsTest {
    @Test fun actualGeneratedExtensionPreservesToolsAndReplacesOnlyModelControls() {
        val executable = System.getenv("PATH").orEmpty().split(File.pathSeparator)
            .map { File(it, if (System.getProperty("os.name").startsWith("Windows")) "node.exe" else "node") }
            .firstOrNull { it.isFile && it.canExecute() }
        assumeTrue("Offline generated extension check requires local Node", executable != null)
        val root = Files.createTempDirectory("native-model-options").toFile()
        try {
            for (provider in listOf(ProviderType.OPENAI_COMPATIBLE, ProviderType.GOOGLE, ProviderType.OPENAI_SUBSCRIPTION)) {
                val profile = LlmProfile("fixture", "Fixture", provider = provider, modelId = "fixture")
                val parameters = buildJsonObject { put(if (provider == ProviderType.GOOGLE) "maxOutputTokens" else "max_tokens", 42) }
                val extension = File(root, "options.mjs").apply { writeText(piModelOptions(profile, parameters)) }
                val script = File(root, "check.mjs").apply { writeText("""
                    import assert from 'node:assert/strict';
                    import setup from './options.mjs';
                    const handlers = {};
                    setup({on:(name,handler) => handlers[name]=handler});
                    const handler=handlers.before_provider_request;
                    if (${provider == ProviderType.OPENAI_SUBSCRIPTION}) {
                      assert.equal(handler,undefined);
                    } else {
                      const controls={max_tokens:7,maxOutputTokens:8,temperature:0.9,enable_thinking:true,
                        chat_template_kwargs:{enable_thinking:true,unrelated:'retained'}};
                      const original={tools:[{name:'file-tool'}],messages:[{role:'user',content:'fixture'}],
                        ...(${provider == ProviderType.GOOGLE} ? {config:controls} : controls)};
                      const result=handler({payload:original});
                      const resolved=${provider == ProviderType.GOOGLE} ? result.config : result;
                      assert.equal(resolved.${if (provider == ProviderType.GOOGLE) "maxOutputTokens" else "max_tokens"},42);
                      assert.equal(resolved.temperature,undefined);
                      assert.equal(resolved.enable_thinking,undefined);
                      assert.equal(resolved.chat_template_kwargs.unrelated,'retained');
                      assert.equal(resolved.chat_template_kwargs.enable_thinking,undefined);
                      assert.equal(controls.enable_thinking,true);
                      assert.deepEqual(result.tools,original.tools);
                      assert.deepEqual(result.messages,original.messages);
                    }
                """.trimIndent()) }
                val process = ProcessBuilder(checkNotNull(executable).absolutePath, script.absolutePath).redirectErrorStream(true).start()
                try {
                    assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue(), process.inputStream.bufferedReader().readText())
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
        } finally { root.deleteRecursively() }
    }
}
