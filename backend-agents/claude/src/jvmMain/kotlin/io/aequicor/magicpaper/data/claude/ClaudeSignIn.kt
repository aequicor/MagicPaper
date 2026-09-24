package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeSignIn
import io.aequicor.magicpaper.domain.EngineSignInResult
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

/**
 * `claude auth login` without a terminal. The CLI opens its sign-in page in the browser and finishes through its own
 * loopback callback, so the application only waits for the child and then asks the CLI whether it is signed in. Its
 * output carries the OAuth address with one-time state and is discarded rather than kept.
 */
internal class ClaudeSignIn(
    private val executable: ClaudeExecutable,
    private val diagnostics: NativeDiagnostics,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES),
) : NativeSignIn {
    override suspend fun signIn(): EngineSignInResult = withContext(Dispatchers.IO) {
        val file = executable.find() ?: return@withContext EngineSignInResult.Failed(executable.status().detail)
        val process = try {
            ProcessBuilder(file.path, "auth", "login").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        } catch (failure: IOException) {
            diagnostics.error(COMPONENT, "sign_in_launch_failed", failure, emptyMap())
            return@withContext EngineSignInResult.Failed("Не удалось запустить вход в Claude Code. Проверьте установку в настройках движков.")
        }
        // Cancellation interrupts the wait; the child and its loopback listener must not outlive the request.
        val exited = try { runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) } }
        finally { if (process.isAlive) stop(process) }
        when {
            !exited -> {
                diagnostics.info(COMPONENT, "sign_in_timed_out", mapOf("timeoutMinutes" to (timeoutMillis / 60_000).toString()))
                EngineSignInResult.Failed("Вход в Claude Code не завершён. Повторите попытку и подтвердите вход на открывшейся странице.")
            }
            process.exitValue() != 0 -> {
                diagnostics.error(COMPONENT, "sign_in_failed", IllegalStateException("Claude Code sign-in exited with ${process.exitValue()}"),
                    mapOf("exitCode" to process.exitValue().toString()))
                EngineSignInResult.Failed("Claude Code не выполнил вход. Повторите попытку или выполните в терминале: \"${file.path}\" auth login.")
            }
            // A CLI that cannot answer the status probe has still reported a completed login by its exit code.
            confirmed(file) == false -> EngineSignInResult.Failed("Claude Code не подтвердил вход. Повторите попытку.")
            else -> EngineSignInResult.SignedIn
        }
    }

    private fun confirmed(file: File): Boolean? = try { executable.signedIn(file) } catch (failure: IOException) {
        diagnostics.error(COMPONENT, "sign_in_status_failed", failure, emptyMap()); null
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private companion object {
        const val COMPONENT = "coding.claude"
        const val TIMEOUT_MINUTES = 10L
        const val STOP_GRACE_SECONDS = 2L
    }
}
