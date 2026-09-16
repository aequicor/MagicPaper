package io.aequicor.magicpaper.data.computer

import com.sun.jna.NativeLibrary
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.nio.file.Path
import java.util.concurrent.TimeUnit

internal data class PermissionHost(val target: PermissionTarget, val screen: Boolean, val accessibility: Boolean)

/** Separate read-only hosts: inspecting setup cannot create/revoke a session lease or reuse its AX references. */
internal class DesktopComputerPermissions(
    private val platform: PermissionPlatform = when {
        System.getProperty("os.name").startsWith("Mac") -> PermissionPlatform.MACOS
        System.getProperty("os.name").startsWith("Windows") -> PermissionPlatform.WINDOWS
        else -> PermissionPlatform.OTHER
    },
    private val mainHost: () -> PermissionHost = ::mainPermissionHost,
    private val helperHost: suspend () -> PermissionHost = ::helperPermissionHost,
    private val open: suspend (List<String>) -> Unit = ::openPermissionLocation,
) : ComputerPermissions {
    @Volatile private var targets: Set<PermissionTarget> = emptySet()

    override suspend fun inspect(computer: ComputerAccess, application: ComputerAccess): ComputerPermissionReport = withContext(Dispatchers.IO) {
        if (platform != PermissionPlatform.MACOS) return@withContext ComputerPermissionReport(platform)
        val checks = buildList {
            if (computer != ComputerAccess.OFF) {
                val host = mainHost()
                add(PermissionCheck(ComputerPermission.SCREEN_RECORDING, host.target, host.screen))
                if (computer == ComputerAccess.CONTROL) add(PermissionCheck(ComputerPermission.ACCESSIBILITY, host.target, host.accessibility))
            }
            if (application != ComputerAccess.OFF) {
                // Window discovery and inspection currently need both permissions, even in view-only mode.
                val host = helperHost()
                add(PermissionCheck(ComputerPermission.SCREEN_RECORDING, host.target, host.screen))
                add(PermissionCheck(ComputerPermission.ACCESSIBILITY, host.target, host.accessibility))
            }
        }
        ensureActive()
        targets = checks.map { it.target }.toSet()
        ComputerPermissionReport(platform, checks)
    }

    override suspend fun openSettings(permission: ComputerPermission) {
        check(platform == PermissionPlatform.MACOS)
        open(listOf("/usr/bin/open", permissionSettingsUri(permission)))
    }

    override suspend fun reveal(target: PermissionTarget) {
        check(platform == PermissionPlatform.MACOS && target in targets)
        open(listOf("/usr/bin/open", "-R", target.path))
    }
}

internal fun permissionSettingsUri(permission: ComputerPermission): String =
    "x-apple.systempreferences:com.apple.preference.security?" + when (permission) {
        ComputerPermission.SCREEN_RECORDING -> "Privacy_ScreenCapture"
        ComputerPermission.ACCESSIBILITY -> "Privacy_Accessibility"
    }

internal fun permissionHostTarget(executable: Path): PermissionTarget {
    val bundle = generateSequence(executable) { it.parent }.firstOrNull { it.fileName?.toString()?.endsWith(".app") == true }
    return PermissionTarget(if (bundle != null) bundle.fileName.toString() else "Java / запуск из IDE", (bundle ?: executable).toString())
}

private fun mainPermissionHost(): PermissionHost {
    val cg = NativeLibrary.getInstance("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
    val ax = NativeLibrary.getInstance("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
    val executable = Path.of(ProcessHandle.current().info().command().orElseThrow { IllegalStateException("Missing process identity") })
    return PermissionHost(permissionHostTarget(executable),
        cg.getFunction("CGPreflightScreenCaptureAccess").invokeInt(emptyArray()) and 0xff != 0,
        ax.getFunction("AXIsProcessTrusted").invokeInt(emptyArray()) and 0xff != 0)
}

private suspend fun helperPermissionHost(): PermissionHost {
    val context = currentCoroutineContext()
    NativeApplicationDesktop().use { helper ->
        val result = helper.request(buildJsonObject { put("action", "permissions") }) { context.ensureActive() }
        return PermissionHost(PermissionTarget("Помощник MagicPaper", checkNotNull(helper.executablePath).toString()),
            result.getValue("screen_capture").jsonPrimitive.boolean,
            result.getValue("accessibility").jsonPrimitive.boolean)
    }
}

private suspend fun openPermissionLocation(command: List<String>) = withContext(Dispatchers.IO) {
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    try {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
            ensureActive()
            check(System.nanoTime() < deadline) { "System settings launch timeout" }
        }
        check(process.exitValue() == 0) { "System settings launch failed" }
    } finally { if (process.isAlive) process.destroyForcibly() }
}
