package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.coding.OwnedCodingProcess
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.nio.file.*
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.*

internal data class ResearchCheckProgress(val sessionId: String, val output: String)
internal data class ResearchCheckResult(val output: String, val exitCode: Int?, val blockedReason: String? = null)

/** Only this host-owned service may launch research commands. There is no unsandboxed fallback. */
internal class ResearchCheckRunner(
    private val root: Path = Paths.get(System.getProperty("user.home"), ".MagicPaper", "research-checks"),
    private val sandbox: () -> ResearchSandbox = { ResearchSandbox.current() },
    private val timeoutMillis: Long = 15 * 60_000L,
) {
    val progress = MutableSharedFlow<ResearchCheckProgress>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val projectLocks = ConcurrentHashMap<Path, Mutex>()
    private val active = ConcurrentHashMap<String, Process>()
    private val owned by lazy { OwnedCodingProcess(root.resolve("owned").toFile()) }
    private val probeLock = Mutex()
    @Volatile private var probed = false

    init { Runtime.getRuntime().addShutdownHook(Thread({ abortAll() }, "research-check-shutdown")) }

    suspend fun run(projectPath: Path, sessionId: String, command: List<String>, subdirectory: String = "."): ResearchCheckResult = withContext(Dispatchers.IO) {
        try {
            require(command.isNotEmpty() && command.size <= 128 && command.all { it.length <= 16_384 && '\u0000' !in it }) { "Нужны команда и корректные аргументы" }
            val project = projectPath.toRealPath()
            require(!Paths.get(subdirectory).isAbsolute) { "Рабочая подпапка задаётся относительно проекта" }
            val cwd = project.resolve(subdirectory).normalize().toRealPath()
            require(cwd.startsWith(project) && Files.isDirectory(cwd)) { "Рабочая подпапка должна находиться внутри проекта" }
            require(command.first().isNotBlank()) { "Команда не задана" }
            projectLocks.computeIfAbsent(project) { Mutex() }.withLock {
                probe()
                val scratch = Files.createTempDirectory(safeRoot(), "run-${key(sessionId)}-")
                try {
                val artifactsFile = safeRoot().resolve("artifacts-${key(project.toString())}.json")
                val known = if (Files.isRegularFile(artifactsFile, LinkOption.NOFOLLOW_LINKS)) runCatching {
                    Json.parseToJsonElement(Files.readString(artifactsFile)).jsonObject.map { (path, hash) -> Paths.get(path) to hash.jsonPrimitive.content }.toMap()
                }.getOrDefault(emptyMap()) else emptyMap()
                val policy = ResearchWorkspacePolicy.inspect(project, scratch, known)
                val env = environment(scratch)
                val resolved = resolveExecutable(command.first(), cwd, env)
                val process = sandbox().launch(listOf(resolved) + command.drop(1), cwd, env, policy)
                check(active.putIfAbsent(sessionId, process) == null) {
                    process.destroyForcibly(); "Проверка этой сессии уже выполняется"
                }
                val output = StringBuilder()
                try {
                    if (process.isAlive) runCatching { owned.record(key(sessionId), process, attachLifetime = false) }.getOrElse {
                        if (process.isAlive) throw it
                    }
                    coroutineScope {
                        val reader = launch(Dispatchers.IO) {
                            process.inputStream.reader(Charsets.UTF_8).use { input ->
                                val buffer = CharArray(4096)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.append(buffer, 0, n)
                                    if (output.length > MAX_OUTPUT) output.delete(0, output.length - MAX_OUTPUT)
                                    progress.tryEmit(ResearchCheckProgress(sessionId, output.toString()))
                                }
                            }
                        }
                        try {
                            withTimeout(timeoutMillis) { while (process.isAlive) delay(50) }
                        } finally {
                            if (process.isAlive) process.destroyForcibly()
                            withContext(NonCancellable) {
                                withTimeoutOrNull(10_000) { while (process.isAlive) delay(25) }
                                if (withTimeoutOrNull(2000) { reader.join(); true } != true) {
                                    process.inputStream.close(); reader.cancel()
                                }
                            }
                        }
                    }
                    val note = if (process.exitValue() != 0 && policy.withheld.isNotEmpty())
                        "\nЗапись не предоставлена: ${policy.withheld.joinToString("; ")}. Исходники и нестандартные пути результатов защищены." else ""
                    val limitation = if (process.exitValue() != 0) "\nПроверки не могут записывать вне разрешённых каталогов, подключаться к пользовательским кэшам или оставлять фоновые процессы." +
                        if (System.getProperty("os.name").startsWith("Mac")) " macOS также блокирует posix_spawn и смену группы процессов; инструменты без запуска через fork/exec могут быть несовместимы." else "" else ""
                    ResearchCheckResult(output.toString() + note + limitation, process.exitValue())
                } finally {
                    process.destroyForcibly()
                    active.remove(sessionId, process)
                    if (!process.isAlive) {
                        owned.clear(key(sessionId))
                        val directories = policy.writable.filter { it != scratch.toRealPath() }
                        val snapshot = known.filterKeys { path -> directories.none { path.startsWith(it) } } + ResearchWorkspacePolicy.snapshot(directories)
                        val tmp = Files.createTempFile(safeRoot(), "artifacts-", ".tmp")
                        try {
                            Files.writeString(tmp, JsonObject(snapshot.mapKeys { it.key.toString() }.mapValues { JsonPrimitive(it.value) }).toString())
                            Files.move(tmp, artifactsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        } finally { Files.deleteIfExists(tmp) }
                    }
                }
                } finally { scratch.toFile().deleteRecursively() }
            }
        } catch (e: TimeoutCancellationException) { ResearchCheckResult("", null, "Проверка остановлена по таймауту") }
        catch (e: CancellationException) { abort(sessionId); throw e }
        catch (e: Exception) { ResearchCheckResult("", null, "Защищённая проверка недоступна: ${e.message}. Чтение и анализ остаются доступны.") }
    }

    /** An actual OS probe, not a flag/version check. All targets are disposable fixtures owned by the app. */
    internal suspend fun probe() = probeLock.withLock {
        if (probed) return@withLock
        val fixture = Files.createTempDirectory(safeRoot(), "sandbox-probe-")
        try {
            val source = Files.writeString(fixture.resolve("source.txt"), "protected")
            val git = Files.createDirectories(fixture.resolve(".git"))
            Files.writeString(git.resolve("index"), "index")
            val output = Files.createDirectories(fixture.resolve("artifacts"))
            val policy = ResearchWorkspacePolicy(fixture.toRealPath(), listOf(output.toRealPath()), listOf(git.toRealPath()), emptyList())
            val windows = System.getProperty("os.name").startsWith("Windows")
            val script = if (windows) listOf(
                Paths.get(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
                "-NoProfile", "-NonInteractive", "-Command",
                "\$ErrorActionPreference='SilentlyContinue'; Set-Content source.txt changed; Remove-Item source.txt; Rename-Item source.txt moved.txt; " +
                    "Set-Content forbidden.txt forbidden; Set-Content .git/index changed; Set-Content artifacts/ok.txt allowed; Write-Output PROBE_DONE"
            ) else listOf("/bin/sh", "-c",
                "printf changed > source.txt; rm -f source.txt; mv source.txt moved.txt; printf forbidden > forbidden.txt; printf changed > .git/index; printf allowed > artifacts/ok.txt; printf PROBE_DONE")
            val process = sandbox().launch(script, fixture.toRealPath(), environment(output), policy)
            try {
                val text = coroutineScope {
                    val read = async(Dispatchers.IO) { process.inputStream.readNBytes(64_000).toString(Charsets.UTF_8) }
                    try {
                        withTimeout(20_000) { while (process.isAlive) delay(50) }
                        withTimeout(2000) { read.await() }
                    } finally { process.destroyForcibly(); process.inputStream.close() }
                }
                check(process.exitValue() == 0 && "PROBE_DONE" in text && Files.readString(source) == "protected" &&
                    Files.readString(git.resolve("index")) == "index" && !Files.exists(fixture.resolve("forbidden.txt")) &&
                    !Files.exists(fixture.resolve("moved.txt")) && Files.readString(output.resolve("ok.txt")).trim() == "allowed") {
                    "ОС не подтвердила запрет записи в исходники: ${text.takeLast(1500)}"
                }
                probed = true
            } finally { process.destroyForcibly(); process.inputStream.close() }
        } finally { fixture.toFile().deleteRecursively() }
    }

    fun abort(sessionId: String) { active[sessionId]?.destroyForcibly() }
    fun abortAll() { active.values.forEach { it.destroyForcibly() } }
    suspend fun reconcile(sessionId: String) = withContext(Dispatchers.IO) {
        owned.reconcile(key(sessionId))
        if (!active.containsKey(sessionId)) Files.newDirectoryStream(safeRoot(), "run-${key(sessionId)}-*").use { paths ->
            paths.forEach { if (!Files.isSymbolicLink(it) && !WindowsResearchSandbox.unsafeLink(it)) it.toFile().deleteRecursively() }
        }
    }

    private fun safeRoot(): Path {
        val parent = Files.createDirectories(root.toAbsolutePath().normalize().parent).toRealPath()
        val path = parent.resolve(root.fileName)
        require(!Files.isSymbolicLink(path) && (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !WindowsResearchSandbox.unsafeLink(path))) { "Служебная папка исследования не может быть ссылкой" }
        Files.createDirectories(path)
        require(path.toRealPath() == path) { "Служебная папка исследования изменилась" }
        return path
    }

    private fun environment(scratch: Path): Map<String, String> = buildMap {
        val allowed = setOf("PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "PROGRAMFILES", "PROGRAMFILES(X86)",
            "PROGRAMW6432", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "SDKROOT", "DEVELOPER_DIR", "RUSTUP_HOME", "LANG", "LC_ALL")
        System.getenv().filterKeys { it.uppercase() in allowed }.forEach { (k, v) -> put(k, v) }
        fun directory(name: String) = Files.createDirectories(scratch.resolve(name)).toString()
        val tmp = directory("tmp")
        put("TMPDIR", tmp); put("TMP", tmp); put("TEMP", tmp)
        put("XDG_CACHE_HOME", directory("cache")); put("GRADLE_USER_HOME", directory("gradle"))
        put("NPM_CONFIG_CACHE", directory("npm")); put("PIP_CACHE_DIR", directory("pip")); put("CARGO_HOME", directory("cargo"))
        put("PYTHONPYCACHEPREFIX", directory("pycache")); put("DOTNET_CLI_HOME", directory("dotnet"))
        put("NUGET_PACKAGES", directory("nuget")); put("GIT_OPTIONAL_LOCKS", "0"); put("GIT_TERMINAL_PROMPT", "0")
        put("CI", "true")
        // macOS forbids posix_spawn because its attributes can escape a process group.
        // Java's supported fork launcher retains the sandbox and the owned process group.
        if (System.getProperty("os.name").startsWith("Mac")) put("JAVA_TOOL_OPTIONS", "-Djdk.lang.Process.launchMechanism=fork")
    }

    private fun resolveExecutable(name: String, cwd: Path, env: Map<String, String>): String {
        if (name.contains('/') || name.contains('\\')) return cwd.resolve(name).normalize().toRealPath().toString()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val suffixes = if (windows) listOf("") + (env["PATHEXT"] ?: ".EXE;.CMD;.BAT").split(';') else listOf("")
        val paths = env.entries.firstOrNull { it.key.equals("PATH", true) }?.value.orEmpty().split(File.pathSeparator)
        return paths.asSequence().filter(String::isNotBlank).flatMap { dir -> suffixes.asSequence().map { Paths.get(dir, name + it) } }
            .firstOrNull { Files.isRegularFile(it) && (windows || Files.isExecutable(it)) }?.toRealPath()?.toString()
            ?: error("Команда не найдена: $name")
    }
    private fun key(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    companion object {
        const val MAX_OUTPUT = 64_000
        val shared by lazy { ResearchCheckRunner() }
    }
}
