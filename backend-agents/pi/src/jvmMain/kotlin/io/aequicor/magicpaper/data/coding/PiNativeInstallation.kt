package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.backend.*
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Native dependencies stay within the existing isolated root; no host tools or settings are resolved here. */
class PiNativeInstallation(
    private val root: File,
    private val resources: NativeResources,
    private val diagnostics: NativeDiagnostics,
) : PiInstallation {
    private val prefix = File(root, "prefix")
    private val nodeDir = File(root, "node")
    private val pihome = File(root, "pihome")
    private val shellDir = File(root, "shell")
    private val toolsBinDir = File(root, "bin")
    private val piCli = File(prefix, "node_modules/@earendil-works/pi-coding-agent/dist/bundle/cli.js")
    override val cliPath get() = piCli.absolutePath
    private val rootPath get() = root.absolutePath
    private val installLock = Mutex()
    private var cachedNode: File? = null
    @Volatile private var searchToolsReady = false
    override suspend fun node() = withContext(Dispatchers.IO) { installLock.withLock { findNode {}.absolutePath } }
    override fun aiDirectory(): String? {
        val agent = File(prefix, "node_modules/@earendil-works/pi-coding-agent")
        val dist = "node_modules/@earendil-works/pi-ai/dist"
        return listOf(File(agent, dist), File(prefix, dist)).firstOrNull { File(it, "index.js").isFile }?.absolutePath
    }
    override fun bashPath() = windowsBashProbe()
    override fun prepareBundledTools() = ensureSearchTools()
    override fun toolsNotice() = searchToolsNotice()
    override fun homeDefaults(directory: String) = writePiHomeDefaults(File(directory))
    override fun environment(nodePath: String, home: String) = piEnv(File(nodePath), File(home))

    // ---- Бинарники поиска (fd, rg) ---------------------------------------

    /**
     * Инструменты движка, которым нужен внешний бинарник: `find` вызывает `fd`,
     * `grep` — `rg`. Pi запускается с `PI_OFFLINE=1` и сам докачать их не может,
     * поэтому бинарники входят в дистрибутив (Gradle-задача bundleCodingSearchTools).
     */
    internal val searchToolNames = listOf("fd", "rg")

    internal fun searchToolFileName(name: String): String = name + if (onWindows()) ".exe" else ""

    /** Каталог ресурсов с бинарниками под машину сборки: имя пишет Gradle-задача. */
    private val toolsResourceTarget: String by lazy {
        val bundled = try {
            resources.read("/coding/tools/$TOOLS_TARGET_FILE")?.toString(StandardCharsets.UTF_8)?.trim()
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: Exception) {
            diagnostics.error("coding.pi", "search_tools.target_read_failed", failure, mapOf("recovery" to "host_target"))
            null
        }
        bundled?.takeIf { it.matches(Regex("[a-z0-9][a-z0-9-]*")) } ?: nodeTarget()
    }

    /**
     * Раскладывает бинарники поиска из ресурсов дистрибутива в общий [toolsBinDir].
     * Не фатально: нет ресурса или не удалось записать — остаётся системный PATH, а
     * [searchToolsNotice] честно сообщает, чего не хватает.
     * Возвращает имена инструментов, чьи бинарники лежат в [toolsBinDir].
     */
    @Synchronized
    internal fun installBundledSearchTools(
        resource: (String) -> ByteArray? = { name ->
            resources.read("/coding/tools/$toolsResourceTarget/$name")
        },
    ): List<String> {
        copyBundledTool(resource(TOOLS_NOTICES_FILE), File(toolsBinDir, TOOLS_NOTICES_FILE))
        return searchToolNames.filter { name ->
            copyBundledTool(resource(searchToolFileName(name)), File(toolsBinDir, searchToolFileName(name)))
        }
    }

    /** Одна запись: перезаписывает только изменённое содержимое, возвращает наличие файла. */
    private fun copyBundledTool(content: ByteArray?, target: File): Boolean {
        if (content == null) return target.isFile
        return try {
            check(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "Cannot create native tool directory" }
            if (!target.isFile || !content.contentEquals(target.readBytes())) writeAtomically(target, content)
            if (!onWindows()) check(target.setExecutable(true, false)) { "Cannot mark native tool executable" }
            true
        } catch (failure: Exception) {
            diagnostics.error("coding.pi", "search_tools.install_failed", failure,
                mapOf("file" to target.name, "result" to "system_path"))
            target.isFile
        }
    }

    /**
     * Есть ли бинарь там, где его ищет pi: общий каталог дистрибутива или системный
     * PATH (pi проверяет `fd` и `fdfind`). Смотрим файлы, не запуская процессов.
     */
    internal fun searchToolAvailable(name: String): Boolean {
        val aliases = if (name == "fd") listOf("fd", "fdfind") else listOf(name)
        val suffix = if (onWindows()) ".exe" else ""
        if (aliases.any { File(toolsBinDir, it + suffix).isFile }) return true
        return (System.getenv("PATH") ?: "").split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .any { dir -> aliases.any { File(dir, it + suffix).isFile } }
    }

    /** Пустая строка, если нативным инструментам поиска хватает бинарников. */
    internal fun searchToolsNotice(): String {
        val missing = listOf(
            "fd" to "поиск по именам файлов",
            "rg" to "поиск по содержимому",
        ).filterNot { searchToolAvailable(it.first) }
            .joinToString(" и ") { "${it.first} (${it.second})" }
        if (missing.isEmpty()) return ""
        return "Не найдены $missing — «Поиск в проекте» будет завершаться ошибкой. " +
            "Установите эти утилиты в PATH или переустановите движок."
    }

    /** Один раз на процесс приложения: раскладка бинарников из дистрибутива. */
    private fun ensureSearchTools() {
        if (searchToolsReady) return
        installBundledSearchTools()
        searchToolsReady = true
    }

    override suspend fun status(): NativeInstallationStatus = withContext(Dispatchers.IO) {
        if (!piCli.isFile) {
            NativeInstallationStatus(
                phase = NativeInstallationPhase.CHECKING,
                detail = "Движок не установлен. Подготовка скачает и установит его автоматически.",
            )
        } else {
            readyStatus()
        }
    }

    override fun ensureReady(): Flow<NativeInstallationStatus> = flow {
        installLock.withLock {
            emit(NativeInstallationStatus(NativeInstallationPhase.CHECKING, "Проверяю зависимости…"))
            try {
                if (piCli.isFile) {
                    // Установка старше защиты кодировки — дупатчим на месте (идемпотентно).
                    patchBundleFuzzySafety()
                    ensureSearchTools()
                    emit(readyStatus())
                    return@flow
                }
                emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, "Ищу подходящий Node…"))
                val node = findNode { detail -> emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, detail)) }
                emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, "Ставлю пи-агент (изолированно)…"))
                installPi(node)
                if (onWindows()) {
                    emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, "Проверяю bash для команд агента…"))
                    ensureWindowsShell { detail -> emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, detail)) }
                }
                emit(NativeInstallationStatus(NativeInstallationPhase.INSTALLING, "Проверяю инструменты поиска…"))
                ensureSearchTools()
                emit(readyStatus())
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (e: Exception) {
                diagnostics.error("coding.pi", "installation_failed", e, mapOf("result" to "unavailable"))
                emit(
                    NativeInstallationStatus(
                        phase = NativeInstallationPhase.ERROR,
                        detail = "Не удалось подготовить движок. Проверьте подключение и повторите установку.",
                    )
                )
            }
        }
    }.flowOn(Dispatchers.IO)
    override suspend fun uninstall(): Unit = withContext(Dispatchers.IO) {
        installLock.withLock {
        cachedNode = null
        resetWindowsShellProbe()
        fuzzySafetyDone = false
        searchToolsReady = false
        check(!root.exists() || root.deleteRecursively()) { "Не удалось удалить зависимости движка" }
        }
    }

    private fun onWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("win")

    /**
     * Pi выполняет shell-команды агента только через bash: на Windows он ищет
     * Git Bash в Program Files, затем bash.exe в PATH, и доходит до System32\
     * bash.exe — заглушки WSL, которая падает с «execvpe(/bin/bash) failed»,
     * если в WSL нет дистрибутива. Здесь мы повторяем тот же порядок, но
     * отбрасываем WSL-заглушку, а при отсутствии bash скачиваем переносимый
     * MinGit в корень изоляции (установщик не нужен, удаляется с uninstall).
     * Кэш: решение принимается один раз за сессию приложения.
     */
    @Volatile
    private var cachedBash: String? = null

    /** Защита кодировки применена к текущей установке (не перечитывать чанки каждый прогон). */
    @Volatile
    private var fuzzySafetyDone = false

    override fun ensureFuzzySafety() {
        if (fuzzySafetyDone || !piCli.isFile) return
        patchBundleFuzzySafety()
        fuzzySafetyDone = true
    }

    private fun resetWindowsShellProbe() {
        cachedBash = null
    }

    /** Быстрый поиск готового bash без скачивания; null — работать через PowerShell. */
    private fun windowsBashProbe(): String? {
        cachedBash?.takeIf { File(it).isFile }?.let { return it }
        // Явный override (например, нестандартная установка MSYS2/Cygwin).
        System.getenv(WINDOWS_SHELL_ENV)?.takeIf { File(it).isFile }?.let {
            cachedBash = it
            return it
        }
        val candidates = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"))
            .map { File(it, "Git/bin/bash.exe") }
            .plus((System.getenv("PATH") ?: "").split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .map { File(it, "bash.exe") })
            .filter { it.isFile }
        val found = candidates.firstOrNull { !isLegacyWslBashStub(it) }
        if (found != null) {
            cachedBash = found.absolutePath
            return cachedBash
        }
        bundledBash()?.let {
            cachedBash = it.absolutePath
            return cachedBash
        }
        return null
    }

    /**
     * Оболочка для команд агента: найденный bash или скачанный MinGit.
     * null — bash недоступен (нет сети/архив повреждён): агенту включается
     * PowerShell-инструмент вместо bash.
     */
    private suspend fun ensureWindowsShell(progress: suspend (String) -> Unit): String? {
        if (!onWindows()) return null
        windowsBashProbe()?.let { return it }
        return try { downloadMinGit(progress).absolutePath }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            diagnostics.error("coding.pi", "shell_installation_failed", failure, mapOf("recovery" to "powershell"))
            progress("MinGit скачать не удалось — команды пойдут через PowerShell.")
            null
        }
    }

    /** System32/sysnative\bash.exe — реликер WSL, а не настоящий bash. */
    private fun isLegacyWslBashStub(file: File): Boolean {
        val normalized = file.absolutePath.replace('/', '\\').lowercase()
        return Regex("^[a-z]:\\\\windows\\\\(system32|sysnative)\\\\bash\\.exe$").matches(normalized)
    }

    private fun bundledBash(): File? {
        val bash = File(shellDir, "usr/bin/bash.exe")
        if (bash.isFile) return bash
        // Минимальный срез: sh.exe в MinGit — это GNU bash (полный режим при
        // имени argv[0]=bash); копия делается сразу после распаковки, но на
        // случай полу-установки проверяем и оригинал.
        val sh = File(shellDir, "usr/bin/sh.exe")
        return sh.takeIf { it.isFile }
    }

    private suspend fun downloadMinGit(progress: suspend (String) -> Unit): File {
        val arch = System.getProperty("os.arch").lowercase()
        val archPart = when {
            arch.contains("arm64") || arch.contains("aarch64") -> "arm64"
            arch.contains("64") -> "64-bit"
            else -> "32-bit"
        }
        val archiveName = "MinGit-$GIT_VERSION-$archPart.zip"
        val url = URL("$GIT_RELEASE_URL/$archiveName")
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
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        received += n
                        val mb = received / (1024 * 1024)
                        if (mb / 2 != lastReported / 2) {
                            lastReported = mb
                            val totalMb = if (total > 0) " из ${total / (1024 * 1024)} МБ" else ""
                            progress("Скачиваю bash для команд агента (MinGit): $mb МБ$totalMb…")
                        }
                    }
                }
            }
        }
        progress("Распаковываю MinGit…")
        shellDir.deleteRecursively()
        shellDir.mkdirs()
        unzipToDirectory(archive, shellDir)
        archive.delete()
        val sh = File(shellDir, "usr/bin/sh.exe")
        if (!sh.isFile) error("в архиве MinGit не найден usr/bin/sh.exe")
        // bash-совместимый вызов по имени: копируем sh.exe в bash.exe.
        val bash = File(shellDir, "usr/bin/bash.exe")
        sh.copyTo(bash, overwrite = true)
        cachedBash = bash.absolutePath
        return bash
    }

    // ---- Установка --------------------------------------------------------

    private suspend fun readyStatus(): NativeInstallationStatus {
        val node = findNode { }
        val version = execLine(listOf(node.absolutePath, piCli.absolutePath, "--version"))
        val shellNote = when {
            !onWindows() -> ""
            else -> {
                val bash = windowsBashProbe()
                if (bash != null) " Bash для команд: $bash." else " Bash не найден — команды агент будет выполнять через PowerShell."
            }
        }
        return NativeInstallationStatus(
            phase = NativeInstallationPhase.READY,
            detail = "Пи-агент готов. Изоляция: $rootPath.$shellNote" +
                searchToolsNotice().takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty(),
            version = version.trim(),
        )
    }

    /** Ищет системный подходящий Node; иначе скачивает дистрибутив в корень изоляции. */
    private suspend fun findNode(progress: suspend (String) -> Unit): File {
        cachedNode?.takeIf { it.isFile }?.let { return it }

        for (candidate in nodeCandidates()) {
            val version = probeNodeVersion(candidate) ?: continue
            if (versionSatisfies(version)) {
                cachedNode = candidate
                return candidate
            }
        }

        // Ранее скачанный дистрибутив используем только если он подходит по версии
        // (NODE_VERSION могла измениться с прошлых версий приложения).
        val bundled = File(nodeDir, "bin/node").takeIf { it.isFile }
            ?: File(nodeDir, "node.exe").takeIf { it.isFile }
        if (bundled != null) {
            val bundledVersion = probeNodeVersion(bundled)
            if (bundledVersion != null && versionSatisfies(bundledVersion)) {
                cachedNode = bundled
                return bundled
            }
            nodeDir.deleteRecursively()
        }

        progress("Подходящий Node не найден — скачиваю дистрибутив…")
        return downloadNode(progress).also { cachedNode = it }
    }

    private fun nodeCandidates(): List<File> {
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        // На Windows бинарник — node.exe; «node» без расширения ничего не найдёт.
        val names = if (isWindows) listOf("node.exe", "node") else listOf("node")
        val fromPath = (System.getenv("PATH") ?: "")
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .flatMap { dir -> names.map { name -> File(dir, name) } }
        val wellKnown = if (isWindows) {
            val programFiles = System.getenv("ProgramFiles") ?: "C:\\Program Files"
            names.map { name -> File(File(programFiles, "nodejs"), name) }
        } else {
            listOf(
                "/opt/homebrew/bin/node",
                "/usr/local/bin/node",
                "/opt/local/bin/node",
                "/usr/bin/node",
                File(System.getProperty("user.home"), ".local/bin/node").absolutePath,
            ).map { File(it) }
        }
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
                        currentCoroutineContext().ensureActive()
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
        if (isWindows) {
            // Системный tar на Windows капризен: в PATH может стоять GNU tar без
            // поддержки zip, а bsdtar не читает 8.3-имена и нелатиницу в %TEMP%.
            // Распаковываем штатным JVM-архиватором, без внешних бинарников.
            unzipToDirectory(archive, root)
        } else {
            runCommand(listOf("tar", "-xf", archive.absolutePath, "-C", root.absolutePath), root)
        }
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

    private suspend fun installPi(node: File) {
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
        patchBundleFuzzySafety()
        writePiHomeDefaults()
    }

    /**
     * Точечный патч бандла пи (проверено на 0.84.4 — живое repro в этой сессии).
     *
     * Встроенный edit при несовпадении oldText уходит в fuzzy-режим: переписывает
     * затронутые правкой СТРОКИ из нормализованной копии файла, по пути заменяя
     * типографику на ASCII (— → -, – ‘’“” → - ' "). Для русских текстов это
     * тихая порча файлов («поехала кодировка»): модель правит одну строку, а
     * тире/кавычки деградируют во всей затронутой области. После патча
     * снисходительность fuzzy остаёт только к концевым пробелам; несовпадение
     * символов даёт честную «Could not find the exact text» — агент перечитывает
     * файл и повторяет правку точно, ничего не портя.
     *
     * Идемпотентно (маркер в теле функции); при изменении внутренностей пи
     * просто не применяется и не мешает работе.
     */
    internal fun patchBundleFuzzySafety() {
        val chunksDir = File(piCli.parentFile, "chunks")
        // esbuild может положить в бандл несколько копий функции с суффиксами
        // («normalizeForFuzzyMatch2» — её использует json-режим) — правим все.
        val fn = Regex("function normalizeForFuzzyMatch\\d*\\(text\\)\\{")
        val patchedMarker = "/*magicpaper-fuzzy-safety*/"
        // Тело каждой копии заканчивается «}» перед следующим объявлением функции.
        val safeBody =
            "return $patchedMarker text.split(\"\\n\").map(line=>line.trimEnd()).join(\"\\n\")}"
        // Повторный проход даёт updated == text (тело уже safeBody) — не пишем.
        chunksDir.listFiles { f -> f.isFile && f.extension == "js" }?.forEach { chunk ->
            val text = chunk.readText(StandardCharsets.UTF_8)
            if (!fn.containsMatchIn(text)) return@forEach
            val updated = buildString {
                var last = 0
                var searchFrom = 0
                while (true) {
                    val match = fn.find(text, searchFrom) ?: break
                    val bodyStart = match.range.last + 1
                    val close = text.indexOf("}function", bodyStart)
                    if (close < 0) {
                        // Неизвестная структура — не рискуем, файл остаётся как есть.
                        append(text, last, text.length)
                        last = text.length
                        break
                    }
                    append(text, last, bodyStart)
                    append(safeBody)
                    last = close + 1
                    searchFrom = last
                }
                append(text, last, text.length)
            }
            if (updated != text) {
                chunk.writeText(updated, StandardCharsets.UTF_8)
            }
        }
    }

    /**
     * npm рядом с Node. Надёжнее запускать npm-cli.js тем же node-бинарником
     * (cmd-обёртки на Windows требуют cmd.exe, shell-обёртки — sh), поэтому
     * сначала ищем js-скрипт в обоих макетах дистрибутива:
     * unix — `<home>/lib/node_modules/npm`, Windows — `<home>/node_modules/npm`
     * (node.exe лежит прямо в home, в отличие от unix `bin/node`).
     */
    private fun npmCommandFor(node: File): List<String> {
        val dir = node.parentFile
        val homes = listOfNotNull(dir?.parentFile, dir)
        for (home in homes) {
            val npmCli = listOf(
                File(home, "lib/node_modules/npm/bin/npm-cli.js"),
                File(home, "node_modules/npm/bin/npm-cli.js"),
            ).firstOrNull { it.isFile }
            if (npmCli != null) return listOf(node.absolutePath, npmCli.absolutePath)
        }
        val sibling = dir?.let {
            val names = listOf("npm.cmd", "npm") // на Windows npm без расширения — shell-скрипт
            names.map { n -> File(it, n) }.firstOrNull { f -> f.isFile }
        }
        if (sibling != null) return listOf(sibling.absolutePath)
        error("npm не найден рядом с Node (${node.absolutePath})")
    }

    /** Распаковка zip чистым JVM с защитой от path traversal в записях архива. */
    private fun unzipToDirectory(zip: File, destDir: File) {
        val destRoot = destDir.canonicalFile
        ZipFile(zip).use { archive ->
            val entries = archive.entries().toList()
            // Сначала каталоги: иначе файл может оказаться раньше своего каталога.
            for (entry in entries.filter { it.isDirectory }) {
                val out = File(destRoot, entry.name)
                require(out.canonicalFile.startsWith(destRoot)) { "архив Node содержит опасный путь: ${entry.name}" }
                out.mkdirs()
            }
            for (entry in entries.filter { !it.isDirectory }) {
                val out = File(destRoot, entry.name)
                require(out.canonicalFile.startsWith(destRoot)) { "архив Node содержит опасный путь: ${entry.name}" }
                out.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output, 128 * 1024) }
                }
            }
        }
    }

    private fun writePiHomeDefaults(home: File = pihome) {
        home.mkdirs()
        // Используем штатный PowerShell на Windows даже при наличии Bash:
        // вложенные cmd /c и powershell -Command теряют quoting и exit code.
        val bash = windowsBashProbe()
        val toolsField = if (onWindows()) {
            "\"defaultTools\":[\"read\",\"powershell\",\"edit\",\"write\",\"grep\",\"find\",\"ls\"],"
        } else {
            ""
        }
        val shellField = if (bash != null) "\"shellPath\":\"${jsonEscape(bash)}\"," else ""
        File(home, "settings.json").writeText(
            """{"defaultProjectTrust":"never",${shellField}${toolsField}"telemetry":false}"""
        )
    }

    private fun writeAtomically(target: File, content: ByteArray) {
        val tmp = File(target.parentFile, "${target.name}.tmp-${System.nanoTime()}")
        tmp.writeBytes(content)
        runCatching {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            // rename между томами или антивирусная блокировка — обычная перезапись.
            target.writeBytes(content)
            tmp.delete()
        }
    }

    internal fun piEnv(node: File, home: File = pihome): Map<String, String> = mapOf(
        "PI_CODING_AGENT_DIR" to home.absolutePath,
        "PI_OFFLINE" to "1",
        "PI_SKIP_VERSION_CHECK" to "1",
        "PI_TELEMETRY" to "0",
        // На Windows в PATH добавляем бинарники автономного MinGit: подстраховка
        // для «where bash.exe» и unix-утилит, если shellPath когда-то разъедется.
        "PATH" to pathWithAll(
            listOf(toolsBinDir) + listOfNotNull(node.parentFile) +
                if (onWindows()) listOf(File(shellDir, "usr/bin"), File(shellDir, "mingw64/bin"))
                else emptyList()
        ),
    )

    private fun pathWith(dir: File?): String = pathWithAll(listOfNotNull(dir))

    private fun pathWithAll(dirs: List<File>): String {
        val current = System.getenv("PATH") ?: if (onWindows()) "" else "/usr/bin:/bin"
        val prefix = dirs.filter { it.isDirectory }.map { it.absolutePath }
        return (prefix + current.split(File.pathSeparator).filter { it.isNotBlank() })
            .distinct()
            .joinToString(File.pathSeparator)
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

    private suspend fun probeNodeVersion(node: File): String? = try {
        execLine(listOf(node.absolutePath, "--version")).trim()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        diagnostics.error("coding.pi", "node_probe_failed", failure, mapOf("recovery" to "next_candidate"))
        null
    }

    private suspend fun execLine(command: List<String>, timeoutSeconds: Long = 20): String =
        commandOutput(command, null, emptyMap(), timeoutSeconds).lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    private suspend fun runCommand(command: List<String>, dir: File,
        extraEnv: Map<String, String> = emptyMap(), timeoutSeconds: Long = 300) {
        commandOutput(command, dir, extraEnv, timeoutSeconds)
    }

    /** Redirecting output prevents a silent or verbose installer from blocking its timeout/cancellation. */
    internal suspend fun commandOutput(command: List<String>, dir: File?, extraEnv: Map<String, String>,
        timeoutSeconds: Long): String = withContext(Dispatchers.IO) {
        val output = java.nio.file.Files.createTempFile("magicpaper-native-install-", ".log").toFile()
        var child: Process? = null
        var primary: Throwable? = null
        try {
            val process = ProcessBuilder(command).directory(dir).redirectErrorStream(true).redirectOutput(output)
                .apply { environment().putAll(extraEnv) }.start().also { child = it }
            process.outputStream.close()
            val finished = runInterruptible { process.waitFor(timeoutSeconds, TimeUnit.SECONDS) }
            check(finished) { "Native installation command timed out" }
            check(process.exitValue() == 0) { "Native installation command exited with code ${process.exitValue()}" }
            output.reader(StandardCharsets.UTF_8).use { reader ->
                val buffer = CharArray(4096)
                val length = reader.read(buffer)
                if (length <= 0) "" else String(buffer, 0, length)
            }
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                child?.takeIf { it.isAlive }?.let { process ->
                    val descendants = process.descendants().use { it.toList() }
                    descendants.asReversed().forEach { it.destroyForcibly() }
                    descendants.forEach { if (it.isAlive) it.onExit().get(10, TimeUnit.SECONDS) }
                    process.destroyForcibly()
                    check(process.waitFor(10, TimeUnit.SECONDS)) { "Native installation process failed to stop" }
                }
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup
                primary.addSuppressed(cleanup)
                diagnostics.error("coding.pi", "installation_cleanup_failed", cleanup, mapOf("result" to "unknown"))
            } finally {
                if (output.exists() && !output.delete()) diagnostics.error("coding.pi", "installation_output_cleanup_failed",
                    IOException("Cannot remove native command output"), mapOf("result" to "temporary_file_retained"))
            }
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
        const val MIN_NODE_MAJOR = 22
        const val MIN_NODE_MINOR = 19
        const val GIT_VERSION = "2.55.0.5"
        const val GIT_RELEASE_URL = "https://github.com/git-for-windows/git/releases/download/v2.55.0.windows.5"
        const val WINDOWS_SHELL_ENV = "MAGICPAPER_SHELL_PATH"
        const val TOOLS_TARGET_FILE = "target.txt"
        const val TOOLS_NOTICES_FILE = "THIRD-PARTY-NOTICES.txt"
    }
}
