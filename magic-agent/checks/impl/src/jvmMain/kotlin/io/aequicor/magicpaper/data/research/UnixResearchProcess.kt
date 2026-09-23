package io.aequicor.magicpaper.data.research

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import io.aequicor.magicpaper.domain.checks.CheckProcessReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** The trusted shell gate runs inside containment; no caller command executes during preparation. */
internal class UnixResearchProcess private constructor(
    private val childPid: Int, private val readFd: Int, private var gateFd: Int,
    private val permit: String, private val guardian: Process, private val namespace: LinuxNamespace?,
    override val receipt: CheckProcessReceipt, private val files: NativeCheckReceiptFiles,
) : PreparedCheckProcess() {
    private val completion = CompletableFuture<Int>()
    private val lifetime = Any()
    private var released = false
    private var reaped = false
    private var cleanup: NativeCheckCleanup? = null
    private val output = FdInputStream(readFd)

    init {
        Thread({
            var failure: Throwable? = null
            var code = 255
            fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            } }
            // WNOWAIT pins the group leader's PID until every member is stopped. A dead parent alone is not proof.
            attempt { Memory(128).use { info ->
                var result: Int
                do { result = libc.getFunction("waitid").invokeInt(arrayOf(1, childPid, info, 4 or if (mac) 0x20 else 0x01000000)) }
                while (result < 0 && Native.getLastError() == 4)
                check(result == 0) { "Cannot observe check process termination" }
            } }
            attempt { synchronized(lifetime) { signalGroup(9); namespace?.terminate() } }
            attempt { awaitNoWriters(childPid, namespace) }
            // Stop the guardian while the leader is still unreaped, so it can never signal a reused group ID.
            attempt {
                guardian.outputStream.use { it.write("done\n".encodeToByteArray()); it.flush() }
                check(guardian.waitFor(10, TimeUnit.SECONDS) && guardian.exitValue() == 0) { "Check guardian did not close cleanly" }
            }
            attempt { synchronized(lifetime) {
                val status = IntByReference()
                var result: Int
                do { result = libc.getFunction("waitpid").invokeInt(arrayOf(childPid, status, 0)) }
                while (result < 0 && Native.getLastError() == 4)
                check(result == childPid) { "Cannot reap check process" }
                reaped = true
                val raw = status.value
                code = if (raw and 127 == 0) raw shr 8 and 255 else 128 + (raw and 127)
            } }
            attempt { namespace?.close() }
            attempt { synchronized(lifetime) { closeGate() } }
            if (failure == null) attempt {
                cleanup = files.confirm(receipt, NativeCheckCleanup(UUID.randomUUID().toString(), "unchanged:${receipt.id}"))
            }
            if (failure == null) completion.complete(code) else completion.completeExceptionally(failure!!)
        }, "check-wait-$childPid").apply { isDaemon = true; start() }
    }

    override fun release() = synchronized(lifetime) {
        check(!released && !reaped && !completion.isDone && guardian.isAlive) { "Prepared check cannot be released" }
        released = true // a failed pipe write has an unknown release outcome and is never repeated
        val bytes = "$permit\n".encodeToByteArray()
        Memory(bytes.size.toLong()).use { memory ->
            memory.write(0, bytes, 0, bytes.size)
            check(libc.getFunction("write").invokeLong(arrayOf(gateFd, memory, bytes.size.toLong())) == bytes.size.toLong()) {
                "Check release was not confirmed"
            }
        }
        closeGate()
    }
    override suspend fun stopAndConfirm(): NativeCheckCleanup = withContext(NonCancellable + Dispatchers.IO) {
        destroyForcibly(); waitFor(); requireNotNull(cleanup)
    }
    private fun closeGate() { if (gateFd >= 0) { val fd = gateFd; gateFd = -1; closeFd(fd) } }
    private fun signalGroup(signal: Int) {
        if (reaped) return
        val result = libc.getFunction("kill").invokeInt(arrayOf(-childPid, signal))
        val error = Native.getLastError()
        // Darwin returns EPERM for a group containing only our unreaped zombie. Verify the entire
        // pinned group in that case; EPERM alone never proves that descendants stopped.
        check(result == 0 || error == 3 || (mac && error == 1 && macGroupWriters(childPid).isEmpty())) {
            "Cannot stop owned check group ($error)"
        }
    }
    override fun getInputStream(): InputStream = output
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun waitFor(): Int = completion.get()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try { completion.get(timeout, unit); true }
        catch (_: java.util.concurrent.TimeoutException) { false }
    override fun exitValue(): Int = if (completion.isDone) completion.get() else throw IllegalThreadStateException("Process is alive")
    override fun isAlive() = !completion.isDone
    /** Completes with [completion]; the default parks a pool thread in [waitFor] until then. */
    override fun onExit(): CompletableFuture<Process> = completion.handle { _, _ -> this }
    override fun pid() = childPid.toLong()
    override fun toHandle(): ProcessHandle = ProcessHandle.of(pid()).orElseThrow { IllegalStateException("Process exited") }
    override fun destroy() { synchronized(lifetime) { signalGroup(15); namespace?.terminate() } }
    override fun destroyForcibly(): Process { synchronized(lifetime) { signalGroup(9); namespace?.terminate() }; return this }

    companion object {
        private val libc by lazy { NativeLibrary.getInstance("c") }
        private val mac get() = System.getProperty("os.name").startsWith("Mac")
        private fun closeFd(fd: Int) { check(libc.getFunction("close").invokeInt(arrayOf(fd)) == 0) { "Cannot close check pipe" } }
        private fun startedAt(pid: Long): Long = ProcessHandle.of(pid).orElseThrow().info().startInstant().orElseThrow().toEpochMilli()

        fun prepare(command: List<String>, containment: List<String>, cwd: Path, environment: Map<String, String>,
            receiptId: String, receiptDirectory: Path, standardOutput: Path? = null): PreparedCheckProcess {
            require(command.isNotEmpty() && command.none { '\u0000' in it })
            val permit = UUID.randomUUID().toString()
            // The handshake stays on the pipe. Only after durable release does stdout switch to
            // its own file; paths remain positional values, never interpolated shell source.
            val redirect = if (standardOutput == null) "" else "exec > \"\$1\" || exit 125; shift; "
            val gate = listOf("/bin/sh", "-c", "printf '%s\\n' \"\$1\"; IFS= read -r permit || exit 125; [ \"\$permit\" = \"\$1\" ] || exit 125; shift; " +
                redirect + "exec 0</dev/null; exec \"\$@\"", "magicpaper-check-gate", permit) + listOfNotNull(standardOutput?.toString()) + command
            val argv = containment + gate
            val output = IntArray(2) { -1 }; val input = IntArray(2) { -1 }
            val actions = Memory(1024).apply { clear() }; val attrs = Memory(1024).apply { clear() }
            var initializedActions = false; var initializedAttrs = false
            var pid = 0; var guardian: Process? = null; var namespace: LinuxNamespace? = null
            var handedOff = false
            var primary: Throwable? = null
            var handedProcess: UnixResearchProcess? = null
            fun call(name: String, vararg args: Any?) { check(libc.getFunction(name).invokeInt(args) == 0) { "Native check preparation failed: $name" } }
            try {
                call("pipe", output); call("pipe", input)
                (output + input).forEach { call("fcntl", it, 2, 1) } // FD_CLOEXEC
                call("posix_spawn_file_actions_init", actions); initializedActions = true
                call("posix_spawnattr_init", attrs); initializedAttrs = true
                call("posix_spawn_file_actions_adddup2", actions, input[0], 0)
                call("posix_spawn_file_actions_adddup2", actions, output[1], 1)
                call("posix_spawn_file_actions_adddup2", actions, output[1], 2)
                (output + input).forEach { call("posix_spawn_file_actions_addclose", actions, it) }
                call("posix_spawn_file_actions_addchdir_np", actions, cwd.toString())
                call("posix_spawnattr_setflags", attrs, 2.toShort()) // POSIX_SPAWN_SETPGROUP
                call("posix_spawnattr_setpgroup", attrs, 0)
                val child = IntByReference()
                call("posix_spawnp", child, argv.first(), actions, attrs, StringArray(argv.toTypedArray()),
                    StringArray(environment.map { (key, value) -> "$key=$value" }.toTypedArray()))
                pid = child.value
                closeFd(output[1]); output[1] = -1; closeFd(input[0]); input[0] = -1
                // EOF kills only this still-pinned group. It is a crash backstop, never restart cleanup proof.
                guardian = ProcessBuilder("/bin/sh", "-c", "if ! IFS= read -r done; then /bin/kill -KILL -- \"-\$1\"; fi", "check-parent-watch", pid.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                    .apply { environment().clear() }.start()
                check(guardian.isAlive) { "Cannot establish check guardian" }
                awaitGate(output[0], permit)
                if (!mac) namespace = LinuxNamespace.capture(pid)
                val receipt = CheckProcessReceipt(receiptId, if (mac) "seatbelt-process-group" else "bubblewrap-pid-namespace", pid.toLong())
                val files = NativeCheckReceiptFiles(receiptDirectory, receiptId)
                files.record(NativeCheckIdentity(receipt, startedAt(pid.toLong()), namespace?.identity ?: "pgrp:$pid",
                    guardian.pid(), startedAt(guardian.pid())))
                val process = UnixResearchProcess(pid, output[0], input[1], permit, guardian, namespace, receipt, files)
                handedProcess = process
                handedOff = true
                return process
            } catch (error: Throwable) {
                primary = error
                // The gate has never received its permit. Still, cleanup failure is UNKNOWN, not retryable unavailability.
                fun cleanup(block: () -> Unit) { try { block() } catch (failure: Throwable) { error.addSuppressed(failure) } }
                if (pid > 0) {
                    cleanup { val killed = libc.getFunction("kill").invokeInt(arrayOf(-pid, 9)); check(killed == 0 || Native.getLastError() == 3) }
                    cleanup { namespace?.terminate() }
                    cleanup { awaitNoWriters(pid, namespace) }
                    cleanup { guardian?.let { it.outputStream.use { stream -> stream.write("done\n".encodeToByteArray()); stream.flush() }; check(it.waitFor(10, TimeUnit.SECONDS) && it.exitValue() == 0) } }
                    cleanup { check(libc.getFunction("waitpid").invokeInt(arrayOf(pid, IntByReference(), 0)) == pid) }
                }
                cleanup { namespace?.close() }
                throw error
            } finally {
                var cleanupFailure: Throwable? = null
                fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = error else cleanupFailure!!.addSuppressed(error)
                } }
                if (!handedOff) (output + input).filter { it >= 0 }.forEach { cleanup { closeFd(it) } }
                cleanup { if (initializedActions) call("posix_spawn_file_actions_destroy", actions) }
                cleanup { if (initializedAttrs) call("posix_spawnattr_destroy", attrs) }
                cleanup { actions.close() }; cleanup { attrs.close() }
                cleanupFailure?.let { failure ->
                    if (handedOff) try { kotlinx.coroutines.runBlocking { handedProcess!!.stopAndConfirm() } }
                        catch (stopFailure: Throwable) { failure.addSuppressed(stopFailure) }
                    if (primary != null) primary!!.addSuppressed(failure) else throw failure
                }
            }
        }

        private fun awaitGate(fd: Int, permit: String) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            val expected = "$permit\n".encodeToByteArray()
            // One-byte reads leave all user output untouched in the pipe. No provider/user output is included in errors.
            for (byte in expected) {
                Memory(8).use { poll ->
                    poll.clear(); poll.setInt(0, fd); poll.setShort(4, 1)
                    val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(0).toInt()
                    check(remaining > 0 && libc.getFunction("poll").invokeInt(arrayOf(poll, 1L, remaining)) > 0) { "Check containment did not become ready" }
                }
                Memory(1).use { value -> check(libc.getFunction("read").invokeLong(arrayOf(fd, value, 1L)) == 1L && value.getByte(0) == byte) {
                    "Check containment did not confirm readiness"
                } }
            }
        }

        private fun awaitNoWriters(group: Int, namespace: LinuxNamespace?) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (true) {
                val writers = if (mac) macGroupWriters(group) else requireNotNull(namespace) { "Missing PID namespace proof" }.writers()
                if (writers.isEmpty()) return
                check(System.nanoTime() < deadline) { "Owned check processes have not stopped" }
                Thread.sleep(10)
            }
        }

        private fun macGroupWriters(group: Int): List<Int> {
            val proc = NativeLibrary.getInstance("proc")
            val size = proc.getFunction("proc_listpids").invokeInt(arrayOf(2, group, null, 0))
            check(size >= 0) { "Cannot inspect owned process group" }
            Memory((size + 4096).toLong()).use { pids ->
                val count = proc.getFunction("proc_listpids").invokeInt(arrayOf(2, group, pids, pids.size().toInt()))
                check(count >= 0 && count < pids.size()) { "Incomplete process group inspection" }
                return (0 until count / 4).map { pids.getInt(it * 4L) }.filter { it > 0 }.filter { pid ->
                    Memory(64).use { info ->
                        val read = proc.getFunction("proc_pidinfo").invokeInt(arrayOf(pid, 13, 0L, info, 64))
                        if (read == 0 && Native.getLastError() == 3) false
                        else { check(read == 64 && info.getInt(0) == pid) { "Cannot inspect group member" }; info.getInt(8) == group && info.getInt(12) != 5 }
                    }
                }
            }
        }

        private class FdInputStream(private val fd: Int) : InputStream() {
            private var closed = false
            override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                java.util.Objects.checkFromIndexSize(offset, length, bytes.size)
                if (length == 0) return 0
                if (closed) return -1
                Memory(length.toLong()).use { memory ->
                    var n: Int
                    do { n = libc.getFunction("read").invokeLong(arrayOf(fd, memory, length.toLong())).toInt() } while (n < 0 && Native.getLastError() == 4)
                    if (n == 0) return -1
                    if (n < 0) throw IOException("Check output read failed")
                    memory.read(0, bytes, offset, n); return n
                }
            }
            override fun close() { if (!closed) { closed = true; closeFd(fd) } }
        }

        /** A pidfd pins namespace init independently of numeric PID reuse; /proc proves no live member remains. */
        private class LinuxNamespace(val identity: String, private val initFd: Int, private val uid: String) : AutoCloseable {
            private var closed = false
            @Synchronized fun terminate() {
                if (closed) return
                val result = libc.getFunction("syscall").invokeLong(arrayOf<Any?>(424L, initFd, 9, null, 0)) // pidfd_send_signal
                check(result == 0L || Native.getLastError() == 3) { "Cannot stop check PID namespace" }
            }
            fun writers(): List<Int> = Files.list(Path.of("/proc")).use { paths -> paths.filter { it.fileName.toString().all(Char::isDigit) }.toList().mapNotNull { path ->
                try {
                    val status = Files.readString(path.resolve("status"))
                    if (status.lineSequence().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1] != uid) return@mapNotNull null
                    val ns = Files.readSymbolicLink(path.resolve("ns/pid")).toString()
                    val stat = Files.readString(path.resolve("stat")).substringAfterLast(") ").split(' ')
                    if (ns == identity && stat[0] != "Z" && stat[0] != "X") path.fileName.toString().toInt() else null
                } catch (_: NoSuchFileException) { null }
            } }
            @Synchronized override fun close() { if (!closed) { closed = true; closeFd(initFd) } }
            companion object {
                fun capture(group: Int): LinuxNamespace {
                    val uid = Files.readString(Path.of("/proc/self/status")).lineSequence().first { it.startsWith("Uid:") }.split(Regex("\\s+"))[1]
                    val ownNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString()
                    val matches = Files.list(Path.of("/proc")).use { paths -> paths.filter { it.fileName.toString().all(Char::isDigit) }.toList().mapNotNull { path ->
                        try {
                            val stat = Files.readString(path.resolve("stat")).substringAfterLast(") ").split(' ')
                            if (stat[2].toInt() != group) return@mapNotNull null
                            val status = Files.readString(path.resolve("status"))
                            val nsPids = status.lineSequence().first { it.startsWith("NSpid:") }.trim().split(Regex("\\s+"))
                            if (nsPids.last() != "1") return@mapNotNull null
                            val ns = Files.readSymbolicLink(path.resolve("ns/pid")).toString()
                            if (ns == ownNamespace) null else path.fileName.toString().toInt() to ns
                        } catch (_: NoSuchFileException) { null }
                    } }
                    check(matches.size == 1) { "Cannot identify gated check PID namespace" }
                    val (pid, identity) = matches.single()
                    val fd = libc.getFunction("syscall").invokeLong(arrayOf<Any?>(434L, pid, 0)).toInt() // pidfd_open
                    check(fd >= 0) { "Kernel process identity handles are unavailable" }
                    try {
                        check(Files.readSymbolicLink(Path.of("/proc/$pid/ns/pid")).toString() == identity) { "Check namespace identity changed" }
                        return LinuxNamespace(identity, fd, uid)
                    } catch (error: Throwable) { closeFd(fd); throw error }
                }
            }
        }
    }
}
