package io.aequicor.magicpaper.data.research

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import java.util.UUID

/**
 * Which live processes run under one check's Seatbelt profile. The profile is inherited by every descendant and
 * cannot be left, unlike the process group: `posix_spawn` attributes change a child's group or session inside the
 * kernel, where the profile's syscall filter never sees them. Refusing `posix_spawn` itself closed that gap only by
 * refusing the ordinary way to start a program on macOS, so Python, `xargs` in `gradlew` and the Java launcher could
 * not run at all. Membership lets the stop proof follow a descendant wherever its group went instead.
 *
 * Each check's profile refuses a lookup of its own random [marker]. A process that refuses the marker yet allows
 * another random name runs under this very profile: an unsandboxed process refuses neither, and a process under some
 * other sandbox, such as a system service that refuses every unknown name, refuses both.
 */
internal class SeatbeltMembership(
    val marker: String = "$PREFIX${UUID.randomUUID()}",
    private val probe: String = "$PREFIX${UUID.randomUUID()}",
) {
    init { require(marker != probe && QUOTABLE.matches(marker) && QUOTABLE.matches(probe)) }

    /** The profile rule that marks the check's processes. */
    val rule: String get() = "(deny mach-lookup (global-name \"$marker\"))"

    /**
     * Live processes of this user under the check's profile, whatever their group or parent. A zombie has already
     * stopped. A member that cannot be inspected fails the proof rather than being assumed gone.
     */
    fun members(): List<Int> {
        val uid = libc.getFunction("getuid").invokeInt(emptyArray())
        return allPids().filter { pid ->
            val owner = ownerOf(pid) ?: return@filter false
            owner == uid && refuses(pid, marker) && !refuses(pid, probe)
        }
    }

    /** Null for a process that is gone, a zombie or another user's. */
    private fun ownerOf(pid: Int): Int? = Memory(SHORT_INFO_SIZE.toLong()).use { info ->
        val read = proc.getFunction("proc_pidinfo").invokeInt(arrayOf(pid, PROC_PIDT_SHORTBSDINFO, 0L, info, SHORT_INFO_SIZE))
        if (read == 0 && Native.getLastError().let { it == ESRCH || it == EPERM }) return null
        check(read == SHORT_INFO_SIZE && info.getInt(0) == pid) { "Cannot inspect check member" }
        if (info.getInt(12) == SZOMB) null else info.getInt(36)
    }

    private fun refuses(pid: Int, name: String): Boolean = when (sandboxCheck.invokeInt(arrayOf<Any>(pid, "mach-lookup",
        SANDBOX_FILTER_GLOBAL_NAME or SANDBOX_CHECK_NO_REPORT, name))) {
        0 -> false
        1 -> true
        // The process ended between listing and inspection; a live one that cannot be answered for is not assumed free.
        else -> { check(libc.getFunction("kill").invokeInt(arrayOf(pid, 0)) != 0 && Native.getLastError() == ESRCH) {
            "Cannot inspect check member sandbox"
        }; false }
    }

    private fun allPids(): List<Int> {
        val estimate = proc.getFunction("proc_listallpids").invokeInt(arrayOf(null, 0))
        check(estimate >= 0) { "Cannot list processes" }
        val capacity = estimate + 1024
        Memory(capacity * 4L).use { pids ->
            val count = proc.getFunction("proc_listallpids").invokeInt(arrayOf(pids, capacity * 4))
            check(count in 0 until capacity) { "Incomplete process listing" }
            return (0 until count).map { pids.getInt(it * 4L) }.filter { it > 0 }
        }
    }

    companion object {
        private const val PREFIX = "io.aequicor.magicpaper.check."
        private val QUOTABLE = Regex("[A-Za-z0-9.-]+")
        private const val PROC_PIDT_SHORTBSDINFO = 13
        private const val SHORT_INFO_SIZE = 64
        private const val SZOMB = 5
        private const val ESRCH = 3
        private const val EPERM = 1
        private const val SANDBOX_FILTER_GLOBAL_NAME = 2
        private const val SANDBOX_CHECK_NO_REPORT = 0x40000000
        private val libc by lazy { NativeLibrary.getInstance("c") }
        private val proc by lazy { NativeLibrary.getInstance("proc") }
        /** `int sandbox_check(pid_t, const char *operation, int type, ...)`: three fixed arguments, then variadic ones. */
        private val sandboxCheck by lazy { libc.getFunction("sandbox_check", Function.C_CONVENTION or (3 shl 7)) }
    }
}
