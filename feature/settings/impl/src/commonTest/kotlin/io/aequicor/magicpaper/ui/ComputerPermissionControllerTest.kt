package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ComputerPermissionControllerTest {
    private class Port : ComputerPermissions {
        var failure = false
        var opens = 0
        var inspect: suspend () -> ComputerPermissionReport = { ComputerPermissionReport(PermissionPlatform.MACOS) }
        override suspend fun inspect(computer: ComputerAccess, application: ComputerAccess): ComputerPermissionReport {
            if (failure) error("private native diagnostic")
            return inspect()
        }
        override suspend fun openSettings(permission: ComputerPermission) { opens++; if (failure) error("private launch diagnostic") }
        override suspend fun reveal(target: PermissionTarget) = error("private file diagnostic")
    }

    @Test fun refreshNeverOpensSettingsAndFailuresStayVisibleAndRetryable() = runTest {
        val port = Port()
        val controller = ComputerPermissionController(port, backgroundScope)
        controller.refresh(ComputerAccess.CONTROL, ComputerAccess.OFF)
        assertTrue(controller.state.value.busy)
        runCurrent()
        val valid = controller.state.value.report
        assertNotNull(valid); assertEquals(0, port.opens)
        port.failure = true
        controller.refresh(ComputerAccess.CONTROL, ComputerAccess.OFF); runCurrent()
        assertEquals(valid, controller.state.value.report)
        assertFalse(controller.state.value.busy)
        assertNotNull(controller.state.value.error)
        assertFalse(controller.state.value.error!!.contains("private"))
        controller.open(ComputerPermission.ACCESSIBILITY); runCurrent()
        assertEquals(1, port.opens); assertNotNull(controller.state.value.error)
        port.failure = false
        controller.refresh(ComputerAccess.CONTROL, ComputerAccess.OFF); runCurrent()
        assertNull(controller.state.value.error)
    }

    @Test fun lateProbeCannotReplaceNewPolicyAndCancellationIsNotAnError() = runTest {
        val port = Port()
        val first = CompletableDeferred<Unit>()
        port.inspect = { withContext(NonCancellable) { first.await() }; ComputerPermissionReport(PermissionPlatform.MACOS) }
        val controller = ComputerPermissionController(port, backgroundScope)
        controller.refresh(ComputerAccess.CONTROL, ComputerAccess.OFF); runCurrent()
        port.inspect = { ComputerPermissionReport(PermissionPlatform.WINDOWS) }
        controller.refresh(ComputerAccess.OFF, ComputerAccess.OFF); runCurrent()
        first.complete(Unit); runCurrent()
        assertEquals(PermissionPlatform.WINDOWS, controller.state.value.report?.platform)
        assertNull(controller.state.value.error)
        assertFalse(controller.state.value.busy)
    }
}
