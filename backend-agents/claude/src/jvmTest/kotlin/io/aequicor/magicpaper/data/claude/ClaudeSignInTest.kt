package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.domain.EngineSignInResult
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

/** The child is a shell script that plays `claude auth`; nothing here opens a browser or reaches an account. */
class ClaudeSignInTest {
    private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private class Fixture(val home: File) : AutoCloseable {
        val binary = home.resolve("claude")
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
        fun signIn(timeoutMillis: Long = 10_000) = ClaudeSignIn(ClaudeExecutable(binary.path), object : NativeDiagnostics {
            override fun error(component: String, event: String, cause: Throwable, fields: Map<String, String>) { errors += event }
            override fun info(component: String, event: String, fields: Map<String, String>) { notes += event }
        }, timeoutMillis)
        fun childAlive() = ProcessHandle.of(home.resolve("pid").readText().trim().toLong()).map { it.isAlive }.orElse(false)
        override fun close() { home.deleteRecursively() }
    }

    private fun fixture() = Fixture(Files.createTempDirectory("claude-sign-in").toFile())

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
}
