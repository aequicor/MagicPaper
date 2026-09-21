package io.aequicor.magicpaper.data.coding

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PiNativeLaunchTest {
    @Test fun preparedLaunchPreservesArgumentsAndScopesEnvironmentToChild() {
        val root = Files.createTempDirectory("pi-launch with spaces ").toFile()
        var process: Process? = null
        try {
            // Java's source-file mode supplies a local protocol probe on every supported JVM host.
            val probe = File(root, "NativeProbe.java").apply { writeText("""
                class NativeProbe {
                    public static void main(String[] args) {
                        for (String arg : args) System.out.println(arg);
                        System.out.println("TOKEN=" + System.getenv("MAGICPAPER_LAUNCH_TEST_TOKEN"));
                    }
                }
            """.trimIndent()) }
            val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
            val request = PiLaunchRequest(java.absolutePath, probe.absolutePath, root.absolutePath, "model with spaces",
                File(root, "sessions").absolutePath, File(root, "prompt file.md").absolutePath,
                true, listOf(File(root, "extension file.mjs").absolutePath), listOf("read", "planning_git"), "high", "native-session",
                mapOf("MAGICPAPER_LAUNCH_TEST_TOKEN" to "child-only"), setOf("MAGICPAPER_LAUNCH_TEST_TOKEN"), File(root, "error.log").absolutePath)
            process = PiNativeAdapter().launch(request)
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "Local native protocol probe timed out")
            assertEquals(0, process.exitValue(), File(root, "error.log").readText())
            val arguments = process.inputStream.bufferedReader().readLines()
            assertEquals("model with spaces", arguments[arguments.indexOf("--model") + 1])
            assertEquals(request.systemPromptFile, arguments[arguments.indexOf("--system-prompt") + 1])
            assertEquals("read,planning_git", arguments[arguments.indexOf("--tools") + 1])
            assertContains(arguments, "--no-skills")
            assertContains(arguments, "--no-extensions")
            assertContains(arguments, "TOKEN=child-only")
            assertNull(System.getenv("MAGICPAPER_LAUNCH_TEST_TOKEN"))
        } finally {
            process?.destroyForcibly()
            root.deleteRecursively()
        }
    }
}
