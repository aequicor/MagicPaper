package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

internal data class SkillFormActionState(val busy: Boolean = false, val notice: String = "", val cleanupPending: Boolean = false, val cancellable: Boolean = false)

/** Explicit native actions survive composition; reset/deletion drains them before revoking drafts. */
internal class SkillFormAction(
    private val scope: CoroutineScope,
    private val available: () -> Boolean,
    private val component: String,
    val projectId: String?,
) {
    private val mutableState = MutableStateFlow(SkillFormActionState())
    val state: StateFlow<SkillFormActionState> = mutableState
    private var job: Job? = null
    private var pendingCleanup: (suspend () -> Boolean)? = null
    var notice: String
        get() = mutableState.value.notice
        set(value) { mutableState.value = mutableState.value.copy(notice = value) }
    fun launch(cancellable: Boolean = false, block: suspend () -> String) {
        if (pendingCleanup != null) return
        start(cancellable, block)
    }
    private fun start(cancellable: Boolean = false, block: suspend () -> String) {
        if (!available() || mutableState.value.busy) return
        mutableState.value = mutableState.value.copy(busy = true, cancellable = cancellable)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            AppLog.info(component, "action_started", fields())
            try {
                val result = block()
                notice = if (pendingCleanup == null) result else cleanupNotice
                AppLog.info(component, "action_completed", fields() + ("phase" to if (pendingCleanup == null) "complete" else "cleanup"))
            }
            catch (cancelled: CancellationException) { AppLog.info(component, "action_cancelled", fields()); throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { mutableState.value = mutableState.value.copy(busy = false, cancellable = false) }
        }
    }
    fun report(failure: Exception) {
        AppLog.error(component, "action_failed", failure, fields())
        notice = if (pendingCleanup != null) cleanupNotice else if (component == "ProjectSkillRollback") "Откат не выполнен: откройте новый предпросмотр." else "Операция не завершена. Проверьте поля и повторите действие."
    }
    /** The business mutation has committed. Retrying this fence never repeats that mutation. */
    suspend fun accepted(clearCapturedSession: suspend () -> Boolean): Boolean = try { clearCapturedSession() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: Exception) {
        pendingCleanup = clearCapturedSession
        mutableState.value = mutableState.value.copy(cleanupPending = true, notice = cleanupNotice)
        AppLog.error(component, "accepted_cleanup_failed", failure, fields() + ("phase" to "cleanup"))
        false
    }
    fun retryCleanup() {
        val cleanup = pendingCleanup ?: return
        start { cleanup(); pendingCleanup = null; mutableState.value = mutableState.value.copy(cleanupPending = false); "Черновик обновлён." }
    }
    suspend fun awaitIdle() {
        job?.join()
        pendingCleanup?.let { cleanup ->
            cleanup() // Flush/reset owner handles any failure; the accepted operation is never repeated.
            pendingCleanup = null; mutableState.value = mutableState.value.copy(cleanupPending = false, notice = "Черновик обновлён.")
        }
    }
    private val cleanupNotice = "Действие выполнено, но черновик ещё не очищен. Повторите очистку."
    fun cancel() { if (mutableState.value.cancellable) { job?.cancel(); notice = "Загрузка отменена." } }
    private fun fields() = projectId?.let { mapOf("projectId" to it) }.orEmpty()
    fun notice(@Suppress("UNUSED_PARAMETER") observed: SkillFormActionState): ReadWriteProperty<Any?, String> = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>) = notice
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) { notice = value }
    }
}
