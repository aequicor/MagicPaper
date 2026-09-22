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
import io.aequicor.magicpaper.domain.checks.CheckProcessReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Native Windows runner. No Codex, PowerShell policy, chmod or changes to source ACLs. */
internal object WindowsResearchSandbox : ResearchSandbox {
    /**
     * A token from `CreateRestrictedToken` with WRITE_RESTRICTED yields a child that dies with
     * STATUS_DLL_INIT_FAILED before its first instruction here — reproduced with the run SID, the logon
     * SID, Everyone and all of the caller's own groups in the restricting list, and with the run SID
     * granted on both the window station and a dedicated desktop; the same preparation starts the child
     * with an unrestricted token. So the run SID confines nothing today. Only exact read-only argument
     * allowlists may run in that state; the sandbox probe keeps arbitrary protected commands refused
     * rather than silently unprotected.
     */
    override val confinesWrites: Boolean get() = false
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    private val advapi by lazy { NativeLibrary.getInstance("advapi32") }
    private val user by lazy { NativeLibrary.getInstance("user32") }
    private fun bool(lib: NativeLibrary, name: String, vararg args: Any?) {
        check(lib.getFunction(name).invokeInt(args) != 0) { "$name: ${Native.getLastError()}" }
    }
    private fun close(handle: Pointer?) { if (handle != null) bool(kernel, "CloseHandle", handle) }
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

    override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess =
        prepareOwned(command, cwd, environment, policy, receiptId, receiptDirectory, authorityRecorder, null)

    override fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, standardOutput: Path,
        authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess =
        prepareOwned(command, cwd, environment, policy, receiptId, receiptDirectory, authorityRecorder, standardOutput)

    private fun prepareOwned(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder, standardOutput: Path?): PreparedCheckProcess {
        if (Native.POINTER_SIZE != 8) throw NativeCheckUnavailable("Проверки требуют 64-битную Windows")
        if (policy != null && !(listOf(policy.project) + policy.writable).all { java.nio.file.Files.getFileStore(it).type().equals("NTFS", true) }) throw NativeCheckUnavailable("Защита проекта и временных каталогов Windows требует NTFS")
        val token = PointerByReference(); val restricted = PointerByReference(); val sid = PointerByReference()
        val outRead = PointerByReference(); val outWrite = PointerByReference()
        var input: Pointer? = null
        var binaryOutput: Pointer? = null
        var job: Pointer? = null
        var desktop: Pointer? = null
        val desktopName = "MagicPaperResearch-${UUID.randomUUID()}"
        var child: Pointer? = null
        var thread: Pointer? = null
        var authority: WindowsCheckAuthority? = null
        var primary: Throwable? = null
        var handedOff = false
        var handedProcess: WindowsProcess? = null
        val allocations = mutableListOf<Memory>()
        var attributes: Memory? = null
        var initializedAttributes = false
        try {
            if (policy != null) {
            bool(advapi, "OpenProcessToken", kernel.getFunction("GetCurrentProcess").invokePointer(emptyArray()), 0x000B, token)
            val uuid = UUID.randomUUID()
            val sidText = "S-1-5-21-${uuid.mostSignificantBits.toUInt()}-${(uuid.mostSignificantBits ushr 32).toUInt()}-${uuid.leastSignificantBits.toUInt()}-1031"
            bool(advapi, "ConvertStringSidToSidW", WString(sidText), sid)
            Memory(16).use { restrictedSid ->
                restrictedSid.clear(); restrictedSid.setPointer(0, sid.value)
                bool(advapi, "CreateRestrictedToken", token.value, 0x1 or 0x8, 0, null, 0, null, 1, restrictedSid, restricted)
            }
            // A unique restricting SID has no rights on user files; only artifact directories receive an ACE.
            authority = WindowsCheckAuthority.capture(policy.writable, sidText, receiptId, authorityRecorder)
            authority.grant(sidText)
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
            } // managed worktrees require a job, but no restricted token or ACL mutation
            val security = Memory(24).also { allocations += it }.apply { clear(); setInt(0, 24); setInt(16, 1) }
            bool(kernel, "CreatePipe", outRead, outWrite, security, 0)
            bool(kernel, "SetHandleInformation", outRead.value, 1, 0)
            input = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString("NUL"), 0x80000000.toInt(), 3, security, 3, 0, null))
            check(input != null && Pointer.nativeValue(input) != -1L) { "Не удалось открыть stdin проверки" }
            if (standardOutput != null) {
                binaryOutput = kernel.getFunction("CreateFileW").invokePointer(arrayOf(WString(standardOutput.toString()),
                    0x40000000, 1, security, 5, 0x80, null)) // GENERIC_WRITE, share-read, TRUNCATE_EXISTING
                check(binaryOutput != null && Pointer.nativeValue(binaryOutput) != -1L) { "Не удалось открыть stdout проверки" }
            }
            val jobName = "MagicPaperCheck-${UUID.randomUUID()}"
            job = kernel.getFunction("CreateJobObjectW").invokePointer(arrayOf(null, WString(jobName)))
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
            val handleBytes = if (binaryOutput == null) 16L else 24L
            val handles = Memory(handleBytes).also { allocations += it }.apply {
                setPointer(0, input); setPointer(8, outWrite.value)
                if (binaryOutput != null) setPointer(16, binaryOutput)
            }
            bool(kernel, "UpdateProcThreadAttribute", attributes, 0, 0x00020002L, handles, handleBytes, null, null)
            val desktopText = if (desktop == null) null else wide("winsta0\\$desktopName").also { allocations += it }
            val startup = Memory(112).also { allocations += it }.apply {
                clear(); setInt(0, 112); setPointer(16, desktopText); setInt(60, 0x100)
                setPointer(80, input); setPointer(88, binaryOutput ?: outWrite.value); setPointer(96, outWrite.value); setPointer(104, attributes)
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
            val flags = 0x00080000 or 0x00000400 or 0x00000004 or 0x08000000
            if (policy == null) bool(kernel, "CreateProcessW", WString(cmd.first()), commandLine, null, null, 1,
                flags, envBlock, WString(cwd.toString()), startup, info)
            else bool(advapi, "CreateProcessAsUserW", restricted.value, WString(cmd.first()), commandLine, null, null, 1,
                flags, envBlock, WString(cwd.toString()), startup, info)
            child = info.getPointer(0); thread = info.getPointer(8)
            bool(kernel, "AssignProcessToJobObject", job, child)
            close(outWrite.value); outWrite.value = null
            close(binaryOutput); binaryOutput = null
            val pid = info.getInt(16).toLong() and 0xFFFFFFFFL
            val receipt = CheckProcessReceipt(receiptId, "windows-job", pid, authority?.receiptId)
            val files = NativeCheckReceiptFiles(receiptDirectory, receiptId)
            val startedAt = Memory(8).use { created ->
                Memory(24).use { times -> bool(kernel, "GetProcessTimes", child, created, times, times.share(8), times.share(16)) }
                created.getLong(0)
            }
            files.record(NativeCheckIdentity(receipt, startedAt, jobName))
            val process = WindowsProcess(child!!, job!!, outRead.value, thread!!, receipt, files, authority, desktop)
            thread = null
            handedProcess = process
            handedOff = true
            return process
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            var cleanupFailure: Throwable? = null
            fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
            } }
            cleanup { if (initializedAttributes) kernel.getFunction("DeleteProcThreadAttributeList").invokeVoid(arrayOf(attributes)) }
            allocations.asReversed().forEach { cleanup { it.close() } }
            listOf(thread, input, binaryOutput, outWrite.value, token.value, restricted.value).forEach { cleanup { close(it) } }
            cleanup { free(sid.value) }
            if (!handedOff) {
                var stopped = true
                cleanup { try { job?.let { terminateJobAndConfirm(it) } } catch (error: Throwable) { stopped = false; throw error } }
                cleanup { try { child?.let {
                    val status = kernel.getFunction("WaitForSingleObject").invokeInt(arrayOf(it, 0))
                    if (status != 0) bool(kernel, "TerminateProcess", it, 1)
                    check(kernel.getFunction("WaitForSingleObject").invokeInt(arrayOf(it, 10_000)) == 0) { "Cannot confirm suspended check cleanup" }
                } } catch (error: Throwable) { stopped = false; throw error } }
                if (stopped) cleanup { authority?.restoreAndConfirm() }
                cleanup { authority?.close() }
                listOf(child, job, outRead.value).forEach { cleanup { close(it) } }
                cleanup { desktop?.let { check(user.getFunction("CloseDesktop").invokeInt(arrayOf(it)) != 0) { "Cannot close check desktop" } } }
            }
            cleanupFailure?.let { failure ->
                if (handedOff) try { kotlinx.coroutines.runBlocking { handedProcess!!.stopAndConfirm() } }
                    catch (stopFailure: Throwable) { failure.addSuppressed(stopFailure) }
                if (primary != null) primary!!.addSuppressed(failure) else throw failure
            }
        }
    }

    private fun terminateJobAndConfirm(job: Pointer) {
        bool(kernel, "TerminateJobObject", job, 0)
        Memory(48).use { accounting ->
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            do {
                bool(kernel, "QueryInformationJobObject", job, 1, accounting, 48, null)
                if (accounting.getInt(40) == 0) return
                check(System.nanoTime() < deadline) { "Check job still has active processes" }
                Thread.sleep(20)
            } while (true)
        }
    }

    private class WindowsProcess(private val handle: Pointer, private val job: Pointer, private val pipe: Pointer,
        private var thread: Pointer?, override val receipt: CheckProcessReceipt, private val files: NativeCheckReceiptFiles,
        private val authority: WindowsCheckAuthority?, private val desktop: Pointer?) : PreparedCheckProcess() {
        private val completion = CompletableFuture<Int>()
        private val lifetime = Any()
        private var released = false
        private var handlesClosed = false
        private var cleanupProof: NativeCheckCleanup? = null
        private val output = object : InputStream() {
            private var closed = false
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                if (length == 0) return 0
                if (closed) return -1
                Memory(length.toLong()).use { buffer ->
                    val n = IntByReference()
                    if (kernel.getFunction("ReadFile").invokeInt(arrayOf(pipe, buffer, length, n, null)) == 0) {
                        if (Native.getLastError() == 109) return -1 // ERROR_BROKEN_PIPE
                        throw java.io.IOException("Check output read failed")
                    }
                    if (n.value == 0) return -1
                    buffer.read(0, bytes, offset, n.value); return n.value
                }
            }
            override fun close() { if (!closed) { closed = true; close(pipe) } }
        }
        init {
            Thread({
                var failure: Throwable? = null
                var code = 255
                var authorityProof: String? = null
                fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
                    if (failure == null) failure = error else failure!!.addSuppressed(error)
                } }
                attempt {
                    check(kernel.getFunction("WaitForSingleObject").invokeInt(arrayOf(handle, -1)) == 0) { "Cannot observe check termination" }
                    val value = IntByReference(255); bool(kernel, "GetExitCodeProcess", handle, value); code = value.value
                }
                attempt { synchronized(lifetime) { terminateJobAndConfirm(job) } }
                // Never restore authority until the whole job is confirmed stopped.
                if (failure == null) attempt { authorityProof = authority?.restoreAndConfirm() ?: "unchanged:${receipt.id}" }
                attempt { authority?.close() }
                attempt { desktop?.let { check(user.getFunction("CloseDesktop").invokeInt(arrayOf(it)) != 0) { "Cannot close check desktop" } } }
                synchronized(lifetime) {
                    handlesClosed = true
                    listOf(thread, job, handle).forEach { value -> attempt { close(value) } }
                    thread = null
                }
                if (failure == null) attempt {
                    cleanupProof = files.confirm(receipt, NativeCheckCleanup(UUID.randomUUID().toString(), requireNotNull(authorityProof)))
                }
                if (failure == null) completion.complete(code) else completion.completeExceptionally(failure!!)
            }, "check-wait-${receipt.pid}").apply { isDaemon = true; start() }
        }
        override fun release() = synchronized(lifetime) {
            check(!released && !handlesClosed && !completion.isDone) { "Prepared check cannot be released" }
            released = true
            check(kernel.getFunction("ResumeThread").invokeInt(arrayOf(requireNotNull(thread))) != -1) { "Check release was not confirmed" }
        }
        override suspend fun stopAndConfirm(): NativeCheckCleanup = withContext(NonCancellable + Dispatchers.IO) {
            destroyForcibly(); waitFor(); requireNotNull(cleanupProof)
        }
        override fun getInputStream(): InputStream = output
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
        override fun waitFor(): Int = completion.get()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try { completion.get(timeout, unit); true } catch (_: java.util.concurrent.TimeoutException) { false }
        override fun exitValue(): Int = if (completion.isDone) completion.get() else throw IllegalThreadStateException("Process is alive")
        override fun isAlive() = !completion.isDone
        override fun pid() = receipt.pid
        override fun toHandle(): ProcessHandle = ProcessHandle.of(pid()).orElseThrow { IllegalStateException("Process exited") }
        override fun destroy() { synchronized(lifetime) { if (!handlesClosed) bool(kernel, "TerminateJobObject", job, 1) } }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
}
