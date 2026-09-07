package io.aequicor.magicpaper.data.llm

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class CodexCommandResolutionTest {
    @Test
    fun usesBundledCodexFromMacAppBeforePathFallback() {
        val root = Files.createTempDirectory("magicpaper-codex-app")
        val executable = root.resolve("Contents/Resources/bin/codex")
        try {
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "#!/bin/sh\n")
            executable.toFile().setExecutable(true)

            assertEquals(
                executable.toAbsolutePath().toString(),
                CodexAppServerOpenAiSubscription.resolveCodexCommand(null, listOf(root)),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun explicitPathTakesPriorityOverBundledCodex() {
        assertEquals(
            "/custom/codex",
            CodexAppServerOpenAiSubscription.resolveCodexCommand(" /custom/codex ", emptyList()),
        )
    }
}
