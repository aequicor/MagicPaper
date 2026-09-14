package io.aequicor.magicpaper.data.planning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Hashes the index and actual tracked/untracked bytes. A nested repository (submodule pointer or untracked clone)
 * contributes only its recorded identity, exactly like Git itself. Never stages files or runs Git filters.
 */
internal suspend fun verificationSnapshot(path: String): String = withContext(Dispatchers.IO) {
    val root = Path.of(path).toRealPath()
    require(Files.isDirectory(root)) { "Нет папки для проверяемого снимка" }
    fun git(vararg args: String): ByteArray {
        val builder = ProcessBuilder(listOf("git", "-c", "core.fsmonitor=false", "-C", root.toString()) + args)
        builder.environment()["GIT_OPTIONAL_LOCKS"] = "0"
        builder.environment().remove("GIT_INDEX_FILE")
        val process = builder.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.use { it.readNBytes(16 * 1024 * 1024 + 1) } }
        val errors = CompletableFuture.supplyAsync { process.errorStream.use { it.readNBytes(64 * 1024) } }
        return try {
            require(process.waitFor(20, TimeUnit.SECONDS)) { "Истёк срок чтения Git-снимка" }
            require(process.exitValue() == 0) { "Git-снимок недоступен" }
            output.get(5, TimeUnit.SECONDS).also { require(it.size <= 16 * 1024 * 1024) { "Слишком большой список файлов" } }
        } finally {
            process.destroyForcibly()
            output.cancel(true); errors.cancel(true)
        }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(bytes: ByteArray) { digest.update(bytes.size.toString().toByteArray()); digest.update(0); digest.update(bytes) }
    fun field(text: String) = field(text.toByteArray())
    field("magicpaper-verification-v1")
    val repository = Files.exists(root.resolve(".git"))
    // A submodule contributes the commit recorded in this index, never the bytes of its own repository.
    val gitlinks = mutableMapOf<String, MutableList<String>>()
    val names = if (repository) {
        require(Path.of(git("rev-parse", "--show-toplevel").decodeToString().trim()).toRealPath() == root) { "Нужен корень репозитория" }
        val index = git("ls-files", "--stage", "-z")
        field(index)
        // Each -z record is "<mode> <object> <stage>\t<path>" with an unquoted raw UTF-8 path.
        index.decodeToString().split('\u0000').forEach { entry ->
            val tab = entry.indexOf('\t')
            val record = if (tab < 0) emptyList() else entry.substring(0, tab).split(' ')
            if (record.size == 3 && record[0] == "160000") gitlinks.getOrPut(entry.substring(tab + 1)) { mutableListOf() } += record[1]
        }
        git("ls-files", "--cached", "--others", "--exclude-standard", "-z").decodeToString()
            .split('\u0000').filter { it.isNotEmpty() }.distinct().sorted()
    } else Files.walk(root).use { stream -> stream.filter { !Files.isDirectory(it, NOFOLLOW_LINKS) }
        .map { root.relativize(it).toString() }.sorted().toList() }
    require(names.size <= 100_000) { "Слишком много файлов для снимка" }
    for (name in names) {
        val file = root.resolve(name).normalize()
        require(file.startsWith(root)) { "Файл вне проверяемого проекта" }
        // Do not follow an intermediate symlink outside the workspace.
        var parent = file.parent
        while (parent != root) { require(!Files.isSymbolicLink(parent)) { "Ссылка в пути проверяемого файла" }; parent = parent.parent }
        field(name)
        val pointer = gitlinks[name]
        when {
            // The local checkout may be absent (an uninitialized worktree) or hold foreign history; the index above already
            // carries the pointer, so a staged "new commits" change still moves the snapshot.
            pointer != null -> { field("gitlink"); pointer.forEach { field(it) } }
            Files.isSymbolicLink(file) -> { field("symlink"); field(Files.readSymbolicLink(file).toString()) }
            !Files.exists(file, NOFOLLOW_LINKS) -> field("deleted")
            Files.isRegularFile(file, NOFOLLOW_LINKS) -> {
                field(if (Files.isExecutable(file)) "executable" else "file")
                val hash = MessageDigest.getInstance("SHA-256")
                Files.newInputStream(file).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
                }
                field(hash.digest())
            }
            // Git enumerates an untracked nested repository as one entry and never reads its bytes; delivery ignores them too.
            Files.isDirectory(file, NOFOLLOW_LINKS) && Files.exists(file.resolve(".git"), NOFOLLOW_LINKS) -> field("nested-repository")
            else -> error("Неподдерживаемый файл снимка: $name")
        }
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
