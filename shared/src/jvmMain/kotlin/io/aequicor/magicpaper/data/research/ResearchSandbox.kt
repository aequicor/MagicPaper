package io.aequicor.magicpaper.data.research

import java.io.File
import java.nio.file.Path

internal interface ResearchSandbox {
    fun launch(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy): Process

    companion object {
        fun current(): ResearchSandbox = when {
            System.getProperty("os.name").startsWith("Mac") -> MacResearchSandbox
            System.getProperty("os.name").startsWith("Linux") -> LinuxResearchSandbox
            System.getProperty("os.name").startsWith("Windows") -> WindowsResearchSandbox
            else -> error("Защищённые проверки не поддерживаются на этой ОС")
        }
    }
}

internal object MacResearchSandbox : ResearchSandbox {
    fun profile(policy: ResearchWorkspacePolicy): String {
        fun quoted(path: Path) = "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            appendLine("(version 1)\n(allow default)\n(deny file-write*)")
            // Keep every descendant in the host-owned group, including raw syscall callers.
            // posix_spawn has group/session attributes that bypass setpgid/setsid syscalls.
            appendLine("(deny syscall-unix (syscall-number SYS_setpgid SYS_setsid SYS_posix_spawn))")
            appendLine("(deny signal)\n(allow signal (target self) (target children))")
            appendLine("(deny process-info* mach-task-name)\n(allow process-info* (target self) (target children))")
            appendLine("(deny appleevent-send)\n(deny network-outbound (remote unix-socket))")
            appendLine("(deny mach-lookup (global-name \"com.apple.coreservices.launchservicesd\") (global-name \"com.apple.tccd\"))")
            appendLine("(allow file-write* (literal \"/dev/null\"))")
            policy.writable.forEach { appendLine("(allow file-write* (subpath ${quoted(it)}))") }
            policy.protected.forEach { appendLine("(deny file-write* (subpath ${quoted(it)}))") }
        }
    }
    override fun launch(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy): Process {
        check(File("/usr/bin/sandbox-exec").canExecute()) { "Seatbelt недоступен" }
        return UnixResearchProcess.launch(listOf("/usr/bin/sandbox-exec", "-p", profile(policy), "--") + command, cwd, environment)
    }
}

internal object LinuxResearchSandbox : ResearchSandbox {
    fun arguments(policy: ResearchWorkspacePolicy, cwd: Path): List<String> = buildList {
        addAll(listOf("--die-with-parent", "--new-session", "--unshare-user", "--unshare-pid", "--unshare-ipc", "--unshare-net",
            "--cap-drop", "ALL", "--disable-userns", "--ro-bind", "/", "/", "--proc", "/proc", "--dev", "/dev"))
        // Host sockets under /run and temporary directories cannot act as unsandboxed command brokers.
        addAll(listOf("--tmpfs", "/run", "--tmpfs", "/tmp"))
        addAll(listOf("--ro-bind", policy.project.toString(), policy.project.toString()))
        policy.writable.forEach { addAll(listOf("--bind", it.toString(), it.toString())) }
        policy.protected.filter { it.toFile().exists() }.forEach { addAll(listOf("--ro-bind", it.toString(), it.toString())) }
        addAll(listOf("--chdir", cwd.toString(), "--"))
    }
    override fun launch(command: List<String>, cwd: Path, environment: Map<String, String>, policy: ResearchWorkspacePolicy): Process {
        val bwrap = listOf("/usr/bin/bwrap", "/bin/bwrap").firstOrNull { File(it).canExecute() }
            ?: error("Для защищённых проверок требуется bubblewrap (bwrap)")
        return UnixResearchProcess.launch(listOf(bwrap) + arguments(policy, cwd) + command, cwd, environment)
    }
}
