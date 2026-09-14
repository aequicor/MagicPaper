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
    fun qwen37MultimodalSnapshotAndPlusSupportVision() {
        for (id in listOf("qwen3.7-max-2026-06-08", "qwen3.7-plus", "qwen3.7-plus-2026-05-26")) {
            assertTrue(ModelCapabilities.resolve(ProviderType.OPENAI_COMPATIBLE, id).vision, id)
        }
    }

    @Test
    fun qwen38AndNewerSupportVision() {
        for (id in listOf("qwen3.8-flash", "qwen3.8-max", "qwen3.8-max-0902", "qwen3.5-flash", "qwen3.5-plus")) {
            assertTrue(ModelCapabilities.resolve(ProviderType.OPENAI_COMPATIBLE, id).vision, id)
        }
    }

    @Test
    fun textOnlyQwenAliasesAndSnapshotsRemainTextOnly() {
        for (id in listOf("qwen3.7-max", "qwen3.7-max-preview", "qwen3.7-max-2026-05-17", "qwen3.7-max-2026-05-20", "qwen-max", "qwen3-max", "qwen-turbo")) {
            assertFalse(ModelCapabilities.resolve(ProviderType.OPENAI_COMPATIBLE, id).vision, id)
        }
    }

    @Test
    fun tokenPlanDoesNotInheritVisionFromAnotherQwenSnapshot() {
        val capabilities = ModelCapabilities.resolve(
            ProviderType.OPENAI_COMPATIBLE, " QWEN3.7-MAX ",
            "https://token-intl.aliyuncs.com/compatible-mode/v1",
        )
        assertFalse(capabilities.vision)
        assertFalse(capabilities.requiresAssistantAfterToolResult)
    }
}
