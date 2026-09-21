package io.aequicor.magicpaper.data.research

import java.nio.file.Files
import java.nio.file.NoSuchFileException
import kotlin.test.*

/**
 * Разрешение исполняемого файла проверяется без песочницы: нас интересует только выбор файла,
 * а не запуск процесса. Регресс — CreateProcess error=193 на `./gradlew`, когда рядом лежит `gradlew.bat`.
 */
class ResearchCheckExecutableResolutionTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private val runner = SandboxCheckDriver(Files.createTempDirectory("research-check-resolve-"), 1000)
    private fun env() = mapOf("PATH" to "", "PATHEXT" to ".COM;.EXE;.BAT;.CMD")

    @Test fun windowsPrefersExecutableWrapperBesidePosixScript() {
        val dir = Files.createTempDirectory("research-check-wrapper-")
        try {
            Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\necho posix")
            if (windows) {
                Files.writeString(dir.resolve("gradlew.bat"), "@echo windows")
                assertEquals(dir.resolve("gradlew.bat").toRealPath().toString(), runner.resolveExecutable("./gradlew", dir, env()))
            } else {
                assertEquals(dir.resolve("gradlew").toRealPath().toString(), runner.resolveExecutable("./gradlew", dir, env()))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun explicitExtensionIsKeptAsIs() {
        val dir = Files.createTempDirectory("research-check-explicit-")
        try {
            val script = if (windows) "run.cmd" else "run.sh"
            Files.writeString(dir.resolve(script), if (windows) "@echo run" else "#!/bin/sh\necho run")
            assertEquals(dir.resolve(script).toRealPath().toString(), runner.resolveExecutable("./$script", dir, env()))
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun posixScriptWithoutWindowsCounterpartIsRefusedWithReason() {
        val dir = Files.createTempDirectory("research-check-posix-only-")
        try {
            Files.writeString(dir.resolve("gradlew"), "#!/bin/sh\necho posix")
            if (windows) {
                val failure = assertFailsWith<IllegalArgumentException> { runner.resolveExecutable("./gradlew", dir, env()) }
                assertTrue(failure.message!!.contains("gradlew"), failure.message)
                assertFalse(failure.message!!.contains("error=193"), failure.message)
            } else {
                assertEquals(dir.resolve("gradlew").toRealPath().toString(), runner.resolveExecutable("./gradlew", dir, env()))
            }
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun missingFileStillReportsAbsence() {
        val dir = Files.createTempDirectory("research-check-missing-")
        try { assertFailsWith<NoSuchFileException> { runner.resolveExecutable("./absent-tool", dir, env()) } }
        finally { dir.toFile().deleteRecursively() }
    }

    @Test fun pathSearchPrefersExecutableExtensionOnWindows() {
        val bin = Files.createTempDirectory("research-check-bin-")
        try {
            val tool = bin.resolve("tool")
            Files.writeString(tool, "#!/bin/sh\necho posix-tool")
            val expected = if (windows) {
                Files.writeString(bin.resolve("tool.exe"), "binary")
                bin.resolve("tool.exe")
            } else {
                tool.toFile().setExecutable(true)
                tool
            }
            val resolved = runner.resolveExecutable("tool", bin, mapOf("PATH" to bin.toString(), "PATHEXT" to ".COM;.EXE;.BAT;.CMD"))
            assertEquals(expected.toRealPath().toString(), resolved)
        } finally { bin.toFile().deleteRecursively() }
    }
}
