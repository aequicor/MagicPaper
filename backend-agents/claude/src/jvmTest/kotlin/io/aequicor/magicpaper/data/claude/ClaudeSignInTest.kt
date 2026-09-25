package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.domain.EngineSignInResult
import io.aequicor.magicpaper.domain.EngineSignOutResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/**
 * The child is a script that plays `claude auth`; nothing here opens a browser or reaches an account. Windows has no sh,
 * so there a batch file plays the sign-out.
 */
class ClaudeSignInTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private class Fixture(val home: File, private val windows: Boolean) : AutoCloseable {
        val binary = home.resolve(if (windows) "claude.cmd" else "claude")
        val errors = CopyOnWriteArrayList<String>()
        val notes = CopyOnWriteArrayList<String>()
        /** [login] runs for `auth login`; `auth status` answers [loggedIn]. */
        fun script(login: String, loggedIn: Boolean = true) {
            binary.writeText("""#!/bin/sh
D="${home.path}"
if [ "${'$'}1 ${'$'}2" = "auth status" ]; then echo '{ "loggedIn": $loggedIn }'; exit 0; fi
printf '%s\n' "${'$'}@" > "${'$'}D/args"
echo ${'$'}${'$'} > "${'$'}D/pid"
$login
""")
            binary.setExecutable(true)
        }
        /** `auth logout` ends with [exit]; `auth status` then answers [loggedIn]. */
        fun logout(exit: Int, loggedIn: Boolean) {
            if (!windows) return script("exit $exit", loggedIn)
            binary.writeText("""@echo off
if "%1 %2"=="auth status" (echo { "loggedIn": $loggedIn }& exit /b 0)
(for %%a in (%*) do @echo %%a)> "${home.path}\args"
exit /b $exit
""")
        }
        fun signIn(timeoutMillis: Long = 10_000, signOutTimeoutMillis: Long = 10_000) =
            ClaudeSignIn(ClaudeExecutable(binary.path), object : NativeDiagnostics {
                override fun error(component: String, event: String, cause: Throwable, fields: Map<String, String>) { errors += event }
                override fun info(component: String, event: String, fields: Map<String, String>) { notes += event }
            }, timeoutMillis, signOutTimeoutMillis)
        fun childAlive() = ProcessHandle.of(home.resolve("pid").readText().trim().toLong()).map { it.isAlive }.orElse(false)
        override fun close() { home.deleteRecursively() }
    }

    private fun fixture() = Fixture(Files.createTempDirectory("claude-sign-in").toFile(), windows)

    @Test fun completedLoginConfirmedByTheCliSignsIn() {
        if (windows) return
        fixture().use { f ->
            f.script("exit 0")
            assertEquals(EngineSignInResult.SignedIn, runBlocking { f.signIn().signIn() })
            assertEquals(listOf("auth", "login"), f.home.resolve("args").readLines())
            assertTrue(f.errors.isEmpty(), "${f.errors}")
        }
    }

    @Test fun failedLoginNamesTheManualCommandAndIsRecorded() {
        if (windows) return
        fixture().use { f ->
            f.script("exit 3")
            val result = assertIs<EngineSignInResult.Failed>(runBlocking { f.signIn().signIn() })
            assertContains(result.reason, "\"${f.binary.path}\" auth login")
            assertEquals(listOf("sign_in_failed"), f.errors.toList())
        }
    }

    @Test fun loginThatTheCliDoesNotConfirmIsNotReportedAsSuccess() {
        if (windows) return
        fixture().use { f ->
            f.script("exit 0", loggedIn = false)
            val result = assertIs<EngineSignInResult.Failed>(runBlocking { f.signIn().signIn() })
            assertContains(result.reason, "не подтвердил")
        }
    }

    @Test fun abandonedLoginTimesOutAndStopsTheChild() {
        if (windows) return
        fixture().use { f ->
            f.script("exec sleep 30")
            val result = assertIs<EngineSignInResult.Failed>(runBlocking { f.signIn(timeoutMillis = 3_000).signIn() })
            assertContains(result.reason, "не завершён")
            assertFalse(f.childAlive(), "The loopback listener must not outlive the attempt")
            assertEquals(listOf("sign_in_timed_out"), f.notes.toList())
        }
    }

    @Test fun cancellationStopsTheWaitingChild() {
        if (windows) return
        fixture().use { f ->
            f.script("exec sleep 30")
            runBlocking {
                val attempt = async { f.signIn().signIn() }
                withTimeout(10_000) { while (!f.home.resolve("pid").exists()) delay(20) }
                assertTrue(f.childAlive())
                attempt.cancel()
                attempt.join()
            }
            assertFalse(f.childAlive(), "Cancelling the sign-in must stop the CLI")
        }
    }

    @Test fun missingInstallationIsReportedWithoutLaunching() {
        if (windows) return
        fixture().use { f ->
            val result = assertIs<EngineSignInResult.Failed>(runBlocking { f.signIn().signIn() })
            assertContains(result.reason, "недоступен")
        }
    }

    @Test fun completedLogoutConfirmedByTheCliSignsOut() {
        fixture().use { f ->
            f.logout(exit = 0, loggedIn = false)
            assertEquals(EngineSignOutResult.SignedOut, runBlocking { f.signIn().signOut() })
            assertEquals(listOf("auth", "logout"), f.home.resolve("args").readLines().map { it.trim() })
            assertTrue(f.errors.isEmpty(), "${f.errors}")
        }
    }

    @Test fun failedLogoutNamesTheManualCommandAndIsRecorded() {
        fixture().use { f ->
            f.logout(exit = 1, loggedIn = true)
            val result = assertIs<EngineSignOutResult.Failed>(runBlocking { f.signIn().signOut() })
            assertContains(result.reason, "\"${f.binary.path}\" auth logout")
            assertEquals(listOf("sign_out_failed"), f.errors.toList())
        }
    }

    /** A login the CLI still reports would be shown again at once, as if nothing had happened. */
    @Test fun logoutThatLeavesTheCliSignedInIsNotReportedAsSuccess() {
        fixture().use { f ->
            f.logout(exit = 0, loggedIn = true)
            val result = assertIs<EngineSignOutResult.Failed>(runBlocking { f.signIn().signOut() })
            assertContains(result.reason, "по-прежнему сообщает о входе")
        }
    }

    @Test fun hungLogoutTimesOutAndStopsTheChild() {
        if (windows) return
        fixture().use { f ->
            f.script("exec sleep 30", loggedIn = false)
            val result = assertIs<EngineSignOutResult.Failed>(runBlocking { f.signIn(signOutTimeoutMillis = 2_000).signOut() })
            assertContains(result.reason, "не завершил выход")
            assertFalse(f.childAlive(), "The CLI must not outlive the sign-out")
            assertEquals(listOf("sign_out_timed_out"), f.notes.toList())
        }
    }

    @Test fun logoutWithoutAnInstallationIsReportedWithoutLaunching() {
        fixture().use { f ->
            val result = assertIs<EngineSignOutResult.Failed>(runBlocking { f.signIn().signOut() })
            assertContains(result.reason, "недоступен")
            assertFalse(f.home.resolve("args").exists())
        }
    }
}
