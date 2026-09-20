package io.aequicor.magicpaper.data.llm

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/** Опциональная проверка совместимости с реально установленным Codex app-server. */
class CodexAppServerOpenAiSubscriptionIntegrationTest {
    @Test
    fun initializesAndReadsIsolatedAccount() {
        if (System.getProperty("magicpaper.codex.it") != "true") return
        val root = createTempDirectory("magicpaper-codex-it").toFile()
        val service = CodexAppServerOpenAiSubscription(
            json = Json { ignoreUnknownKeys = true },
            appHome = root.resolve("home").toPath(),
        )
        try {
            val account = runBlocking { service.account() }
            assertFalse(account.signedIn, "в новом изолированном CODEX_HOME не должно быть аккаунта")
            val models = runBlocking {
                service.models(
                    LlmProfile(
                        id = "subscription",
                        name = "OpenAI subscription",
                        provider = ProviderType.OPENAI_SUBSCRIPTION,
                        modelId = "gpt-5.6-terra",
                    ),
                )
            }
            assertTrue(models.isNotEmpty(), "Codex app-server должен вернуть каталог моделей")
        } finally {
            service.close()
            root.deleteRecursively()
        }
    }
}
