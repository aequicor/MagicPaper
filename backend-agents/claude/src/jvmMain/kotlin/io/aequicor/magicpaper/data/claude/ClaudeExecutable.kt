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
    private val mac: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac", ignoreCase = true),
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
        return onPath + known + desktopBundled()
    }

    /**
     * Claude Code shipped inside the Claude desktop app, newest version first. It is a fallback: a separate
     * installation is preferred because it keeps its own sign-in, while the bundled one starts signed out.
     */
    private fun desktopBundled(): List<File> {
        if (!mac) return emptyList()
        val root = File(home, "Library/Application Support/Claude/claude-code")
        return root.listFiles { it.isDirectory }.orEmpty().sortedWith(compareByDescending(::versionKey))
            .map { File(it, "claude.app/Contents/MacOS/claude") }
    }

    private fun versionKey(directory: File): Long =
        directory.name.split('.').take(4).fold(0L) { total, part -> total * 10_000 + (part.toLongOrNull() ?: 0L) }

    suspend fun status(): NativeInstallationStatus = withContext(Dispatchers.IO) {
        val file = find() ?: return@withContext NativeInstallationStatus(NativeInstallationPhase.ERROR,
            if (override != null || environment(OVERRIDE_VARIABLE) != null) "Указанный путь к Claude Code недоступен."
            else "Claude Code не найден. Установите его (claude.com/claude-code) или задайте MAGICPAPER_CLAUDE_PATH.")
        val version = try { version(file) } catch (failure: java.io.IOException) { report(failure); null }
        if (version == null) return@withContext NativeInstallationStatus(NativeInstallationPhase.ERROR, "Claude Code не отвечает на проверку версии: ${file.path}")
        val signedIn = try { signedIn(file) } catch (failure: java.io.IOException) { report(failure); null }
        // The account is shown and signed in beside the engine, so the detail names only the installation.
        NativeInstallationStatus(NativeInstallationPhase.READY, "Claude Code установлен: ${file.path}", version, signedIn)
    }

    /** `claude auth status` prints JSON and exits 1 when signed out; an older CLI without it leaves the answer unknown. */
    fun signedIn(file: File): Boolean? = probe(file, "auth", "status")?.let { (_, text) ->
        Regex("\"loggedIn\"\\s*:\\s*(true|false)").find(text)?.groupValues?.get(1)?.toBooleanStrict()
    }

    private fun version(file: File): String? = probe(file, "--version")?.takeIf { (exit, _) -> exit == 0 }?.second
        ?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }

    /** Exit code and output of a short probe, or null when it does not finish in time. */
    private fun probe(file: File, vararg arguments: String): Pair<Int, String>? {
        val process = ProcessBuilder(file.path, *arguments).redirectErrorStream(true).start()
        process.outputStream.close()
        // Reading blocks until the child closes stdout; the deadline below is enforced by killing it.
        val output = StringBuilder()
        val reader = Thread { runCatching { process.inputStream.bufferedReader().use { output.append(it.readText().take(2000)) } } }
            .apply { isDaemon = true; start() }
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return null
        }
        reader.join(1000)
        return process.exitValue() to output.toString()
    }

    companion object {
        const val OVERRIDE_VARIABLE = "MAGICPAPER_CLAUDE_PATH"
        private const val PROBE_TIMEOUT_SECONDS = 15L
    }
}
