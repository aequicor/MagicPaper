package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun glmOnZaiEndpointUsesTheThinkingSwitch() {
        // Z.AI переключает мышление полем `thinking`; на своём сервере это факт,
        // а не догадка pi по адресу.
        assertEquals(
            "zai",
            ModelCapabilities.resolve(
                ProviderType.OPENAI_COMPATIBLE,
                "glm-5.3-flash",
                "https://api.z.ai/api/paas/v4",
            ).thinkingFormat,
        )
        assertEquals(
            "zai",
            ModelCapabilities.resolve(
                ProviderType.OPENAI_COMPATIBLE,
                "glm-4.7",
                "https://open.bigmodel.cn/api/paas/v4",
            ).thinkingFormat,
        )
        // В маршруте агрегатора формы `thinking` нет: там `reasoning.effort`.
        assertNull(
            ModelCapabilities.resolve(
                ProviderType.OPENROUTER,
                "z-ai/glm-5.3",
                "https://openrouter.ai/api/v1",
            ).thinkingFormat,
        )
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
