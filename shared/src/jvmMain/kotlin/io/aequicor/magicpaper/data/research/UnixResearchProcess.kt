package io.aequicor.magicpaper.data.research

import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.StringArray
import com.sun.jna.ptr.IntByReference
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A separate process group is essential: children must not survive a completed test command. */
internal class UnixResearchProcess private constructor(private val childPid: Int, private val readFd: Int, private val guardian: Process?) : Process() {
    private val completion = CompletableFuture<Int>()
    private val output = object : InputStream() {
        private var closed = false
        override fun read(): Int = ByteArray(1).let { if (read(it, 0, 1) < 0) -1 else it[0].toInt() and 255 }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (closed) return -1
            Memory(length.toLong()).use { memory ->
                val n = libc.getFunction("read").invokeLong(arrayOf(readFd, memory, length.toLong())).toInt()
                if (n <= 0) return -1
                memory.read(0, bytes, offset, n)
                return n
            }
        }
        override fun close() { if (!closed) { closed = true; libc.getFunction("close").invokeInt(arrayOf(readFd)) } }
    }
    init {
        Thread({
            val status = IntByReference()
            var result: Int
            do { result = libc.getFunction("waitpid").invokeInt(arrayOf(childPid, status, 0)) } while (result < 0 && com.sun.jna.Native.getLastError() == 4)
            // Also terminate descendants when the foreground command exits normally.
            libc.getFunction("kill").invokeInt(arrayOf(-childPid, 9))
            guardian?.let { watcher -> runCatching { watcher.outputStream.use { it.write("done\n".toByteArray()); it.flush() } } }
            val raw = status.value
            completion.complete(if (result < 0) 255 else if (raw and 127 == 0) raw shr 8 and 255 else 128 + (raw and 127))
        }, "research-wait-$childPid").apply { isDaemon = true; start() }
    }
    override fun getInputStream(): InputStream = output
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun waitFor(): Int = completion.get()
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try { completion.get(timeout, unit); true } catch (_: java.util.concurrent.TimeoutException) { false }
    override fun exitValue(): Int = completion.getNow(null) ?: throw IllegalThreadStateException("Process is alive")
    override fun isAlive() = !completion.isDone
    override fun pid() = childPid.toLong()
    override fun toHandle(): ProcessHandle = ProcessHandle.of(pid()).orElseThrow { IllegalStateException("Process exited") }
    override fun destroy() { if (isAlive) libc.getFunction("kill").invokeInt(arrayOf(-childPid, 15)) }
    override fun destroyForcibly(): Process { if (isAlive) libc.getFunction("kill").invokeInt(arrayOf(-childPid, 9)); return this }

    companion object {
        private val libc by lazy { NativeLibrary.getInstance("c") }
        fun launch(command: List<String>, cwd: Path, environment: Map<String, String>): Process {
            val pipe = IntArray(2)
            check(libc.getFunction("pipe").invokeInt(arrayOf(pipe)) == 0) { "Не удалось создать канал вывода проверки" }
            // File descriptors never escape to unrelated subprocesses.
            pipe.forEach { libc.getFunction("fcntl").invokeInt(arrayOf(it, 2, 1)) }
            val actions = Memory(1024).apply { clear() }
            val attrs = Memory(1024).apply { clear() }
            fun call(name: String, vararg args: Any?) { val code = libc.getFunction(name).invokeInt(args); check(code == 0) { "$name: $code" } }
            var initializedActions = false
            var initializedAttrs = false
            var childPid = 0
            var guardian: Process? = null
            try {
                call("posix_spawn_file_actions_init", actions); initializedActions = true
                call("posix_spawnattr_init", attrs); initializedAttrs = true
                call("posix_spawn_file_actions_adddup2", actions, pipe[1], 1)
                call("posix_spawn_file_actions_adddup2", actions, pipe[1], 2)
                call("posix_spawn_file_actions_addclose", actions, pipe[0])
                call("posix_spawn_file_actions_addclose", actions, pipe[1])
                call("posix_spawn_file_actions_addopen", actions, 0, "/dev/null", 0, 0)
                call("posix_spawn_file_actions_addchdir_np", actions, cwd.toString())
                val mac = System.getProperty("os.name").startsWith("Mac")
                // On macOS establish the parent-death watcher before the untrusted executable runs.
                call("posix_spawnattr_setflags", attrs, (2 or if (mac) 0x80 else 0).toShort())
                call("posix_spawnattr_setpgroup", attrs, 0)
                val pid = IntByReference()
                call("posix_spawnp", pid, command.first(), actions, attrs, StringArray(command.toTypedArray()),
                    StringArray(environment.map { (key, value) -> "$key=$value" }.toTypedArray()))
                childPid = pid.value
                if (mac) {
                    guardian = ProcessBuilder("/bin/sh", "-c",
                        "if ! IFS= read -r done; then /bin/kill -KILL -- \"-\$1\" 2>/dev/null; fi", "research-parent-watch", childPid.toString())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .apply { environment().clear() }.start()
                    check(guardian.isAlive) { "Не удалось установить контроль владельца проверки" }
                    check(libc.getFunction("kill").invokeInt(arrayOf(childPid, 19)) == 0) { "Не удалось продолжить проверку" } // SIGCONT on Darwin
                }
                libc.getFunction("close").invokeInt(arrayOf(pipe[1]))
                return UnixResearchProcess(pid.value, pipe[0], guardian)
            } catch (e: Exception) {
                if (childPid != 0) { libc.getFunction("kill").invokeInt(arrayOf(-childPid, 9)); libc.getFunction("waitpid").invokeInt(arrayOf(childPid, IntByReference(), 0)) }
                guardian?.outputStream?.close()
                pipe.forEach { libc.getFunction("close").invokeInt(arrayOf(it)) }
                throw e
            } finally {
                if (initializedActions) libc.getFunction("posix_spawn_file_actions_destroy").invokeInt(arrayOf(actions))
                if (initializedAttrs) libc.getFunction("posix_spawnattr_destroy").invokeInt(arrayOf(attrs))
                actions.close(); attrs.close()
            }
        }
    }
}
