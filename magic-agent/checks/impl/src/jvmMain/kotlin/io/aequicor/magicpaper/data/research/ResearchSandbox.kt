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
    /** How [program] is started, for diagnostics: `direct`, or the interpreter it needs, such as `cmd` for a batch file. */
    fun launchMethod(program: String): String = "direct"
    fun prepare(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess

    /** stdout goes directly to the owned file; the process pipe contains stderr only. */
    fun prepareBinary(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy?,
        receiptId: String, receiptDirectory: Path, standardOutput: Path,
        authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess = throw NativeCheckUnavailable("Двоичный вывод недоступен")

    /**
     * A managed-worktree command the user allowed to start programs the containment otherwise refuses. Only a
     * sandbox that restricts starting programs differs from [prepare] without a filesystem policy; none does now,
     * so the grant is kept in reserve for one that would.
     */
    fun prepareSpawning(command: List<String>, cwd: Path, environment: Map<String, String>,
        receiptId: String, receiptDirectory: Path, authorityRecorder: CheckAuthorityRecorder): PreparedCheckProcess =
        prepare(command, cwd, environment, null, receiptId, receiptDirectory, authorityRecorder)

    /**
     * Whether a failed command's [output] shows this sandbox refused to start a program; a grant would change it.
     * A sandbox that never refuses must keep this false: a failing build's own "Operation not permitted" would
     * otherwise ask the user about a refusal that did not happen.
     */
    fun spawnRefused(output: String): Boolean = false

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
        val membership = SeatbeltMembership()
        return UnixResearchProcess.prepare(command, listOf("/usr/bin/sandbox-exec", "-p", profile(policy, membership), "--"),
            cwd, environment, receiptId, receiptDirectory, standardOutput, membership)
    }
    /**
     * Program start stays allowed: Python's framework launcher, `xargs` in `gradlew` and the Java launcher all use
     * `posix_spawn`. Its group and session attributes can still move a descendant out of the host-owned group, so
     * the stop proof also follows [membership], which no descendant can leave.
     */
    fun profile(policy: ResearchWorkspacePolicy?, membership: SeatbeltMembership): String {
        fun quoted(path: Path) = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            appendLine("(version 1)\n(allow default)")
            // Keep ordinary descendants in the host-owned group, including raw syscall callers.
            appendLine("(deny syscall-unix (syscall-number SYS_setpgid SYS_setsid))")
            appendLine(membership.rule)
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
        val membership = SeatbeltMembership()
        return UnixResearchProcess.prepare(command, listOf("/usr/bin/sandbox-exec", "-p", profile(policy, membership), "--"),
            cwd, environment, receiptId, receiptDirectory, membership = membership)
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
