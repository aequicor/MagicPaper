package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class ComputerPermissionUi(
    val report: ComputerPermissionReport? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

/** Screen lifetime only. No persistence or automation authority; repeated refreshes supersede old probes. */
internal class ComputerPermissionController(private val port: ComputerPermissions?, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(ComputerPermissionUi())
    val state = mutableState.asStateFlow()
    private var probe: Job? = null
    private var generation = 0L

    fun refresh(computer: ComputerAccess, application: ComputerAccess) {
        val version = ++generation
        probe?.cancel()
        mutableState.value = state.value.copy(busy = true, error = null)
        probe = scope.launch {
            try {
                AppLog.info("computer.permissions", "inspect.started", mapOf("computer" to computer.name, "application" to application.name))
                val report = port?.inspect(computer, application) ?: ComputerPermissionReport()
                if (version == generation) {
                    AppLog.info("computer.permissions", "inspect.completed", mapOf("platform" to report.platform.name,
                        "required" to report.checks.size.toString(), "granted" to report.checks.count { it.granted }.toString()))
                    mutableState.value = ComputerPermissionUi(report)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reportFailure("inspect", failure)
                if (version == generation) {
                    mutableState.value = state.value.copy(busy = false, error = "Не удалось проверить разрешения. Повторите проверку.")
                }
            }
        }
    }

    fun open(permission: ComputerPermission) = action("open_settings") { checkNotNull(port).openSettings(permission) }
    fun reveal(target: PermissionTarget) = action("reveal_target") { checkNotNull(port).reveal(target) }

    private fun action(operation: String, block: suspend () -> Unit) {
        if (state.value.busy) return
        val version = ++generation
        probe?.cancel()
        mutableState.value = state.value.copy(busy = true, error = null)
        probe = scope.launch {
            try {
                AppLog.info("computer.permissions", operation)
                block()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                reportFailure(operation, failure)
                if (version == generation) mutableState.value = state.value.copy(error = "Не удалось открыть системное окно. Откройте «Конфиденциальность и безопасность» вручную или повторите попытку.")
            } finally { if (version == generation) mutableState.value = state.value.copy(busy = false) }
        }
    }

    private fun reportFailure(operation: String, failure: Exception) = AppLog.error("computer.permissions", "$operation.failed",
        fields = mapOf("causeType" to (failure::class.simpleName ?: "Exception")))
}
