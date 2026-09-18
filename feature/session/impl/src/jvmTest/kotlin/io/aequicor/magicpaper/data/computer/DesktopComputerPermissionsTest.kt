package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.*

class DesktopComputerPermissionsTest {
    @Test fun macMainProcessPreflightReturnsStatusWithoutCaptureOrPrompt() = runTest {
        org.junit.Assume.assumeTrue("macOS permission preflight", System.getProperty("os.name").startsWith("Mac"))
        val report = DesktopComputerPermissions().inspect(ComputerAccess.CONTROL, ComputerAccess.OFF)
        assertEquals(PermissionPlatform.MACOS, report.platform)
        assertEquals(ComputerPermission.entries.toSet(), report.checks.map { it.permission }.toSet())
        assertTrue(report.checks.all { java.nio.file.Files.exists(Path.of(it.target.path)) })
    }

    @Test fun independentProcessesAndOnlyRequiredPermissionsAreProbed() = runTest {
        var main = 0; var helper = 0
        val app = PermissionTarget("MagicPaper.app", "/Applications/MagicPaper.app")
        val adapter = PermissionTarget("Helper", "/tmp/helper")
        val opened = mutableListOf<List<String>>()
        val port = DesktopComputerPermissions(PermissionPlatform.MACOS,
            { main++; PermissionHost(app, true, false) },
            { helper++; PermissionHost(adapter, false, true) }, { opened += it }, desktopCaptureUsesHelper = false)
        assertTrue(port.inspect(ComputerAccess.OFF, ComputerAccess.OFF).checks.isEmpty())
        assertEquals(0, main + helper)
        val screen = port.inspect(ComputerAccess.SCREEN, ComputerAccess.OFF)
        assertEquals(listOf(PermissionCheck(ComputerPermission.SCREEN_RECORDING, app, true)), screen.checks)
        val background = port.inspect(ComputerAccess.OFF, ComputerAccess.SCREEN)
        assertEquals(1, main); assertEquals(1, helper)
        assertEquals(listOf(false, true), background.checks.map { it.granted })
        assertTrue(background.checks.all { it.target == adapter })
        assertTrue(opened.isEmpty())
        port.openSettings(ComputerPermission.ACCESSIBILITY)
        assertEquals(listOf("/usr/bin/open", "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"), opened.single())
        port.reveal(adapter)
        assertEquals(listOf("/usr/bin/open", "-R", "/tmp/helper"), opened.last())
        assertFailsWith<IllegalStateException> { port.reveal(PermissionTarget("foreign", "/tmp/foreign")) }
        val both = port.inspect(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
        assertEquals(4, both.checks.size)
    }

    @Test fun windowsNeverOpensUnrelatedPrivacySettingsOrProbesMac() = runTest {
        val port = DesktopComputerPermissions(PermissionPlatform.WINDOWS,
            { error("mac probe") }, { error("helper probe") }, { error("launch") })
        assertEquals(ComputerPermissionReport(PermissionPlatform.WINDOWS), port.inspect(ComputerAccess.CONTROL, ComputerAccess.CONTROL))
        assertFailsWith<IllegalStateException> { port.openSettings(ComputerPermission.SCREEN_RECORDING) }
    }

    @Test fun errorsArePropagatedInsteadOfReportedAsGranted() = runTest {
        val port = DesktopComputerPermissions(PermissionPlatform.MACOS, { error("probe unavailable") })
        assertFailsWith<IllegalStateException> { port.inspect(ComputerAccess.CONTROL, ComputerAccess.OFF) }
    }

    @Test fun excludedDesktopCaptureRequestsScreenAccessForHelperAndInputAccessForMainProcess() = runTest {
        val app = PermissionTarget("App", "/tmp/app")
        val helper = PermissionTarget("Helper", "/tmp/helper")
        var mainProbes = 0
        val port = DesktopComputerPermissions(PermissionPlatform.MACOS,
            { mainProbes++; PermissionHost(app, false, true) }, { PermissionHost(helper, true, false) },
            { error("Preflight must never prompt") }, desktopCaptureUsesHelper = true)
        val screen = port.inspect(ComputerAccess.SCREEN, ComputerAccess.OFF)
        assertEquals(listOf(PermissionCheck(ComputerPermission.SCREEN_RECORDING, helper, true)), screen.checks)
        assertEquals(0, mainProbes)
        val control = port.inspect(ComputerAccess.CONTROL, ComputerAccess.OFF)
        assertEquals(setOf(PermissionCheck(ComputerPermission.ACCESSIBILITY, app, true),
            PermissionCheck(ComputerPermission.SCREEN_RECORDING, helper, true)), control.checks.toSet())
        assertEquals(3, port.inspect(ComputerAccess.CONTROL, ComputerAccess.CONTROL).checks.size)
    }

    @Test fun actualBundleOrDevelopmentExecutableIsUsedNotInventedApplicationPath() {
        // Разделители путей нормализует хостовая файловая система: ожидание строится тем же Path API,
        // а проверяется сам обход до бандла, а не конкретные слэши.
        assertEquals(PermissionTarget("Magic Paper.app", Path.of("/Applications/Magic Paper.app").toString()),
            permissionHostTarget(Path.of("/Applications/Magic Paper.app/Contents/MacOS/MagicPaper")))
        assertEquals(PermissionTarget("Java / запуск из IDE", Path.of("/opt/jdk/bin/java").toString()),
            permissionHostTarget(Path.of("/opt/jdk/bin/java")))
        assertTrue(permissionSettingsUri(ComputerPermission.SCREEN_RECORDING).endsWith("?Privacy_ScreenCapture"))
    }
}
