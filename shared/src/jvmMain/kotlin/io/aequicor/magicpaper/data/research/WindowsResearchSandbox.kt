package io.aequicor.magicpaper.data.research

import com.sun.jna.*
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Native Windows runner. No Codex, PowerShell policy, chmod or changes to source ACLs. */
internal object WindowsResearchSandbox : ResearchSandbox {
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    private val advapi by lazy { NativeLibrary.getInstance("advapi32") }
    private val user by lazy { NativeLibrary.getInstance("user32") }
    private fun bool(lib: NativeLibrary, name: String, vararg args: Any?) {
        check(lib.getFunction(name).invokeInt(args) != 0) { "$name: ${Native.getLastError()}" }
    }
    private fun close(handle: Pointer?) { if (handle != null) kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) }
    private fun free(pointer: Pointer?) { if (pointer != null) kernel.getFunction("LocalFree").invokePointer(arrayOf(pointer)) }
    private fun wide(text: String) = Memory((text.length + 1L) * 2).apply { setWideString(0, text) }

    internal fun quote(arg: String): String = buildString {
        append('"')
        var slashes = 0
        for (c in arg) {
            if (c == '\\') { slashes++; continue }
            repeat(if (c == '"') slashes * 2 + 1 else slashes) { append('\\') }
            slashes = 0; append(c)
        }
        repeat(slashes * 2) { append('\\') }; append('"')
    }

    internal fun unsafeLink(path: Path): Boolean {
        if (!System.getProperty("os.name").startsWith("Windows")) return false
        val attributes = kernel.getFunction("GetFileAttributesW").invokeInt(arrayOf(WString(path.toString())))
        check(attributes != -1) { "Не удалось проверить атрибуты $path" }
        if (attributes and 0x400 != 0) return true // includes junctions, not just Java symbolic links
        val handle = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString(path.toString()), 0x80, 7, null, 3, 0x02200000, null))
        check(handle != null && Pointer.nativeValue(handle) != -1L) { "Не удалось проверить ссылки $path" }
        try {
            Memory(24).use { info ->
                bool(kernel, "GetFileInformationByHandleEx", handle, 1, info, 24) // FileStandardInfo
                return info.getInt(16) > 1
            }
        } finally { close(handle) }
    }

    private class DirectoryGrant(val path: Path, val descriptor: Pointer, val oldDacl: Pointer?) : AutoCloseable {
        override fun close() {
            // Restore the pre-existing ACL, also removing inherited run-specific entries.
            try {
                val code = advapi.getFunction("SetNamedSecurityInfoW").invokeInt(arrayOf(WString(path.toString()), 1, 4, null, null, oldDacl, null))
                check(code == 0) { "Не удалось восстановить ACL каталога $path: $code" }
            } finally { free(descriptor) }
        }
    }

    private fun grantDirectory(path: Path, sid: Pointer): DirectoryGrant {
        val descriptor = PointerByReference(); val dacl = PointerByReference()
        val code = advapi.getFunction("GetNamedSecurityInfoW").invokeInt(arrayOf(WString(path.toString()), 1, 4, null, null, dacl, null, descriptor))
        check(code == 0 && dacl.value != null) { "Каталог проверок должен иметь явный ACL: $path ($code)" }
        val next = PointerByReference()
        try {
            Memory(48).use { access ->
                access.clear()
                access.setInt(0, 0x001F01FF) // FILE_ALL_ACCESS, confined to generated artifact directories
                access.setInt(4, 1) // GRANT_ACCESS
                access.setInt(8, 3) // OBJECT_INHERIT_ACE | CONTAINER_INHERIT_ACE
                access.setInt(28, 0) // TRUSTEE_IS_SID
                access.setInt(32, 5) // TRUSTEE_IS_WELL_KNOWN_GROUP
                access.setPointer(40, sid)
                check(advapi.getFunction("SetEntriesInAclW").invokeInt(arrayOf(1, access, dacl.value, next)) == 0) { "Не удалось подготовить ACL проверок" }
            }
            check(advapi.getFunction("SetNamedSecurityInfoW").invokeInt(arrayOf(WString(path.toString()), 1, 4, null, null, next.value, null)) == 0) {
                "Не удалось ограничить каталог проверок"
            }
            return DirectoryGrant(path, descriptor.value, dacl.value)
        } catch (e: Exception) { free(descriptor.value); throw e }
        finally { free(next.value) }
    }

    override fun launch(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy): Process {
        check(Native.POINTER_SIZE == 8) { "Защищённые проверки требуют 64-битную Windows" }
        check((listOf(policy.project) + policy.writable).all { java.nio.file.Files.getFileStore(it).type().equals("NTFS", true) }) { "Защита проекта и временных каталогов Windows требует NTFS" }
        val token = PointerByReference(); val restricted = PointerByReference(); val sid = PointerByReference()
        val outRead = PointerByReference(); val outWrite = PointerByReference()
        var input: Pointer? = null
        var job: Pointer? = null
        var desktop: Pointer? = null
        var child: Pointer? = null
        var thread: Pointer? = null
        val grants = mutableListOf<DirectoryGrant>()
        var handedOff = false
        val allocations = mutableListOf<Memory>()
        var attributes: Memory? = null
        var initializedAttributes = false
        try {
            bool(advapi, "OpenProcessToken", kernel.getFunction("GetCurrentProcess").invokePointer(emptyArray()), 0x000B, token)
            val uuid = UUID.randomUUID()
            val sidText = "S-1-5-21-${uuid.mostSignificantBits.toUInt()}-${(uuid.mostSignificantBits ushr 32).toUInt()}-${uuid.leastSignificantBits.toUInt()}-1031"
            bool(advapi, "ConvertStringSidToSidW", WString(sidText), sid)
            Memory(16).use { restrictedSid ->
                restrictedSid.clear(); restrictedSid.setPointer(0, sid.value)
                bool(advapi, "CreateRestrictedToken", token.value, 0x1 or 0x8, 0, null, 0, null, 1, restrictedSid, restricted)
            }
            // A unique restricting SID has no rights on user files; only artifact directories receive an ACE.
            policy.writable.forEach { grants += grantDirectory(it, sid.value) }
            val desktopName = "MagicPaperResearch-${UUID.randomUUID()}"
            val securityDescriptor = PointerByReference()
            // Owner access is retained; the second ACE is only for this restricted child.
            bool(advapi, "ConvertStringSecurityDescriptorToSecurityDescriptorW", WString("D:(A;;GA;;;OW)(A;;GA;;;$sidText)"), 1, securityDescriptor, null)
            val security = Memory(24).also { allocations += it }.apply {
                clear(); setInt(0, 24); setPointer(8, securityDescriptor.value); setInt(16, 1)
            }
            try {
                desktop = user.getFunction("CreateDesktopW").invokePointer(arrayOf(WString(desktopName), null, null, 0, 0x01FF, security))
                check(desktop != null) { "Не удалось создать изолированный desktop: ${Native.getLastError()}" }
            } finally { free(securityDescriptor.value); security.setPointer(8, null) }
            bool(kernel, "CreatePipe", outRead, outWrite, security, 0)
            bool(kernel, "SetHandleInformation", outRead.value, 1, 0)
            input = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString("NUL"), 0x80000000.toInt(), 3, security, 3, 0, null))
            check(input != null && Pointer.nativeValue(input) != -1L) { "Не удалось открыть stdin проверки" }
            job = kernel.getFunction("CreateJobObjectW").invokePointer(arrayOf(null, null))
            check(job != null) { "Не удалось создать Job Object" }
            Memory(144).use { limits ->
                limits.clear(); limits.setInt(16, 0x2000) // KILL_ON_JOB_CLOSE, no breakaway
                bool(kernel, "SetInformationJobObject", job, 9, limits, 144)
            }
            // Inherit only redirected stdio, never app handles or credentials.
            val attributeSize = Memory(8).also { allocations += it }.apply { clear() }
            kernel.getFunction("InitializeProcThreadAttributeList").invokeInt(arrayOf(null, 1, 0, attributeSize))
            attributes = Memory(attributeSize.getLong(0)).also { allocations += it }
            bool(kernel, "InitializeProcThreadAttributeList", attributes, 1, 0, attributeSize)
            initializedAttributes = true
            val handles = Memory(16).also { allocations += it }.apply { setPointer(0, input); setPointer(8, outWrite.value) }
            bool(kernel, "UpdateProcThreadAttribute", attributes, 0, 0x00020002L, handles, 16L, null, null)
            val desktopText = wide("winsta0\\$desktopName").also { allocations += it }
            val startup = Memory(112).also { allocations += it }.apply {
                clear(); setInt(0, 112); setPointer(16, desktopText); setInt(60, 0x100)
                setPointer(80, input); setPointer(88, outWrite.value); setPointer(96, outWrite.value); setPointer(104, attributes)
            }
            val info = Memory(24).also { allocations += it }.apply { clear() }
            val cmd = if (command.first().endsWith(".cmd", true) || command.first().endsWith(".bat", true)) {
                require(command.none { it.any { c -> c in "&|<>^%!\r\n" } }) { "Спецсимволы в аргументах batch-проверки недопустимы" }
                listOf(environment["SystemRoot"].orEmpty().ifBlank { "C:\\Windows" } + "\\System32\\cmd.exe", "/d", "/s", "/c")
            } else command
            val line = if (cmd !== command) cmd.joinToString(" ", transform = ::quote) + " \"" + command.joinToString(" ", transform = ::quote) + "\""
                else command.joinToString(" ", transform = ::quote)
            val commandLine = wide(line).also { allocations += it }
            val envText = environment.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (k, v) -> "$k=$v\u0000" }.joinToString("") + "\u0000"
            val envBlock = Memory(envText.length * 2L).also { allocations += it }.apply { write(0, envText.toCharArray(), 0, envText.length) }
            bool(advapi, "CreateProcessAsUserW", restricted.value, WString(cmd.first()), commandLine, null, null, 1,
                0x00080000 or 0x00000400 or 0x00000004 or 0x08000000, envBlock, WString(cwd.toString()), startup, info)
            child = info.getPointer(0); thread = info.getPointer(8)
            bool(kernel, "AssignProcessToJobObject", job, child)
            check(kernel.getFunction("ResumeThread").invokeInt(arrayOf(thread)) != -1) { "Не удалось запустить проверку" }
            close(outWrite.value); outWrite.value = null
            val handle = child!!; val group = job!!; val privateDesktop = desktop!!
            handedOff = true
            return WindowsProcess(handle, group, outRead.value, info.getInt(16).toLong() and 0xFFFFFFFFL) {
                grants.asReversed().forEach { it.close() }
                user.getFunction("CloseDesktop").invokeInt(arrayOf(privateDesktop))
            }
        } finally {
            if (initializedAttributes) kernel.getFunction("DeleteProcThreadAttributeList").invokeVoid(arrayOf(attributes))
            allocations.asReversed().forEach { it.close() }
            close(thread); close(input); close(outWrite.value); close(token.value); close(restricted.value); free(sid.value)
            if (!handedOff) {
                child?.let { kernel.getFunction("TerminateProcess").invokeInt(arrayOf(it, 1)) }
                close(child); close(job); close(outRead.value)
                desktop?.let { user.getFunction("CloseDesktop").invokeInt(arrayOf(it)) }
                grants.asReversed().forEach { it.close() }
            }
        }
    }

    private class WindowsProcess(private val handle: Pointer, private val job: Pointer, private val pipe: Pointer,
        private val childPid: Long, cleanup: () -> Unit) : Process() {
        private val completion = CompletableFuture<Int>()
        private val output = object : InputStream() {
            private var closed = false
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (length == 0) return 0
                if (closed) return -1
                Memory(length.toLong()).use { buffer ->
                    val n = IntByReference()
                    if (kernel.getFunction("ReadFile").invokeInt(arrayOf(pipe, buffer, length, n, null)) == 0 || n.value == 0) return -1
                    buffer.read(0, bytes, offset, n.value); return n.value
                }
            }
            override fun close() { if (!closed) { closed = true; close(pipe) } }
        }
        init {
            Thread({
                val outcome = runCatching {
                    check(kernel.getFunction("WaitForSingleObject").invokeInt(arrayOf(handle, -1)) == 0) { "Не удалось дождаться проверки" }
                    val code = IntByReference(255)
                    bool(kernel, "GetExitCodeProcess", handle, code)
                    bool(kernel, "TerminateJobObject", job, 0)
                    Memory(48).use { accounting ->
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                        do {
                            bool(kernel, "QueryInformationJobObject", job, 1, accounting, 48, null)
                            if (accounting.getInt(40) == 0) break
                            check(System.nanoTime() < deadline) { "Дочерние процессы проверки не завершились" }
                            Thread.sleep(20)
                        } while (true)
                    }
                    code.value
                }
                close(job); close(handle)
                val restored = runCatching(cleanup)
                val failure = outcome.exceptionOrNull() ?: restored.exceptionOrNull()
                if (failure == null) completion.complete(outcome.getOrThrow()) else completion.completeExceptionally(failure)
            }, "research-wait-$childPid").apply { isDaemon = true; start() }
        }
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun waitFor(): Int = completion.get()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try { completion.get(timeout, unit); true } catch (_: java.util.concurrent.TimeoutException) { false }
        override fun exitValue(): Int = completion.getNow(null) ?: throw IllegalThreadStateException("Process is alive")
        override fun isAlive() = !completion.isDone
        override fun pid() = childPid
        override fun toHandle(): ProcessHandle = ProcessHandle.of(pid()).orElseThrow { IllegalStateException("Process exited") }
        override fun destroy() { if (isAlive) kernel.getFunction("TerminateJobObject").invokeInt(arrayOf(job, 1)) }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
