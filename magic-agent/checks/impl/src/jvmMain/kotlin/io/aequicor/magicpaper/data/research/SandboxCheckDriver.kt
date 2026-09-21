package io.aequicor.magicpaper.data.research

import io.aequicor.magicpaper.data.checks.*
import io.aequicor.magicpaper.data.coding.WindowsExecutables
import io.aequicor.magicpaper.domain.checks.*
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.nio.file.*
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

/** Filesystem policy and native process adaptation. Admission and recovery are owned by DefaultCommandChecks. */
internal class SandboxCheckDriver(private val root: Path, private val timeoutMillis: Long,
    private val sandbox: () -> ResearchSandbox = { ResearchSandbox.current() }) : CheckProcessDriver {
    private val resources = ConcurrentHashMap<String, PreparedCommandCheck>()
    override suspend fun prepare(command: CheckCommand, receiptId: String,
        authority: io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder): PreparedCommandCheck =
        prepareOwned(command, receiptId, authority, null)

    override suspend fun needsGitMetadata(command: CheckCommand): Boolean = withContext(Dispatchers.IO) {
        val project = Paths.get(command.workspace).toRealPath()
        val probe = command.ref.scope.projectId == "sandbox-probe" && project == safeRoot().resolve("sandbox-probe")
        command.policy == CheckPolicy.PROTECTED_PROJECT && !probe && ResearchWorkspacePolicy.hasRepository(project)
    }

    override suspend fun prepareWithMetadata(command: CheckCommand, receiptId: String,
        authority: io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder, metadata: CheckGitMetadata?): PreparedCommandCheck =
        prepareOwned(command, receiptId, authority, metadata)

    private suspend fun prepareOwned(command: CheckCommand, receiptId: String,
        authority: io.aequicor.magicpaper.data.checks.CheckAuthorityRecorder, metadata: CheckGitMetadata?): PreparedCommandCheck {
        var acquired: PreparedCommandCheck? = null
        try { return withContext(Dispatchers.IO) {
        var scratch: Path? = null
        var native: PreparedCheckProcess? = null
        var nativeEntered = false
        try {
            val project = Paths.get(command.workspace).toRealPath()
            require(!Paths.get(command.subdirectory).isAbsolute)
            val cwd = project.resolve(command.subdirectory).normalize().toRealPath()
            require(cwd.startsWith(project) && Files.isDirectory(cwd))
            val ownedRoot = safeRoot()
            scratch = Files.createTempDirectory(ownedRoot, "run-${ResearchArtifactStore.key(command.ref.scope.sessionId)}-")
            val protected = command.policy != CheckPolicy.MANAGED_WORKTREE
            val metadataRead = command.policy in setOf(CheckPolicy.METADATA_READ_ONLY, CheckPolicy.GIT_READ_ONLY)
            val artifacts = ResearchArtifactStore(ownedRoot)
            val before = if (protected) artifacts.read(project) else null
            val isProbe = command.ref.scope.projectId == "sandbox-probe" && project == ownedRoot.resolve("sandbox-probe")
            val policy = when {
                metadataRead -> ResearchWorkspacePolicy.metadataOnly(project, scratch)
                isProbe -> ResearchWorkspacePolicy(project, listOf(project.resolve("build"), scratch), listOf(project.resolve(".git")), emptyList())
                protected -> ResearchWorkspacePolicy.inspect(project, scratch, checkNotNull(before).files,
                    metadata?.let { saved ->
                        require(saved.parent == command.ref && saved.protectedResource == command.resource &&
                            saved.outputs.keys == CheckGitMetadataQuery.entries.toSet()) { "Git metadata identity changed" }
                        val store = BinaryCheckOutputs(ownedRoot.resolve("outputs"))
                        ResearchGitMetadata.decode(project, saved.outputs.mapValues { (_, ref) -> store.read(ref) })
                    })
                else -> null
            }
            val environment = (if (protected) environment(scratch) else managedEnvironment()) + command.environment
            val effectiveEnvironment = if (!metadataRead && command.arguments.first() != "git") environment else environment.toMutableMap().apply {
                keys.removeIf { it.startsWith("GIT_", ignoreCase = true) }
                put("GIT_OPTIONAL_LOCKS", "0"); put("GIT_CONFIG_NOSYSTEM", "1"); put("GIT_TERMINAL_PROMPT", "0")
                put("GIT_CONFIG_GLOBAL", if (System.getProperty("os.name").startsWith("Windows")) "NUL" else "/dev/null")
                putAll(command.environment)
            }
            val executable = resolveExecutable(command.arguments.first(), cwd, effectiveEnvironment)
            val binary = if (command.outputMode == CheckOutputMode.BINARY_STDOUT) Files.createFile(scratch.resolve("stdout.bin")) else null
            currentCoroutineContext().ensureActive()
            nativeEntered = true
            val recorder: CheckAuthorityRecorder = { id, bytes -> runBlocking { authority.record(id, bytes) } }
            val arguments = listOf(executable) + command.arguments.drop(1)
            native = if (binary == null) sandbox().prepare(arguments, cwd, effectiveEnvironment, policy,
                receiptId, ownedRoot.resolve("owned"), recorder)
            else sandbox().prepareBinary(arguments, cwd, effectiveEnvironment, policy,
                receiptId, ownedRoot.resolve("owned"), binary, recorder)
            CommandResource(native, scratch, policy, artifacts, before, timeoutMillis, binary, metadataRead,
                BinaryCheckOutputs(ownedRoot.resolve("outputs"))) { resources.remove(receiptId) }.also {
                resources[receiptId] = it
                acquired = it
            }
        } catch (failure: Throwable) {
            if (native != null) {
                try { withContext(NonCancellable) { native.stopAndConfirm() } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            // Only native's explicit pre-effect refusal is known. Unknown native preparation retains its receipts.
            if (!nativeEntered || failure is NativeCheckUnavailable) {
                scratch?.let { path ->
                    try { deleteScratch(path) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup); throw failure }
                }
                if (failure is CancellationException) throw CheckPreparationCancelled(failure)
                throw CheckNotDispatched(if (failure is NativeCheckUnavailable) failure.message ?: "Проверка недоступна" else
                    "Команда или рабочая папка проверки недоступна", cause = failure)
            }
            throw failure
        }
        } } catch (failure: Throwable) {
            // Prompt cancellation on a dispatcher handoff happens outside the inner withContext body.
            // Retain the resource before that boundary and always attempt its cleanup here.
            acquired?.let { resource ->
                try { withContext(NonCancellable) { resource.stopAndConfirm(); resource.discard() } }
                catch (cleanup: Throwable) { if (cleanup !== failure) failure.addSuppressed(cleanup) }
            }
            throw failure
        }
    }

    override suspend fun readOutput(output: CheckOutputRef): ByteArray = withContext(Dispatchers.IO) {
        BinaryCheckOutputs(safeRoot().resolve("outputs")).read(output)
    }

    override suspend fun cleanup() = withContext(NonCancellable + Dispatchers.IO) {
        val failures = mutableListOf<Throwable>()
        resources.values.toList().forEach { resource ->
            try { resource.stopAndConfirm(); resource.discard() } catch (failure: Throwable) { failures += failure }
        }
        val primary = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull()
        if (primary != null) {
            failures.filter { it !== primary }.forEach(primary::addSuppressed)
            throw primary
        }
    }

    override suspend fun probeWorkspace(): String = withContext(Dispatchers.IO) {
        val fixture = safeRoot().resolve("sandbox-probe")
        require(!Files.isSymbolicLink(fixture))
        Files.createDirectories(fixture)
        require(!WindowsResearchSandbox.unsafeLink(fixture))
        fixture.toRealPath().toString()
    }
    override suspend fun createProbe(ref: CheckRef): CheckProbe = withContext(Dispatchers.IO) {
        val fixture = Paths.get(probeWorkspace())
        // The caller has replayed and fenced this fixed workspace before replacing disposable fixture files.
        Files.newDirectoryStream(fixture).use { entries -> entries.forEach { path ->
            require(!Files.isSymbolicLink(path) && !WindowsResearchSandbox.unsafeLink(path))
            check(path.toFile().deleteRecursively())
        } }
        val source = Files.writeString(fixture.resolve("source.txt"), "protected")
        val git = Files.createDirectories(fixture.resolve(".git"))
        Files.writeString(git.resolve("index"), "index")
        // This probe uses a deliberately non-repository .git fixture; policy is constructed below without Git introspection.
        val output = Files.createDirectories(fixture.resolve("build"))
        val windows = System.getProperty("os.name").startsWith("Windows")
        val script = if (windows) listOf(
            Paths.get(System.getenv("SystemRoot") ?: "C:\\Windows", "System32", "WindowsPowerShell", "v1.0", "powershell.exe").toString(),
            "-NoProfile", "-NonInteractive", "-Command",
            "\$ErrorActionPreference='SilentlyContinue'; Set-Content source.txt changed; Remove-Item source.txt; Rename-Item source.txt moved.txt; " +
                "Set-Content forbidden.txt forbidden; Set-Content .git/index changed; Set-Content build/ok.txt allowed; Write-Output PROBE_DONE")
        else listOf("/bin/sh", "-c", "printf changed > source.txt; rm -f source.txt; mv source.txt moved.txt; printf forbidden > forbidden.txt; " +
            "printf changed > .git/index; printf allowed > build/ok.txt; printf PROBE_DONE")
        object : CheckProbe {
            override val command = CheckCommand(ref, fixture.toString(), script)
            override suspend fun verify(result: CheckResult) {
                check(result.exitCode == 0 && result.blockedReason == null && "PROBE_DONE" in result.output &&
                    Files.readString(source) == "protected" && Files.readString(git.resolve("index")) == "index" &&
                    !Files.exists(fixture.resolve("forbidden.txt")) && !Files.exists(fixture.resolve("moved.txt")) &&
                    Files.readString(output.resolve("ok.txt")).trim() == "allowed")
            }
        }
    }

    private class CommandResource(private val process: PreparedCheckProcess, private val scratch: Path,
        private val policy: ResearchWorkspacePolicy?, private val artifacts: ResearchArtifactStore,
        private val before: ResearchArtifactStore.Snapshot?, private val timeoutMillis: Long,
        private val binary: Path?, private val metadataRead: Boolean, private val binaryOutputs: BinaryCheckOutputs,
        private val onDiscard: () -> Unit) : PreparedCommandCheck {
        override val receipt get() = process.receipt
        private val lock = Any()
        private val text = StringBuilder()
        private val read = CompletableFuture<Unit>()
        private var released = false
        private var cleanup: CheckCleanup? = null
        private var attested: String? = null
        private val reader = Thread({
            try {
                process.inputStream.reader(Charsets.UTF_8).use { input ->
                    val buffer = CharArray(4096)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        synchronized(lock) {
                            text.append(buffer, 0, count)
                            if (text.length > MAX_OUTPUT) text.delete(0, text.length - MAX_OUTPUT)
                        }
                    }
                }
                read.complete(Unit)
            } catch (failure: Throwable) { read.completeExceptionally(failure) }
        }, "command-check-output").apply { isDaemon = true; start() }

        override suspend fun release() = withContext(Dispatchers.IO) {
            check(!released)
            process.release()
            released = true
        }
        override suspend fun awaitResult(progress: (String) -> Unit): CheckResult = withContext(Dispatchers.IO) {
            check(released)
            var last = ""
            val completed = withTimeoutOrNull(timeoutMillis) {
                while (process.isAlive) {
                    if (binary != null && Files.size(binary) > BinaryCheckOutputs.LIMIT) throw CheckOutputLimitExceeded()
                    val current = synchronized(lock) { text.toString() }
                    if (current != last) { progress(current); last = current }
                    delay(50)
                }
                true
            } ?: false
            if (!completed) throw CheckTimedOut()
            // Native completion includes containment cleanup; a lingering output pipe is an unresolved resource.
            read.get(2, TimeUnit.SECONDS)
            val output = synchronized(lock) { text.toString() }
            progress(output)
            val code = process.exitValue()
            // A descendant may still hold stdout after its leader exits. Freeze the artifact only
            // after the same native owner proves the entire group and its authority stopped.
            val binaryOutput = binary?.let {
                stopAndConfirm()
                binaryOutputs.save(receipt.id, it)
            }
            val note = if (code != 0 && policy?.withheld?.isNotEmpty() == true)
                "\nЗапись не предоставлена: ${policy.withheld.joinToString("; ")}. Исходники и нестандартные пути результатов защищены." else ""
            return@withContext CheckResult(output + note, code, binaryOutput = binaryOutput)
        }
        override suspend fun stopAndConfirm(): CheckCleanup = withContext(Dispatchers.IO) {
            cleanup ?: process.stopAndConfirm().let { proof ->
                read.get(2, TimeUnit.SECONDS)
                CheckCleanup(proof.groupProof, proof.authorityProof).also { cleanup = it }
            }
        }
        override suspend fun attest(): String = withContext(Dispatchers.IO) {
            checkNotNull(cleanup)
            attested ?: (if (policy == null || metadataRead) ResearchArtifactStore.digest("${if (metadataRead) "metadata" else "managed"}:${receipt.id}:${cleanup}")
                else artifacts.commit(checkNotNull(before), policy.writable.filter { it != scratch.toRealPath() }, receipt.id))
                .also { attested = it }
        }
        override suspend fun discard() = withContext(Dispatchers.IO) {
            checkNotNull(cleanup)
            process.inputStream.close()
            reader.join(2000)
            check(!reader.isAlive) { "Check output reader did not stop" }
            deleteScratch(scratch)
            onDiscard()
        }
    }

    private fun safeRoot(): Path {
        val normalized = root.toAbsolutePath().normalize()
        val parent = Files.createDirectories(normalized.parent).toRealPath()
        val path = parent.resolve(normalized.fileName)
        require(!Files.isSymbolicLink(path) && (!Files.exists(path, LinkOption.NOFOLLOW_LINKS) || !WindowsResearchSandbox.unsafeLink(path)))
        Files.createDirectories(path)
        require(path.toRealPath() == path)
        return path
    }
    private fun managedEnvironment(): Map<String, String> = System.getenv().toMutableMap().apply { useForkLauncher() }
    private fun environment(scratch: Path): Map<String, String> = buildMap {
        val allowed = setOf("PATH", "HOME", "USERPROFILE", "SYSTEMROOT", "WINDIR", "COMSPEC", "PATHEXT", "PROGRAMFILES", "PROGRAMFILES(X86)",
            "PROGRAMW6432", "JAVA_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT", "SDKROOT", "DEVELOPER_DIR", "RUSTUP_HOME", "LANG", "LC_ALL")
        System.getenv().filterKeys { it.uppercase() in allowed }.forEach { (key, value) -> put(key, value) }
        fun directory(name: String) = Files.createDirectories(scratch.resolve(name)).toString()
        val temp = directory("tmp")
        put("TMPDIR", temp); put("TMP", temp); put("TEMP", temp)
        put("XDG_CACHE_HOME", directory("cache")); put("GRADLE_USER_HOME", directory("gradle"))
        put("NPM_CONFIG_CACHE", directory("npm")); put("PIP_CACHE_DIR", directory("pip")); put("CARGO_HOME", directory("cargo"))
        put("PYTHONPYCACHEPREFIX", directory("pycache")); put("DOTNET_CLI_HOME", directory("dotnet"))
        put("NUGET_PACKAGES", directory("nuget")); put("GIT_OPTIONAL_LOCKS", "0"); put("GIT_TERMINAL_PROMPT", "0")
        put("CI", "true")
        useForkLauncher()
    }
    private fun MutableMap<String, String>.useForkLauncher() {
        if (System.getProperty("os.name").startsWith("Mac"))
            put("JAVA_TOOL_OPTIONS", listOfNotNull(get("JAVA_TOOL_OPTIONS"), "-Djdk.lang.Process.launchMechanism=fork").joinToString(" "))
    }
    internal fun resolveExecutable(name: String, cwd: Path, env: Map<String, String>): String {
        val windows = WindowsExecutables.isWindows()
        val extensions = WindowsExecutables.extensions(env.entries.firstOrNull { it.key.equals("PATHEXT", true) }?.value ?: DEFAULT_PATHEXT)
        val suffixes = WindowsExecutables.suffixes(name, extensions)
        fun candidates(base: Path) = suffixes.asSequence().map { Paths.get(base.toString() + it) }
        if (name.contains('/') || name.contains('\\')) {
            val base = cwd.resolve(name).normalize()
            val file = candidates(base).firstOrNull { Files.isRegularFile(it) } ?: return base.toRealPath().toString()
            require(WindowsExecutables.isLaunchable(file.toFile(), extensions)) {
                "$name не является исполнимым файлом Windows; нужен аналог с расширением, например $name.bat"
            }
            return file.toRealPath().toString()
        }
        val paths = env.entries.firstOrNull { it.key.equals("PATH", true) }?.value.orEmpty().split(File.pathSeparator)
        return paths.asSequence().filter(String::isNotBlank).flatMap { candidates(Paths.get(it, name)) }
            .firstOrNull { Files.isRegularFile(it) && (windows || Files.isExecutable(it)) && WindowsExecutables.isLaunchable(it.toFile(), extensions) }
            ?.toRealPath()?.toString() ?: throw NativeCheckUnavailable("Команда проверки не найдена")
    }
    companion object {
        const val MAX_OUTPUT = 64_000
        const val DEFAULT_PATHEXT = ".EXE;.CMD;.BAT"
        private fun deleteScratch(path: Path) {
            require(!Files.isSymbolicLink(path) && !WindowsResearchSandbox.unsafeLink(path))
            check(path.toFile().deleteRecursively()) { "Check scratch cleanup failed" }
        }
    }
}
