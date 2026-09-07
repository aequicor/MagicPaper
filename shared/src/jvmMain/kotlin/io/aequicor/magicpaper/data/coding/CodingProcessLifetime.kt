package io.aequicor.magicpaper.data.coding

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/** Windows job ownership also terminates tools when the application crashes, before recovery. */
internal object CodingProcessLifetime {
    private val jobs = mutableMapOf<Long, Pointer>()
    private val kernel by lazy { NativeLibrary.getInstance("kernel32") }
    @Synchronized fun attach(process: Process) {
        if (!System.getProperty("os.name").startsWith("Windows", ignoreCase = true) || process.pid() in jobs) return
        // JOBOBJECT_EXTENDED_LIMIT_INFORMATION, Windows x64/ARM64 ABI.
        // https://learn.microsoft.com/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information
        check(Native.POINTER_SIZE == 8) { "Для изоляции coding-процессов требуется 64-битная Java" }
        val job = kernel.getFunction("CreateJobObjectW").invokePointer(arrayOf(null, null)) ?: error("Не удалось создать job object")
        try {
            Memory(144).use { information ->
                information.clear()
                information.setInt(16, 0x2000) // JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
                check(kernel.getFunction("SetInformationJobObject").invokeInt(arrayOf(job, 9, information, 144)) != 0) {
                    "Не удалось настроить завершение coding-процессов: ${Native.getLastError()}"
                }
            }
            val handle = kernel.getFunction("OpenProcess").invokePointer(arrayOf(0x0101, 0, process.pid().toInt()))
                ?: error("Не удалось открыть coding-процесс")
            try {
                check(kernel.getFunction("AssignProcessToJobObject").invokeInt(arrayOf(job, handle)) != 0) {
                    "Не удалось изолировать coding-процесс: ${Native.getLastError()}"
                }
            } finally { kernel.getFunction("CloseHandle").invokeInt(arrayOf(handle)) }
            jobs[process.pid()] = job
            process.onExit().thenRun { release(process.pid()) }
        } catch (e: Exception) {
            kernel.getFunction("CloseHandle").invokeInt(arrayOf(job))
            process.destroyForcibly()
            throw e
        }
    }
    @Synchronized private fun release(pid: Long) { jobs.remove(pid)?.let { kernel.getFunction("CloseHandle").invokeInt(arrayOf(it)) } }
}
