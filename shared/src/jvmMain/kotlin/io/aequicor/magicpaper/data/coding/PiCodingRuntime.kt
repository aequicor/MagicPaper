package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.CodingEvent
import io.aequicor.magicpaper.domain.CodingProject
import io.aequicor.magicpaper.domain.CodingRuntime
import io.aequicor.magicpaper.domain.RuntimePhase
import io.aequicor.magicpaper.domain.RuntimeStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Кодинг-бэкенд на пи-агенте (десктоп).
 *
 * Изоляция и жизненный цикл зависимостей:
 *  - всё живёт в корне [root] (по умолчанию ~/.MagicPaper/coding) —
 *    удаляется вместе с данными приложения ([uninstall]);
 *  - системный Node (>= 22.19) используется, если есть; иначе скачивается
 *    официальный дистрибутив Node в тот же корень — ничего не ставится в систему;
 *  - пи-агент ставится через `npm --prefix` в изолированную папку (без скриптов установки);
 *  - конфиг пи (модель из настроек приложения) и сессии — тоже в корне;
 *  - пи-процесс запускается с PI_OFFLINE/без телеметрии, расширения проекта не исполняются.
 */
class PiCodingRuntime(
    rootDir: File = File(File(System.getProperty("user.home"), ".MagicPaper"), "coding"),
) : CodingRuntime {

    override val supported: Boolean = true
    override val rootPath: String get() = root.absolutePath

    private val root: File = rootDir
    private val prefix = File(root, "prefix")
    private val nodeDir = File(root, "node")
    private val pihome = File(root, "pihome")
    private val sessionsDir = File(root, "sessions")
    private val piCli = File(prefix, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")

    private val installLock = Mutex()
    private var cachedNode: File? = null

    /** Текущий процесс агента — для прерывания извне. */
    private val runningProcess = AtomicReference<Process?>(null)

    override fun abort() {
        runningProcess.getAndSet(null)?.let { process ->
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    // ---- Состояние -------------------------------------------------------

    override suspend fun status(): RuntimeStatus = withContext(Dispatchers.IO) {
        if (!piCli.isFile) {
            RuntimeStatus(
                phase = RuntimePhase.CHECKING,
                detail = "Движок не установлен. Подготовка скачает и установит его автоматически.",
            )
        } else {
            readyStatus()
        }
    }

    override fun ensureReady(): Flow<RuntimeStatus> = flow {
        installLock.withLock {
            emit(RuntimeStatus(RuntimePhase.CHECKING, "Проверяю зависимости…"))
            try {
                if (piCli.isFile) {
                    emit(readyStatus())
                    return@flow
                }
                emit(RuntimeStatus(RuntimePhase.INSTALLING, "Ищу подходящий Node…"))
                val node = findNode { detail -> emit(RuntimeStatus(RuntimePhase.INSTALLING, detail)) }
                emit(RuntimeStatus(RuntimePhase.INSTALLING, "Ставлю пи-агент (изолированно)…"))
                installPi(node) { detail -> emit(RuntimeStatus(RuntimePhase.INSTALLING, detail)) }
                emit(readyStatus())
            } catch (e: Exception) {
                emit(
                    RuntimeStatus(
                        phase = RuntimePhase.ERROR,
                        detail = "Не удалось подготовить движок: ${e.message}",
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun uninstall(): Unit = withContext(Dispatchers.IO) {
        cachedNode = null
        root.deleteRecursively()
    }

    // ---- Запуск агента ----------------------------------------------------

    override fun run(project: CodingProject, prompt: String, settings: AppSettings): Flow<CodingEvent> = flow {
        val dir = File(project.path)
        if (!piCli.isFile) {
            emit(CodingEvent.Failed("Движок не установлен. Нажмите «Подготовить движок»."))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (!dir.isDirectory) {
            emit(CodingEvent.Failed("Папка проекта недоступна: ${project.path}"))
            emit(CodingEvent.Finished)
            return@flow
        }
        if (!settings.llmConfigured) {
            emit(CodingEvent.Failed("В настройках не указан источник модели (Base URL и имя модели)."))
            emit(CodingEvent.Finished)
            return@flow
        }
        val node = runCatching { findNode { } }.getOrNull()
        if (node == null) {
            emit(CodingEvent.Failed("Node не найден. Подготовьте движок заново."))
            emit(CodingEvent.Finished)
            return@flow
        }

        writePiConfig(settings)

        val args = mutableListOf(
            node.absolutePath, piCli.absolutePath,
            "--mode", "json",
            "--provider", PROVIDER_ID,
            "--model", settings.llmModel,
            "--session-dir", sessionsDir.absolutePath,
            "--no-extensions", "--no-skills", "--no-prompt-templates", "--no-themes",
            "--no-approve",
        )
        if (project.piSessionId.isNotBlank()) {
            args += listOf("--session-id", project.piSessionId)
        }
        args += listOf("--", prompt)

        val stderrFile = File.createTempFile("magicpaper-pi-stderr", ".log")
        stderrFile.deleteOnExit()
        val process = ProcessBuilder(args)
            .directory(dir)
            .redirectError(stderrFile)
            .apply { environment().putAll(piEnv(node)) }
            .start()
        // Пи в неинтерактивном режиме читает stdin до EOF (сливает его в промпт) —
        // из процесса пайп stdin без данных повесил бы агента; закрываем сразу.
        runCatching { process.outputStream.close() }
        runningProcess.set(process)

        var sawAnswer = false
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    val event = PiEventParser.parse(line) ?: continue
                    if (event is CodingEvent.FinalText || event is CodingEvent.Failed) sawAnswer = true
                    emit(event)
                }
            }
            val exit = process.waitFor()
            if (!sawAnswer) {
                val err = runCatching { stderrFile.readText() }.getOrDefault("").takeLast(600).trim()
                emit(CodingEvent.Failed(err.ifEmpty { "Агент завершился без ответа (код $exit)." }))
            }
        } finally {
            stderrFile.delete()
            runningProcess.compareAndSet(process, null)
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
            }
        }
        emit(CodingEvent.Finished)
    }.flowOn(Dispatchers.IO)

    // ---- Установка --------------------------------------------------------

    private suspend fun readyStatus(): RuntimeStatus {
        val node = runCatching { findNode { } }.getOrNull()
        val version = node?.let { runCatching { execLine(listOf(it.absolutePath, piCli.absolutePath, "--version")) }.getOrNull() }.orEmpty()
        return RuntimeStatus(
            phase = RuntimePhase.READY,
            detail = "Пи-агент готов. Изоляция: $rootPath",
            version = version.trim(),
        )
    }

    /** Ищет системный подходящий Node; иначе скачивает дистрибутив в корень изоляции. */
    private suspend fun findNode(progress: suspend (String) -> Unit): File {
        cachedNode?.takeIf { it.isFile }?.let { return it }

        for (candidate in nodeCandidates()) {
            val version = runCatching { execLine(listOf(candidate.absolutePath, "--version")) }.getOrNull()?.trim()
                ?: continue
            if (versionSatisfies(version)) {
                cachedNode = candidate
                return candidate
            }
        }

        val bundled = File(nodeDir, "bin/node").takeIf { it.isFile }
            ?: File(nodeDir, "node.exe").takeIf { it.isFile }
        if (bundled != null) {
            cachedNode = bundled
            return bundled
        }

        progress("Подходящий Node не найден — скачиваю дистрибутив…")
        return downloadNode(progress).also { cachedNode = it }
    }

    private fun nodeCandidates(): List<File> {
        val fromPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { File(it, "node") }
        val wellKnown = listOf(
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            "/opt/local/bin/node",
            "/usr/bin/node",
            File(System.getProperty("user.home"), ".local/bin/node").absolutePath,
        ).map { File(it) }
        return (fromPath + wellKnown).filter { it.isFile }
    }

    private suspend fun downloadNode(progress: suspend (String) -> Unit): File {
        val target = nodeTarget()
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val ext = if (isWindows) "zip" else "tar.gz"
        val archiveName = "node-$NODE_VERSION-$target.$ext"
        val url = URL("https://nodejs.org/dist/$NODE_VERSION/$archiveName")
        root.mkdirs()
        val archive = File(root, archiveName)

        url.openConnection().let { conn ->
            val http = conn as HttpURLConnection
            http.instanceFollowRedirects = true
            http.connectTimeout = 20_000
            http.readTimeout = 60_000
            http.connect()
            if (http.responseCode !in 200..299) error("сервер вернул код ${http.responseCode}")
            val total = http.contentLengthLong
            var received = 0L
            var lastReported = -1L
            http.inputStream.use { input ->
                archive.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        val mb = received / (1024 * 1024)
                        if (mb != lastReported) {
                            lastReported = mb
                            val totalMb = if (total > 0) " из ${total / (1024 * 1024)} МБ" else ""
                            progress("Скачиваю Node $NODE_VERSION: $mb МБ$totalMb…")
                        }
                    }
                }
            }
        }

        progress("Распаковываю Node…")
        nodeDir.mkdirs()
        runCommand(listOf("tar", "-xf", archive.absolutePath, "-C", root.absolutePath), root)
        archive.delete()
        val unpacked = File(root, "node-$NODE_VERSION-$target")
        if (!unpacked.isDirectory) error("архив Node распаковался неожиданно")
        // Кладём дистрибутив прямо в корень изоляции.
        if (nodeDir.exists()) nodeDir.deleteRecursively()
        if (!unpacked.renameTo(nodeDir)) {
            // Переименование между томами — копируем и чистим.
            unpacked.copyRecursively(nodeDir)
            unpacked.deleteRecursively()
        }
        val binary = if (isWindows) File(nodeDir, "node.exe") else File(nodeDir, "bin/node")
        if (!binary.isFile) error("после распаковки не найден исполняемый файл Node")
        if (!isWindows) binary.setExecutable(true)
        return binary
    }

    private suspend fun installPi(node: File, progress: suspend (String) -> Unit) {
        root.mkdirs()
        val npmCommand = npmCommandFor(node)
        val command = npmCommand + listOf(
            "install",
            "--prefix", prefix.absolutePath,
            "--ignore-scripts", "--no-audit", "--no-fund", "--no-progress",
            "$PI_PACKAGE@$PI_VERSION",
        )
        val env = if (node.parentFile != null) mapOf("PATH" to pathWith(node.parentFile)) else emptyMap()
        runCommand(command, root, extraEnv = env, timeoutSeconds = 600)
        if (!piCli.isFile) error("установка завершилась, но движок не найден в изоляции")
        writePiHomeDefaults()
    }

    /** npm рядом с Node; для скачанного дистрибутива — штатный путь через npm-cli.js. */
    private fun npmCommandFor(node: File): List<String> {
        val nodeHome = node.parentFile?.parentFile // bin/node -> <dist>
        val bundled = File(nodeDir, "lib/node_modules/npm/bin/npm-cli.js")
        if (bundled.isFile) return listOf(node.absolutePath, bundled.absolutePath)
        val viaHome = nodeHome?.let { File(it, "lib/node_modules/npm/bin/npm-cli.js") }
        if (viaHome != null && viaHome.isFile) return listOf(node.absolutePath, viaHome.absolutePath)
        val sibling = node.parentFile?.let { dir ->
            listOf(File(dir, "npm"), File(dir, "npm.cmd")).firstOrNull { it.isFile }
        }
        if (sibling != null) return listOf(sibling.absolutePath)
        error("npm не найден рядом с Node (${node.absolutePath})")
    }

    private fun writePiHomeDefaults() {
        pihome.mkdirs()
        File(pihome, "settings.json").writeText(
            """{"defaultProjectTrust":"never","telemetry":false}"""
        )
    }

    /** Модель из настроек приложения мостится в конфиг пи изолированно. */
    private fun writePiConfig(settings: AppSettings) {
        pihome.mkdirs()
        writePiHomeDefaults()
        sessionsDir.mkdirs()
        val key = settings.llmApiKey.ifBlank { "magicpaper" }
        val model = jsonEscape(settings.llmModel)
        val baseUrl = jsonEscape(settings.llmBaseUrl.trimEnd('/'))
        File(pihome, "models.json").writeText(
            """
            {"providers":{"$PROVIDER_ID":{
              "baseUrl":"$baseUrl",
              "api":"openai-completions",
              "apiKey":"${jsonEscape(key)}",
              "compat":{"supportsDeveloperRole":false,"supportsReasoningEffort":false},
              "models":[{"id":"$model","name":"$model","reasoning":false,"contextWindow":128000,"maxTokens":8192}]
            }}}
            """.trimIndent()
        )
    }

    private fun piEnv(node: File): Map<String, String> = mapOf(
        "PI_CODING_AGENT_DIR" to pihome.absolutePath,
        "PI_OFFLINE" to "1",
        "PI_SKIP_VERSION_CHECK" to "1",
        "PI_TELEMETRY" to "0",
        "PATH" to pathWith(node.parentFile),
    )

    private fun pathWith(dir: File?): String {
        val current = System.getenv("PATH") ?: "/usr/bin:/bin"
        return if (dir == null) current else dir.absolutePath + File.pathSeparator + current
    }

    // ---- Утилиты -----------------------------------------------------------

    private fun nodeTarget(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val osPart = when {
            os.contains("mac") || os.contains("darwin") -> "darwin"
            os.contains("win") -> "win"
            else -> "linux"
        }
        val archPart = when {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "x64"
        }
        return "$osPart-$archPart"
    }

    private fun versionSatisfies(version: String): Boolean {
        // "v22.23.2" -> [22, 23, 2]
        val parts = version.removePrefix("v").split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size < 2) return false
        val (major, minor) = parts
        return major > MIN_NODE_MAJOR || (major == MIN_NODE_MAJOR && minor >= MIN_NODE_MINOR)
    }

    private fun execLine(command: List<String>, timeoutSeconds: Long = 20): String {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("команда зависла: ${command.joinToString(" ")}")
        }
        return output.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
    }

    private fun runCommand(
        command: List<String>,
        dir: File,
        extraEnv: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 300,
    ) {
        val process = ProcessBuilder(command)
            .directory(dir)
            .redirectErrorStream(true)
            .apply { environment().putAll(extraEnv) }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            error("команда не завершилась за $timeoutSeconds с: ${command.take(3).joinToString(" ")}")
        }
        if (process.exitValue() != 0) {
            error("${command.first()} завершился с кодом ${process.exitValue()}: ${output.takeLast(400).trim()}")
        }
    }

    private fun jsonEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")

    private companion object {
        const val PI_PACKAGE = "@earendil-works/pi-coding-agent"
        const val PI_VERSION = "0.84.4"
        const val NODE_VERSION = "v22.23.2"
        const val PROVIDER_ID = "magicpaper"
        const val MIN_NODE_MAJOR = 22
        const val MIN_NODE_MINOR = 19
    }
}
