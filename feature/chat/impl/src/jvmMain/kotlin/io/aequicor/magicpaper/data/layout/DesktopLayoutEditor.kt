package io.aequicor.magicpaper.data.layout

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Launches only the installed Paper Editor executable, with argument arrays (never a shell). */
class DesktopLayoutEditor(
    private val executable: () -> Path = ::locatePaperEditor,
    private val stateDirectory: Path = Path.of(System.getProperty("user.home"), ".magicpaper", "paper-editor", "chat"),
) : LayoutEditor {
    private data class Window(val process: Process, val ready: Path, var connected: Boolean = false)
    private val windows = mutableMapOf<Path, Window>()
    private val openMutex = Mutex()

    override suspend fun open(project: CodingProject, conversationId: String): LayoutWorkspace = boundary {
        require(conversationId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val binary = executable()
        val projectRoot = Path.of(project.path).toRealPath()
        val directory = projectRoot.resolve("design/layouts/chat-$conversationId")
        var owned = projectRoot
        for (segment in listOf("design", "layouts", "chat-$conversationId")) {
            owned = owned.resolve(segment)
            if (!Files.exists(owned)) Files.createDirectory(owned)
            require(owned.toRealPath().startsWith(projectRoot)) { "Layout folder escapes project" }
        }
        val canonical = directory.toRealPath()
        require(canonical.startsWith(projectRoot)) { "Layout folder escapes project" }
        val source = canonical.resolve("screen.layout.md")
        if (!Files.exists(source)) Files.writeString(source, EmptyLayout, java.nio.file.StandardOpenOption.CREATE_NEW)
        require(!Files.isSymbolicLink(source)) { "Layout source must not be a symlink" }
        require(Files.size(source) <= MaxSourceBytes)
        val initial = Files.readString(source)
        Files.createDirectories(stateDirectory)
        val key = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray()).take(12).joinToString("") { "%02x".format(it) }
        val runtime = stateDirectory.resolve(key).also(Files::createDirectories)
        val catalog = batch(binary, listOf("catalog"), runtime).toString()
        openMutex.withLock {
            windows.entries.removeAll { !it.value.process.isAlive }
            var window = windows[canonical]
            val activeFolder = window?.ready?.takeIf(Files::exists)?.let(Files::readString)
            if (window == null || (window.connected && activeFolder != canonical.toString())) {
                val ready = Files.createTempFile(runtime, "window-", ".ready")
                val process = ProcessBuilder(binary.toString(), "--project", canonical.toString(), "--ready-file", ready.toString(),
                    "--data-dir", runtime.resolve("settings").toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                window = Window(process, ready)
                windows[canonical] = window
            }
            val opened = window
            val connected = withTimeoutOrNull(30_000) {
                while (!Files.exists(opened.ready) || Files.readString(opened.ready) != canonical.toString()) {
                    if (!opened.process.isAlive) throw LayoutEditorException("Paper Editor завершился при открытии проекта. Повторите запрос.")
                    delay(100)
                }
                true
            }
            if (connected != true) throw LayoutEditorException("Paper Editor не открыл проект вовремя. Проверьте его окно и повторите запрос.")
            opened.connected = true
        }
        AppLog.info("layout-editor", "project.opened", mapOf("projectId" to project.id, "sessionId" to conversationId))
        LayoutWorkspace(project.id, canonical.toString(), initial, catalog)
    }

    override suspend fun render(workspace: LayoutWorkspace, source: String): LayoutRender = boundary {
        require(source.toByteArray().size <= MaxSourceBytes)
        Files.createDirectories(stateDirectory)
        val temp = Files.createTempDirectory(stateDirectory, "render-")
        try {
            val input = temp.resolve("screen.layout.md"); val png = temp.resolve("preview.png")
            Files.writeString(input, source)
            val result = batch(executable(), listOf("render", input.toString(), png.toString()), temp)
            val valid = result["valid"]?.jsonPrimitive?.booleanOrNull == true
            if (!valid) return@boundary LayoutRender(false, result["diagnostics"]?.jsonPrimitive?.content.orEmpty().take(8000))
            require(Files.size(png) <= 16 * 1024 * 1024) { "Preview too large" }
            LayoutRender(true, "", Attachment.fromBytes("Макет.png", "image/png", Files.readAllBytes(png)))
        } finally { deleteTemporaryDirectory(temp) }
    }

    override suspend fun publish(workspace: LayoutWorkspace, source: String): Unit = boundary {
        val directory = Path.of(workspace.directory).toRealPath()
        val target = directory.resolve("screen.layout.md")
        require(source.toByteArray().size <= MaxSourceBytes)
        if (Files.isSymbolicLink(target) || Files.readString(target) != workspace.source) {
            throw LayoutEditorException("Макет изменён в редакторе во время работы агента. Ваши правки сохранены. Повторите запрос, чтобы продолжить с новой версии.")
        }
        val staged = Files.createTempFile(directory, ".agent-", ".tmp")
        try {
            Files.writeString(staged, source)
            Files.writeString(directory.resolve("screen.before-agent.bak"), workspace.source)
            // Recheck after staging and backup; external editor changes must not be silently replaced.
            if (Files.readString(target) != workspace.source) throw LayoutEditorException("Макет изменён в редакторе. Повторите запрос с новой версии.")
            Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { deleteTemporaryFile(staged) }
        AppLog.info("layout-editor", "layout.published", mapOf("projectId" to workspace.projectId))
    }

    private suspend fun batch(binary: Path, arguments: List<String>, directory: Path): JsonObject {
        val response = Files.createTempFile(directory, "result-", ".json")
        var process: Process? = null
        try {
            process = ProcessBuilder(listOf(binary.toString(), "--agent", arguments.first(), response.toString()) + arguments.drop(1))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val completed = withTimeoutOrNull(60_000) { while (process.isAlive) delay(50); true }
            if (completed != true) throw LayoutEditorException("Paper Editor не завершил проверку вовремя. Повторите запрос.")
            if (process.exitValue() != 0 || Files.size(response) !in 1..1_048_576) {
                throw LayoutEditorException("Paper Editor не смог проверить макет. Повторите запрос.")
            }
            return Json.parseToJsonElement(Files.readString(response)).jsonObject
        } finally {
            if (process?.isAlive == true) {
                process.destroyForcibly()
                withContext(NonCancellable + Dispatchers.IO) { process.waitFor() }
            }
            deleteTemporaryFile(response)
        }
    }

    private suspend fun <T> boundary(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        try { action() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: LayoutEditorException) { throw known }
        catch (failure: Exception) {
            throw LayoutEditorException("Не удалось открыть или сохранить макет. Проверьте доступность Paper Editor и папки проекта, затем повторите запрос.", failure)
        }
    }

    private fun deleteTemporaryFile(path: Path) {
        try { Files.deleteIfExists(path) }
        catch (failure: Exception) { AppLog.error("layout-editor", "temporary_cleanup.failed", failure) }
    }

    private fun deleteTemporaryDirectory(path: Path) {
        try { Files.list(path).use { entries -> entries.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(path) }
        catch (failure: Exception) { AppLog.error("layout-editor", "temporary_cleanup.failed", failure) }
    }

    companion object {
        const val MaxSourceBytes = 512 * 1024L
        val EmptyLayout = """
            ---
            screen: mockup
            sourceLocale: en-US
            libraries:
              - id: paper
                source: paper
            ---

            # Mockup

            ## Frame: Mockup id canvas 960 by 720 color #F3EBDD

            ### Instance: Heading id heading of PaperWorkspaceHeading library paper 800 by 100 position 32 24 props (text «Новый макет») variant (platform macOS textScale «1»)

            ### Instance: Button id primary of PaperButton library paper 200 by 48 position 32 160 props (text «Продолжить») variant (state NORMAL platform macOS textScale «1» kind PRIMARY)
        """.trimIndent() + "\n"
    }
}

private fun locatePaperEditor(): Path {
    val configured = System.getProperty("magicpaper.paperEditor.executable") ?: System.getenv("MAGICPAPER_PAPER_EDITOR")
    if (configured != null) return Path.of(configured).also { require(Files.isExecutable(it)) }
    val roots = buildList {
        System.getProperty("compose.application.resources.dir")?.let { add(Path.of(it).resolve("paper-editor")) }
        generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath()) { it.parent }.take(6).forEach {
            add(it.resolve("tools/paper-editor/build/compose/binaries/main/app"))
        }
    }
    val paths = listOf("PaperEditor.app/Contents/MacOS/PaperEditor", "PaperEditor/bin/PaperEditor", "PaperEditor/PaperEditor.exe")
    return roots.flatMap { root -> paths.map(root::resolve) }.firstOrNull(Files::isExecutable)
        ?: throw LayoutEditorException("Paper Editor не установлен рядом с MagicPaper. Установите desktop-сборку с редактором и повторите запрос.")
}
