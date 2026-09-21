package io.aequicor.magicpaper.data.planning

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import io.aequicor.magicpaper.domain.checks.CheckGitReadQuery

/**
 * Hashes the index and actual tracked/untracked bytes, including gitlinks. An initialized submodule is hashed
 * recursively; a nested clone that Git itself leaves untracked contributes only one entry. Never stages files or
 * runs Git filters.
 */
internal suspend fun verificationSnapshot(path: String, commands: OwnedGitCommands): String = withContext(Dispatchers.IO) {
    snapshot(Path.of(path).toRealPath(), depth = 0, commands).joinToString("") { "%02x".format(it) }
}

private suspend fun snapshot(root: Path, depth: Int, commands: OwnedGitCommands): ByteArray {
    require(depth <= 32) { "Слишком большая вложенность подмодулей для снимка" }
    require(Files.isDirectory(root)) { "Нет папки для проверяемого снимка" }
    suspend fun git(query: CheckGitReadQuery): ByteArray = commands.read(root.toFile(), query)
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(bytes: ByteArray) { digest.update(bytes.size.toString().toByteArray()); digest.update(0); digest.update(bytes) }
    fun field(text: String) = field(text.toByteArray())
    field("magicpaper-verification-v1")
    val repository = Files.exists(root.resolve(".git"))
    var gitlinks = emptySet<String>()
    val names = if (repository) {
        require(Path.of(git(CheckGitReadQuery.ROOT).decodeToString().trim()).toRealPath() == root) { "Нужен корень репозитория" }
        val index = git(CheckGitReadQuery.INDEX)
        field(index)
        // Each -z record is "<mode> <object> <stage>\t<path>" with an unquoted raw UTF-8 path.
        gitlinks = index.decodeToString().split('\u0000').filter { it.startsWith("160000 ") }
            .map { it.substringAfter('\t') }.toSet()
        // The parent index identifies the expected commit; the submodule's HEAD may differ
        // even when its index and file bytes are identical (e.g. an empty commit).
        if (depth > 0) field(git(CheckGitReadQuery.HEAD))
        git(CheckGitReadQuery.FILES).decodeToString()
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
        when {
            Files.isSymbolicLink(file) -> { field("symlink"); field(Files.readSymbolicLink(file).toString()) }
            !Files.exists(file, NOFOLLOW_LINKS) -> field("deleted")
            name in gitlinks && Files.isDirectory(file, NOFOLLOW_LINKS) -> {
                field("gitlink")
                // Worktree creation leaves an uninitialized gitlink as an empty directory.
                // Hash populated copies recursively so submodule edits cannot evade verification.
                field(snapshot(file.toRealPath(), depth + 1, commands))
            }
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
    return digest.digest()
}
