package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeInstallationPhase
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class ClaudeExecutableTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    private fun script(directory: File, body: String, name: String = "claude") =
        File(directory, name).apply { writeText("#!/bin/sh\n$body\n"); setExecutable(true) }

    @Test fun explicitPathIsAuthoritativeEvenWhenAnotherInstallationExists() {
        val home = Files.createTempDirectory("claude-locate").toFile()
        try {
            val other = script(home.resolve("bin").apply { mkdirs() }, "true")
            val finder = ClaudeExecutable(home.resolve("missing").path, { if (it == "PATH") other.parent else null }, home.path, windows = false)
            assertNull(finder.find())
            assertEquals(ClaudeExecutable.OVERRIDE_VARIABLE, "MAGICPAPER_CLAUDE_PATH")
        } finally { home.deleteRecursively() }
    }

    @Test fun environmentVariableIsUsedWhenNoOverrideIsGiven() {
        val home = Files.createTempDirectory("claude-locate").toFile()
        try {
            val binary = script(home, "true")
            val finder = ClaudeExecutable(null, { if (it == ClaudeExecutable.OVERRIDE_VARIABLE) binary.path else null }, home.path, windows = false)
            assertEquals(binary, finder.find())
        } finally { home.deleteRecursively() }
    }

    @Test fun vendorInstallDirectoryIsFoundWithoutAShellPath() {
        val home = Files.createTempDirectory("claude-locate").toFile()
        try {
            val binary = script(home.resolve(".local/bin").apply { mkdirs() }, "true")
            assertEquals(binary, ClaudeExecutable(null, { null }, home.path, windows = false).find())
            assertContains(ClaudeExecutable(null, { null }, home.path, windows = false).candidates(), binary)
        } finally { home.deleteRecursively() }
    }

    @Test fun desktopAppBundleIsTheLastResortAndTheNewestVersionWins() {
        val home = Files.createTempDirectory("claude-locate").toFile()
        try {
            fun bundled(version: String) = script(home.resolve("Library/Application Support/Claude/claude-code/$version/claude.app/Contents/MacOS")
                .apply { mkdirs() }, "true")
            val old = bundled("2.1.9"); val newest = bundled("2.1.10")
            val finder = ClaudeExecutable(null, { null }, home.path, windows = false, mac = true)
            assertEquals(newest, finder.find())
            assertEquals(listOf(newest, old), finder.candidates().filter { it.path.contains("claude-code") })
            val standalone = script(home.resolve(".local/bin").apply { mkdirs() }, "true")
            assertEquals(standalone, finder.find())
            assertTrue(ClaudeExecutable(null, { null }, home.path, windows = false, mac = false).candidates().none { it.path.contains("claude-code") })
        } finally { home.deleteRecursively() }
    }

    @Test fun signedOutInstallationIsReadyButTellsHowToSignIn() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-status").toFile()
        try {
            val binary = script(home, "case \"\$1\" in --version) echo '2.1.275 (Claude Code)';; auth) echo '{ \"loggedIn\": false }'; exit 1;; esac")
            val status = ClaudeExecutable(binary.path).status()
            assertEquals(NativeInstallationPhase.READY, status.phase)
            assertContains(status.detail, "auth login")
            assertEquals("2.1.275", status.version)
            val signedIn = script(home, "case \"\$1\" in --version) echo '2.1.275 (Claude Code)';; auth) echo '{ \"loggedIn\": true }';; esac", "claude2")
            assertFalse("auth login" in ClaudeExecutable(signedIn.path).status().detail)
            val old = script(home, "case \"\$1\" in --version) echo '1.0.0 (Claude Code)';; *) exit 1;; esac", "claude3")
            assertEquals(NativeInstallationPhase.READY, ClaudeExecutable(old.path).status().phase)
            assertFalse("auth login" in ClaudeExecutable(old.path).status().detail)
        } finally { home.deleteRecursively() }
    }

    @Test fun readyStatusCarriesTheInstalledVersion() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-status").toFile()
        try {
            val binary = script(home, "echo '2.1.275 (Claude Code)'")
            val status = ClaudeExecutable(binary.path).status()
            assertEquals(NativeInstallationPhase.READY, status.phase)
            assertEquals("2.1.275", status.version)
        } finally { home.deleteRecursively() }
    }

    @Test fun unlaunchableFileIsAnErrorAndItsCauseIsReported() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-status").toFile()
        try {
            val notExecutable = home.resolve("claude").apply { writeText("#!/bin/sh\ntrue\n") }
            val reported = mutableListOf<Throwable>()
            val status = ClaudeExecutable(notExecutable.path, report = { reported += it }).status()
            assertEquals(NativeInstallationPhase.ERROR, status.phase)
            assertEquals(1, reported.size)
        } finally { home.deleteRecursively() }
    }

    @Test fun missingOrBrokenInstallationIsAnErrorWithAnAction() = runBlocking {
        if (windows) return@runBlocking
        val home = Files.createTempDirectory("claude-status").toFile()
        try {
            val absent = ClaudeExecutable(null, { null }, home.path, windows = false).status()
            assertEquals(NativeInstallationPhase.ERROR, absent.phase)
            assertContains(absent.detail, "MAGICPAPER_CLAUDE_PATH")
            val broken = ClaudeExecutable(script(home, "exit 3").path).status()
            assertEquals(NativeInstallationPhase.ERROR, broken.phase)
            assertEquals(NativeInstallationPhase.ERROR, ClaudeExecutable(home.resolve("nope").path).status().phase)
        } finally { home.deleteRecursively() }
    }
}
