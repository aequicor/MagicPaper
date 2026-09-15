package io.aequicor.magicpaper.domain.tools

import kotlin.test.Test
import kotlin.test.assertEquals

class ToolDisplayNameTest {
    @Test fun imageInspectionUsesAReadableActivityName() {
        assertEquals("Анализ изображения", toolDisplayName("view_image"))
        assertEquals("Анализ изображения", toolDisplayName("functions:view_image"))
    }
}
