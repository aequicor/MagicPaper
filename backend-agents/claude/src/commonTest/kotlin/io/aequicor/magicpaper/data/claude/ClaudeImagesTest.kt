package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.domain.LlmProfile
import io.aequicor.magicpaper.domain.ProviderType
import kotlin.test.*

class ClaudeImagesTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test fun formatIsTheContentsNotTheNameOrDeclaredType() {
        assertEquals(ClaudeImageFormat.PNG, ClaudeImageFormat.of(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertEquals(ClaudeImageFormat.JPEG, ClaudeImageFormat.of(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertEquals(ClaudeImageFormat.GIF, ClaudeImageFormat.of("GIF89a".encodeToByteArray()))
        assertEquals(ClaudeImageFormat.WEBP, ClaudeImageFormat.of("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".encodeToByteArray()))
        assertNull(ClaudeImageFormat.of("BM\u0000\u0000".encodeToByteArray()), "BMP is not an image Claude takes")
        assertNull(ClaudeImageFormat.of("<svg xmlns".encodeToByteArray()))
    }

    @Test fun fileNameCarriesTheExtensionTheReadToolRecognises() {
        assertEquals("clipboard.png", ClaudeImageFormat.fileName("clipboard.png", ClaudeImageFormat.PNG))
        assertEquals("photo.jpeg", ClaudeImageFormat.fileName("photo.jpeg", ClaudeImageFormat.JPEG))
        assertEquals("Снимок.jpg", ClaudeImageFormat.fileName("Снимок.png", ClaudeImageFormat.JPEG), "A wrong extension is replaced")
        assertEquals("image.png", ClaudeImageFormat.fileName("image", ClaudeImageFormat.PNG), "A missing extension is added")
    }

    @Test fun windowIsTheModelsOnAnthropicsApiAndTheStandardOneThroughAGateway() {
        val subscription = LlmProfile("s", "Claude", "", provider = ProviderType.ANTHROPIC_SUBSCRIPTION, modelId = "opus")
        assertEquals(1_000_000L, ClaudeContext.window(subscription, ClaudeContext.LONG))
        assertEquals(1_000_000L, ClaudeContext.window(subscription.copy(baseUrl = "https://api.anthropic.com/v1"), ClaudeContext.LONG))
        assertEquals(200_000L, ClaudeContext.window(subscription.copy(baseUrl = "https://gw.example/anthropic"), ClaudeContext.LONG))
        assertEquals(200_000L, ClaudeContext.window(subscription, ClaudeContext.STANDARD))
        assertNull(ClaudeContext.window(subscription, null), "An unknown model waits for the CLI's own figure")
    }
}
