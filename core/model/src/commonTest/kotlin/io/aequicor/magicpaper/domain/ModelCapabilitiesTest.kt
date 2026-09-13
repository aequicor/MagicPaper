package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelCapabilitiesTest {
    @Test
    fun multimodalQwenSnapshotSupportsImagesAndToolResultCompatibility() {
        val capabilities = ModelCapabilities.resolve(
            ProviderType.OPENAI_COMPATIBLE,
            " QWEN3.7-MAX-2026-06-08 ",
        )
        assertTrue(capabilities.vision)
        assertTrue(capabilities.requiresAssistantAfterToolResult)
    }

    @Test
    fun textOnlyQwenAliasesAndSnapshotsRemainTextOnly() {
        for (id in listOf("qwen3.7-max", "qwen3.7-max-preview", "qwen3.7-max-2026-05-20", "qwen-max")) {
            assertFalse(ModelCapabilities.resolve(ProviderType.OPENAI_COMPATIBLE, id).vision, id)
        }
    }
}
