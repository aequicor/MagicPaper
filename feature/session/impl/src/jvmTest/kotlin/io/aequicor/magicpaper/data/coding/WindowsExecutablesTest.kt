package io.aequicor.magicpaper.data.coding

import java.io.File
import java.nio.file.Files
import kotlin.test.*

class WindowsExecutablesTest {
    private val windows = WindowsExecutables.isWindows()
    private val extensions = WindowsExecutables.extensions(".COM;.EXE;.BAT;.CMD")

    @Test fun extensionsIgnoreBlankEntries() {
        assertEquals(listOf(".COM", ".EXE"), WindowsExecutables.extensions(".COM;;.EXE;"))
        assertEquals(emptyList(), WindowsExecutables.extensions(null))
    }

    @Test fun windowsTriesExecutableExtensionsBeforeBareName() {
        val expected = if (windows) listOf(".EXE", ".CMD", "") else listOf("")
        assertEquals(expected, WindowsExecutables.suffixes("gradlew", listOf(".EXE", ".CMD", "")))
        // Точное имя с известным расширением остаётся первым кандидатом.
        assertEquals(listOf("") + listOf(".EXE", ".CMD"), WindowsExecutables.suffixes("run.cmd", listOf(".EXE", ".CMD")))
    }

    @Test fun launchabilityFollowsExtensionOrPeImage() {
        val dir = Files.createTempDirectory("windows-executables-").toFile()
        try {
            val script = File(dir, "gradlew").apply { writeText("#!/bin/sh\necho posix") }
            val batch = File(dir, "gradlew.bat").apply { writeText("@echo windows") }
            val image = File(dir, "fixture-probe").apply { writeBytes(byteArrayOf('M'.code.toByte(), 'Z'.code.toByte(), 0, 0)) }
            if (windows) {
                assertTrue(WindowsExecutables.isLaunchable(batch, extensions))
                assertTrue(WindowsExecutables.isLaunchable(image, extensions), "PE-образ без расширения запускается полным путём")
                assertFalse(WindowsExecutables.isLaunchable(script, extensions))
                assertTrue(WindowsExecutables.isLaunchable(script, emptyList()), "Без PATHEXT правило не применяется")
            } else {
                assertTrue(WindowsExecutables.isLaunchable(script, extensions))
            }
        } finally { dir.deleteRecursively() }
    }
}
