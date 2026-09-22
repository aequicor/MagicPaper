package io.aequicor.magicpaper.data.research

import java.io.File
import java.nio.file.Path

internal interface ResearchSandbox {
    /**
     * False when this OS cannot confine a command's writes at all. Only commands whose arguments are an
     * exact read-only allowlist may run then; anything that could write stays refused, because without
     * confinement its protection would be an assumption rather than an enforced property.
     */
    val confinesWrites: Boolean get() = true
    fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess

    /** stdout goes directly to the owned file; the process pipe contains stderr only. */
    fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, standardOutput: Path,
        authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess = throw NativeCheckUnavailable("Двоичный вывод недоступен")

    companion object {
        fun current(): ResearchSandbox = when {
            System.getProperty("os.name").startsWith("Mac") -> MacResearchSandbox
            System.getProperty("os.name").startsWith("Linux") -> LinuxResearchSandbox
            System.getProperty("os.name").startsWith("Windows") -> WindowsResearchSandbox
            else -> throw NativeCheckUnavailable("Проверки не поддерживаются на этой ОС")
        }
    }
}

internal object MacResearchSandbox : ResearchSandbox {
    override fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, standardOutput: Path,
        authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
        if (!File("/usr/bin/sandbox-exec").canExecute()) throw NativeCheckUnavailable("Seatbelt недоступен")
        return UnixResearchProcess.prepare(command, listOf("/usr/bin/sandbox-exec", "-p", profile(policy), "--"),
            cwd, environment, receiptId, receiptDirectory, standardOutput)
    }
    fun profile(policy: ResearchWorkspacePolicy?): String {
        fun quoted(path: Path) = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            appendLine("(version 1)\n(allow default)")
            // Keep every descendant in the host-owned group, including raw syscall callers.
            // posix_spawn has group/session attributes that bypass setpgid/setsid syscalls.
            appendLine("(deny syscall-unix (syscall-number SYS_setpgid SYS_setsid SYS_posix_spawn))")
            appendLine("(deny signal)\n(allow signal (target self) (target children))")
            if (policy == null) return@buildString // Managed worktrees retain ordinary file and network access.
            appendLine("(deny file-write*)")
            appendLine("(deny process-info* mach-task-name)\n(allow process-info* (target self) (target children))")
            appendLine("(deny appleevent-send)\n(deny network-outbound (remote unix-socket))")
            appendLine("(deny mach-lookup (global-name \"com.apple.coreservices.launchservicesd\") (global-name \"com.apple.tccd\"))")
            appendLine("(allow file-write* (literal \"/dev/null\"))")
            policy.writable.forEach { appendLine("(allow file-write* (subpath ${quoted(it)}))") }
            policy.protected.forEach { appendLine("(deny file-write* (subpath ${quoted(it)}))") }
        }
    }
    override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
        if (!File("/usr/bin/sandbox-exec").canExecute()) throw NativeCheckUnavailable("Seatbelt недоступен")
        return UnixResearchProcess.prepare(command, listOf("/usr/bin/sandbox-exec", "-p", profile(policy), "--"),
            cwd, environment, receiptId, receiptDirectory)
    }
}

internal object LinuxResearchSandbox : ResearchSandbox {
    override fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, standardOutput: Path,
        authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
        val bwrap = listOf("/usr/bin/bwrap", "/bin/bwrap").firstOrNull { File(it).canExecute() }
            ?: throw NativeCheckUnavailable("Для проверок требуется bubblewrap (bwrap)")
        return UnixResearchProcess.prepare(command, listOf(bwrap) + arguments(policy, cwd), cwd, environment, receiptId, receiptDirectory, standardOutput)
    }
    fun arguments(policy: ResearchWorkspacePolicy?, cwd: Path): List<String> = buildList {
        addAll(listOf("--die-with-parent", "--unshare-user", "--unshare-pid", "--as-pid-1",
            "--cap-drop", "ALL", "--disable-userns", if (policy == null) "--bind" else "--ro-bind", "/", "/", "--proc", "/proc", "--dev", "/dev"))
        if (policy == null) { addAll(listOf("--chdir", cwd.toString(), "--")); return@buildList }
        addAll(listOf("--unshare-ipc", "--unshare-net"))
        // Host sockets under /run and temporary directories cannot act as unsandboxed command brokers.
        addAll(listOf("--tmpfs", "/run", "--tmpfs", "/tmp"))
        addAll(listOf("--ro-bind", policy.project.toString(), policy.project.toString()))
        policy.writable.forEach { addAll(listOf("--bind", it.toString(), it.toString())) }
        policy.protected.filter { it.toFile().exists() }.forEach { addAll(listOf("--ro-bind", it.toString(), it.toString())) }
        addAll(listOf("--chdir", cwd.toString(), "--"))
    }
    override fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess {
        val bwrap = listOf("/usr/bin/bwrap", "/bin/bwrap").firstOrNull { File(it).canExecute() }
            ?: throw NativeCheckUnavailable("Для проверок требуется bubblewrap (bwrap)")
        return UnixResearchProcess.prepare(command, listOf(bwrap) + arguments(policy, cwd), cwd, environment, receiptId, receiptDirectory)
    }
}
