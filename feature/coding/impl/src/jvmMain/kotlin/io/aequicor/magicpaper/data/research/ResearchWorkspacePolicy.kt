package io.aequicor.magicpaper.data.research

import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import java.security.MessageDigest

/** A directory name alone is never evidence that its existing files may be overwritten. */
internal data class ResearchWorkspacePolicy(
    val project: Path,
    val writable: List<Path>,
    val protected: List<Path>,
    val withheld: List<String>,
) {
    companion object {
        private val manifests = mapOf(
            "build.gradle" to listOf("build", ".gradle"), "build.gradle.kts" to listOf("build", ".gradle"),
            "settings.gradle" to listOf(".gradle"), "settings.gradle.kts" to listOf(".gradle"),
            "pom.xml" to listOf("target"), "Cargo.toml" to listOf("target"),
            "package.json" to listOf("build", "dist", ".next", "coverage", ".cache"),
            "pyproject.toml" to listOf(".pytest_cache", ".mypy_cache", ".ruff_cache"),
            "pytest.ini" to listOf(".pytest_cache"),
        )
        private val skip = setOf(".git", ".hg", ".svn", "node_modules", ".venv", "venv", "build", "dist", "target", ".gradle", ".next", ".cache", "coverage")
        private val sourceExtensions = setOf("kt", "kts", "java", "swift", "c", "cpp", "h", "hpp", "cs", "rs", "go", "py", "js", "jsx", "ts", "tsx", "vue", "svelte", "sh", "ps1", "bat", "cmd", "html", "css", "scss", "json", "toml", "yaml", "yml")

        fun inspect(projectPath: Path, scratch: Path, artifacts: Map<Path, String> = emptyMap()): ResearchWorkspacePolicy {
            val project = projectPath.toRealPath()
            require(Files.isDirectory(project)) { "Папка проекта недоступна" }
            val realScratch = scratch.toRealPath()
            require(!realScratch.startsWith(project) && !project.startsWith(realScratch)) { "Служебная папка исследования должна быть вне проекта и его родительских каталогов" }
            val candidates = linkedSetOf<Path>()
            var count = 0
            Files.walkFileTree(project, emptySet(), 16, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(++count <= 50_000) { "Проект слишком велик для проверки служебных каталогов" }
                    if (dir != project && dir.fileName.toString() in skip) return FileVisitResult.SKIP_SUBTREE
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) manifests[file.fileName.toString()]?.forEach { candidates.add(file.parent.resolve(it)) }
                    return FileVisitResult.CONTINUE
                }
            })
            val protected = mutableListOf(project.resolve(".git"))
            val tracked = linkedSetOf<Path>()
            // A selected project may itself be a subdirectory of a repository or a worktree.
            if (generateSequence(project) { it.parent }.any { Files.exists(it.resolve(".git"), LinkOption.NOFOLLOW_LINKS) }) {
                fun git(vararg args: String): String {
                    val process = ProcessBuilder(listOf("git", "--no-pager", "-c", "core.fsmonitor=false") + args)
                        .directory(project.toFile()).redirectErrorStream(true).apply {
                            environment().keys.removeIf { it.startsWith("GIT_") }
                            environment()["GIT_OPTIONAL_LOCKS"] = "0"
                            environment()["GIT_CONFIG_NOSYSTEM"] = "1"
                            environment()["GIT_CONFIG_GLOBAL"] = if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null"
                        }.start()
                    val bytes = process.inputStream.readNBytes(16_000_001)
                    check(bytes.size <= 16_000_000 && process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0) {
                        process.destroyForcibly(); "Не удалось безопасно прочитать Git для защиты файлов"
                    }
                    return bytes.toString(Charsets.UTF_8)
                }
                git("ls-files", "-z", "--cached").split('\u0000').filter(String::isNotBlank)
                    .forEach { tracked.add(project.resolve(it).normalize()) }
                for (option in listOf("--absolute-git-dir", "--git-common-dir")) {
                    val path = Paths.get(git("rev-parse", option).trim())
                    protected.add((if (path.isAbsolute) path else project.resolve(path)).toRealPath())
                }
            }
            val withheld = mutableListOf<String>()
            val writable = candidates.filter { candidate ->
                val reason = when {
                    protected.any { candidate.startsWith(it) || it.startsWith(candidate) } -> "Git"
                    tracked.any { it.startsWith(candidate) } -> "файлы проекта"
                    !safeAncestors(project, candidate) -> "символьная ссылка"
                    else -> existingProblem(candidate, artifacts)
                }
                if (reason != null) withheld += "${project.relativize(candidate)}: $reason"
                reason == null
            }
            writable.forEach { Files.createDirectories(it) }
            // Check again after creation; never follow a pre-existing link or grant a parent directory.
            require(writable.all { safeAncestors(project, it) && it.toRealPath() == it.toAbsolutePath().normalize() }) { "Путь служебного каталога изменился" }
            return ResearchWorkspacePolicy(project, writable + listOf(scratch.toRealPath()), protected.distinct(), withheld)
        }

        private fun safeAncestors(root: Path, path: Path): Boolean {
            var cursor: Path? = path
            while (cursor != null && cursor != root) {
                if (Files.isSymbolicLink(cursor) || (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && WindowsResearchSandbox.unsafeLink(cursor))) return false
                cursor = cursor.parent
            }
            return cursor == root
        }

        private fun existingProblem(path: Path, artifacts: Map<Path, String>): String? {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return "это файл"
            var problem: String? = null
            var count = 0
            Files.walkFileTree(path, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (WindowsResearchSandbox.unsafeLink(dir)) { problem = "reparse point"; return FileVisitResult.TERMINATE }
                    return FileVisitResult.CONTINUE
                }
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    problem = when {
                        ++count > 100_000 -> "слишком много существующих файлов"
                        attrs.isSymbolicLink || attrs.isOther || WindowsResearchSandbox.unsafeLink(file) -> "ссылка или специальный файл"
                        runCatching { (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() > 1 }.getOrDefault(false) -> "жёсткая ссылка"
                        artifacts[file] != digest(file) -> if (file.fileName.toString().substringAfterLast('.', "").lowercase() in sourceExtensions)
                            "исходники или конфигурация" else "существующий пользовательский файл"
                        else -> null
                    }
                    return if (problem != null) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
                }
            })
            return problem
        }

        /** Only exact, unchanged outputs of our previous sandbox launch can be reused. */
        fun snapshot(directories: List<Path>): Map<Path, String> = buildMap {
            directories.forEach { dir ->
                Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (attrs.isRegularFile && !WindowsResearchSandbox.unsafeLink(file) &&
                            runCatching { (Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS) as Number).toInt() == 1 }.getOrDefault(true))
                            put(file, digest(file))
                        return FileVisitResult.CONTINUE
                    }
                })
            }
        }

        private fun digest(path: Path): String {
            val hash = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { stream ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val n = stream.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
            }
            return hash.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
