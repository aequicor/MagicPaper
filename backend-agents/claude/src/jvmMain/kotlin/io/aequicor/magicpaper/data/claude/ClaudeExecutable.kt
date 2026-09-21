package io.aequicor.magicpaper.data.claude

import io.aequicor.magicpaper.backend.NativeInstallationPhase
import io.aequicor.magicpaper.backend.NativeInstallationStatus
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Locates the user's own Claude Code installation. The application installs nothing: the vendor's installer owns the
 * binary and its updates, and the CLI owns the sign-in. A GUI process often lacks the shell's PATH, so the vendor's
 * usual install directories are searched as well.
 */
internal class ClaudeExecutable(
    private val override: String?,
    private val environment: (String) -> String? = System::getenv,
    private val home: String = System.getProperty("user.home").orEmpty(),
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
    /** The probe's failure is shown as a status; the owner of diagnostics records its cause. */
    private val report: (Throwable) -> Unit = {},
) {
    /** An explicit path is authoritative: a wrong one is reported, never replaced by another installation. */
    fun find(): File? {
        val explicit = override?.takeIf { it.isNotBlank() } ?: environment(OVERRIDE_VARIABLE)?.takeIf { it.isNotBlank() }
        if (explicit != null) return File(explicit).takeIf { it.isFile }
        return candidates().firstOrNull { it.isFile && (windows || it.canExecute()) }
    }

    fun candidates(): List<File> {
        val name = if (windows) "claude.exe" else "claude"
        val onPath = environment("PATH").orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map { File(it, name) }
        val known = buildList {
            add(File(home, ".local/bin/$name")); add(File(home, ".claude/local/$name")); add(File(home, ".bun/bin/$name"))
            if (!windows) { add(File("/opt/homebrew/bin/$name")); add(File("/usr/local/bin/$name")); add(File(home, ".npm-global/bin/$name")) }
        }
        return onPath + known
    }

    suspend fun status(): NativeInstallationStatus = withContext(Dispatchers.IO) {
        val file = find() ?: return@withContext NativeInstallationStatus(NativeInstallationPhase.ERROR,
            if (override != null || environment(OVERRIDE_VARIABLE) != null) "Указанный путь к Claude Code недоступен."
            else "Claude Code не найден. Установите его (claude.com/claude-code) или задайте MAGICPAPER_CLAUDE_PATH.")
        val version = try { version(file) } catch (failure: java.io.IOException) { report(failure); null }
        if (version == null) NativeInstallationStatus(NativeInstallationPhase.ERROR, "Claude Code не отвечает на проверку версии: ${file.path}")
        else NativeInstallationStatus(NativeInstallationPhase.READY, "Claude Code установлен: ${file.path}", version)
    }

    private fun version(file: File): String? {
        val process = ProcessBuilder(file.path, "--version").redirectErrorStream(true).start()
        process.outputStream.close()
        // Reading blocks until the child closes stdout; the deadline below is enforced by killing it.
        val output = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().use { output.append(it.readText().take(2000)) } } }
            .apply { isDaemon = true; start() }
        if (!process.waitFor(VERSION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(1000)
        return output.toString().trim().takeIf { process.exitValue() == 0 && it.isNotEmpty() }?.substringBefore(' ')
    }

    companion object {
        const val OVERRIDE_VARIABLE = "MAGICPAPER_CLAUDE_PATH"
        private const val VERSION_TIMEOUT_SECONDS = 15L
    }
}
