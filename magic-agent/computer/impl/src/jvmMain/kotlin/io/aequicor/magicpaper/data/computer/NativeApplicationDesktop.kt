package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bundled OS adapters use retained AX/UIA references. No target text is placed in a command line. */
internal class NativeApplicationDesktop(
    private val installation: Path = Path.of(System.getProperty("user.home"), ".MagicPaper", "coding", "native", "application-use"),
    private val launchProcess: (() -> Process)? = null,
) : ApplicationDesktop {
    companion object {
        val supported: Boolean get() = System.getProperty("os.name").startsWith("Windows") ||
            (System.getProperty("os.name").startsWith("Mac") &&
                (System.getProperty("os.version").substringBefore('.').toIntOrNull() ?: 0) >= 14)
    }
    private val lock = Any()
    private var closed = false
    private var process: Process? = null
    private var directory: Path? = null
    internal var executablePath: Path? = null
        private set
    private val reader = Executors.newSingleThreadExecutor { r -> Thread(r, "application-use-response").apply { isDaemon = true } }

    private fun start(): Process = synchronized(lock) {
        check(!closed) { "Доступ к приложениям отозван. Отправьте новый запрос." }
        process?.let { return it }
        launchProcess?.let { return it().also { child -> process = child } }
        val mac = System.getProperty("os.name").startsWith("Mac")
        check(supported) { "Application-use доступен на macOS 14+ и Windows." }
        val resource = if (mac) "application-use" else "application-use.ps1"
        val bytes = javaClass.getResourceAsStream("/computer/$resource").use { source ->
            checkNotNull(source) { "Адаптер application-use отсутствует в сборке. Переустановите MagicPaper." }.readBytes()
        }
        // TCC grants belong to a stable executable identity/path, not a new temporary file every turn.
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val dir = if (mac) Files.createDirectories(installation.resolve(hash)) else Files.createTempDirectory("magicpaper-application-").also { directory = it }
        val script = dir.resolve(resource)
        if (!Files.exists(script)) Files.write(script, bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
        check(Files.readAllBytes(script).contentEquals(bytes)) { "Application adapter integrity check failed" }
        if (mac) Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"))
        executablePath = script
        val command = if (mac) listOf(script.toString()) else listOf(
            Path.of(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            // Process-only policy for the checksum-verified bundled helper; machine/enterprise policy is unchanged.
            "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", script.toString(),
        )
        // Adapter errors are returned as allowlisted codes, not native exception/payload dumps.
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start().also { process = it }
    }

    override fun request(args: JsonObject, checkActive: () -> Unit): JsonObject {
        try {
            checkActive()
            val child = start()
            checkActive()
            val response = reader.submit<String> {
                checkActive()
                child.outputStream.write((args.toString() + "\n").toByteArray(Charsets.UTF_8))
                child.outputStream.flush()
                val out = java.io.ByteArrayOutputStream()
                val input = child.inputStream.buffered()
                // Native desktop PNGs can exceed the ordinary UIA/AX reply budget (bounded at 16 MP).
                val limit = if (args.optionalString("action") == "desktop_capture" && args.optionalString("resolution") == "native")
                    96 * 1024 * 1024 else 8 * 1024 * 1024
                while (out.size() <= limit) {
                    val byte = input.read()
                    check(byte >= 0) { "Application adapter terminated" }
                    if (byte == 10) return@submit out.toString(Charsets.UTF_8)
                    out.write(byte)
                }
                error("Application adapter response exceeds limit")
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25)
            var line: String? = null
            while (line == null) {
                checkActive()
                check(System.nanoTime() < deadline) { "Application adapter timeout" }
                try { line = response.get(100, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* Poll revocation/cancellation. */ }
            }
            checkActive()
            val result = Json.parseToJsonElement(line).jsonObject
            result.optionalString("error")?.let { throw ApplicationAdapterException(it) }
            return result
        } catch (error: kotlinx.coroutines.CancellationException) {
            closeAfterFailure(error)
            throw error
        } catch (error: ApplicationAdapterException) {
            throw error
        } catch (error: Exception) {
            closeAfterFailure(error)
            throw ApplicationAdapterException("unavailable", error)
        }
    }

    private fun closeAfterFailure(primary: Exception) {
        try { close() } catch (cleanup: Throwable) {
            AppLog.error("computer", "application.cleanup.failed", fields = mapOf("causeType" to cleanup.javaClass.simpleName))
            if (cleanup is kotlinx.coroutines.CancellationException && primary !is kotlinx.coroutines.CancellationException) {
                cleanup.addSuppressed(primary)
                throw cleanup
            }
            primary.addSuppressed(cleanup)
        }
    }

    /** Does not wait for a blocked provider. Closing the process invalidates every native reference. */
    override fun close(): Unit = synchronized(lock) {
        closed = true
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
            failure = combineComputerCleanup(failure, error)
        } }
        cleanup { process?.destroyForcibly(); process = null }
        cleanup { reader.shutdownNow() }
        directory?.let { dir -> cleanup {
            Files.list(dir).use { files -> files.forEach { Files.deleteIfExists(it) } }
            Files.deleteIfExists(dir)
            directory = null
        } }
        failure?.let { throw it }
    }
}

internal class ApplicationAdapterException(code: String, cause: Throwable? = null) : IllegalStateException(when (code) {
    "permission" -> "Разрешите адаптеру MagicPaper запись экрана и Универсальный доступ в системных настройках, затем отправьте новый запрос."
    "stale" -> "Окно или элемент изменились. Вызовите windows и inspect заново."
    "unsupported" -> "Программа не поддерживает это фоновое действие. Выберите доступный элемент; управление общей мышью не используется."
    "capture" -> "Не удалось получить снимок выбранного окна. Проверьте, что оно не свёрнуто и разрешена запись экрана, затем повторите screenshot."
    "capture_too_large" -> "Слишком большой снимок. Запросите screenshot с region для нужной области."
    else -> "Адаптер приложения недоступен или не ответил. Действие могло выполниться: отправьте новый запрос и проверьте окно, не повторяя ввод автоматически."
}, cause)
