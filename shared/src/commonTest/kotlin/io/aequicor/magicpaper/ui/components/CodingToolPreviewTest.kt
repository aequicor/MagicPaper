package io.aequicor.magicpaper.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodingToolPreviewTest {
    @Test fun shortCommandsAndPathsStayIntact() {
        for (title in listOf("", "⚒ read · src/main.kt", "⚒ write · README.md", "bash\necho готово")) {
            assertEquals(title, codingToolPreview(title))
        }
    }

    @Test fun largeSingleLinePayloadHasBoundedLayoutInput() {
        val title = "⚒ bash · " + "echo тест; ".repeat(100_000)
        val preview = codingToolPreview(title)
        assertTrue(preview.length <= 513)
        assertTrue(preview.endsWith("…"))
        assertTrue(title.startsWith(preview.dropLast(1)))
    }

    @Test fun heredocOnlyPreviewsTheFirstTwoLines() {
        val title = "⚒ bash · cat <<'EOF'\nfirst line\n" + "file content\n".repeat(100_000)
        assertEquals("⚒ bash · cat <<'EOF'\nfirst line…", codingToolPreview(title))
    }

    @Test fun windowsAndOldMacLineEndingsCountAsSingleBreaks() {
        for (newline in listOf("\r\n", "\r", "\n")) {
            assertEquals("read${newline}second…", codingToolPreview("read${newline}second${newline}third"))
        }
    }

    @Test fun exactCharacterBudgetNeedsNoEllipsis() {
        val title = "x".repeat(512)
        assertEquals(title, codingToolPreview(title))
    }

    @Test fun truncationDoesNotSplitAnEmoji() {
        val prefix = "x".repeat(511)
        assertEquals(prefix + "…", codingToolPreview(prefix + "😀suffix"))
    }
}
