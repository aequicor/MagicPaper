package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeDiagnostics
import io.aequicor.magicpaper.backend.NativeSignIn
import io.aequicor.magicpaper.backend.NativeSignOut
import io.aequicor.magicpaper.domain.EngineSignInResult
import io.aequicor.magicpaper.domain.EngineSignOutResult
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
 *
 * `claude auth logout` is its counterpart. `auth status` reports a stored login whether or not its token still works,
 * so leaving the account is how a dead token is dropped before signing in afresh.
 */
internal class ClaudeSignIn(
    private val executable: ClaudeExecutable,
    private val diagnostics: NativeDiagnostics,
    private val timeoutMillis: Long = TimeUnit.MINUTES.toMillis(TIMEOUT_MINUTES),
    private val signOutTimeoutMillis: Long = TimeUnit.SECONDS.toMillis(SIGN_OUT_TIMEOUT_SECONDS),
) : NativeSignIn, NativeSignOut {
    override suspend fun signIn(): EngineSignInResult = withContext(Dispatchers.IO) {
        val file = executable.find() ?: return@withContext EngineSignInResult.Failed(executable.status().detail)
        val process = try { start(file, "login") } catch (failure: IOException) {
            diagnostics.error(COMPONENT, "sign_in_launch_failed", failure, emptyMap())
            return@withContext EngineSignInResult.Failed("Не удалось запустить вход в Claude Code. Проверьте установку в настройках движков.")
        }
        val exited = awaitExit(process, timeoutMillis)
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
            confirmed(file, "sign_in_status_failed") == false -> EngineSignInResult.Failed("Claude Code не подтвердил вход. Повторите попытку.")
            else -> EngineSignInResult.SignedIn
        }
    }

    override suspend fun signOut(): EngineSignOutResult = withContext(Dispatchers.IO) {
        val file = executable.find() ?: return@withContext EngineSignOutResult.Failed(executable.status().detail)
        val process = try { start(file, "logout") } catch (failure: IOException) {
            diagnostics.error(COMPONENT, "sign_out_launch_failed", failure, emptyMap())
            return@withContext EngineSignOutResult.Failed("Не удалось запустить выход из Claude Code. Проверьте установку в настройках движков.")
        }
        val exited = awaitExit(process, signOutTimeoutMillis)
        when {
            !exited -> {
                diagnostics.info(COMPONENT, "sign_out_timed_out", mapOf("timeoutSeconds" to (signOutTimeoutMillis / 1000).toString()))
                EngineSignOutResult.Failed("Claude Code не завершил выход. Повторите попытку.")
            }
            process.exitValue() != 0 -> {
                diagnostics.error(COMPONENT, "sign_out_failed", IllegalStateException("Claude Code sign-out exited with ${process.exitValue()}"),
                    mapOf("exitCode" to process.exitValue().toString()))
                EngineSignOutResult.Failed("Claude Code не выполнил выход. Повторите попытку или выполните в терминале: \"${file.path}\" auth logout.")
            }
            // A login that outlives the logout (a key in the environment, say) would be shown again at once.
            confirmed(file, "sign_out_status_failed") == true -> EngineSignOutResult.Failed(
                "Claude Code по-прежнему сообщает о входе. Повторите попытку или выполните в терминале: \"${file.path}\" auth logout.")
            else -> EngineSignOutResult.SignedOut
        }
    }

    private fun start(file: File, command: String): Process =
        ProcessBuilder(file.path, "auth", command).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()

    /** Cancellation interrupts the wait; the child and its loopback listener must not outlive the request. */
    private suspend fun awaitExit(process: Process, timeoutMillis: Long): Boolean =
        try { runInterruptible { process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS) } }
        finally { if (process.isAlive) stop(process) }

    private fun confirmed(file: File, failureEvent: String): Boolean? = try { executable.signedIn(file) } catch (failure: IOException) {
        diagnostics.error(COMPONENT, failureEvent, failure, emptyMap()); null
    }

    private fun stop(process: Process) {
        process.descendants().forEach { it.destroy() }
        process.destroy()
        if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    private companion object {
        const val COMPONENT = "coding.claude"
        const val TIMEOUT_MINUTES = 10L
        /** The CLI only deletes its stored credentials; it answers in about a second. */
        const val SIGN_OUT_TIMEOUT_SECONDS = 30L
        const val STOP_GRACE_SECONDS = 2L
    }
}
