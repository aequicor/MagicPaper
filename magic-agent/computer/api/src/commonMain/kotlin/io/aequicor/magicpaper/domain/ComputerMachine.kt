package io.aequicor.magicpaper.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Authority and native-call outcomes have one owner. Pixels, typed text and native references stay ephemeral. */
object ComputerMachine {
    @Serializable enum class Tool { DESKTOP, APPLICATION }
    @Serializable enum class Action(val tool: Tool, val wireName: String, val mutating: Boolean = false) {
        DISPLAYS(Tool.DESKTOP, "displays"), SCREENSHOT(Tool.DESKTOP, "screenshot"), WAIT(Tool.DESKTOP, "wait"),
        CLICK(Tool.DESKTOP, "click", true), DOUBLE_CLICK(Tool.DESKTOP, "double_click", true), MOVE(Tool.DESKTOP, "move", true),
        DRAG(Tool.DESKTOP, "drag", true), SCROLL(Tool.DESKTOP, "scroll", true), TYPE(Tool.DESKTOP, "type", true), KEY(Tool.DESKTOP, "key", true),
        WINDOWS(Tool.APPLICATION, "windows"), INSPECT(Tool.APPLICATION, "inspect"), WINDOW_SCREENSHOT(Tool.APPLICATION, "screenshot"),
        INVOKE(Tool.APPLICATION, "invoke", true), SET_VALUE(Tool.APPLICATION, "set_value", true),
        INCREMENT(Tool.APPLICATION, "increment", true), DECREMENT(Tool.APPLICATION, "decrement", true),
    }
    @Serializable enum class Outcome { PENDING, SUCCEEDED, FAILED, UNKNOWN, CANCELLED }
    @Serializable data class Request(val sessionId: String, val id: String)
    @Serializable data class Grant(val lease: ComputerLease, val desktop: ComputerAccess, val application: ComputerAccess,
        val request: Request? = null)
    @Serializable data class PermissionAttempt(val lease: ComputerLease, val access: ComputerAccess)
    @Serializable data class Reference(val id: String, val tool: Tool, val capturedNanos: Long)
    @Serializable data class Invocation(val lease: ComputerLease, val request: Request?, val callId: String,
        val action: Action, val fingerprint: String, val referenceId: String? = null, val atNanos: Long)
    @Serializable data class Call(val invocation: Invocation, val outcome: Outcome, val consumedReference: Reference? = null)

    @ConsistentCopyVisibility
    data class State internal constructor(
        val desktopPolicy: ComputerAccess = ComputerAccess.OFF,
        val applicationPolicy: ComputerAccess = ComputerAccess.OFF,
        val generation: Long = 0,
        val incarnation: String = "",
        val policyRevision: Long = 0,
        val policyConfirmed: Boolean = false,
        val grant: Grant? = null,
        val permission: PermissionAttempt? = null,
        val pending: String? = null,
        val calls: Map<String, Call> = emptyMap(),
        val closedRequests: Set<Request> = emptySet(),
        val references: Map<Tool, Reference> = emptyMap(),
        val observationRequired: Set<Tool> = emptySet(),
        val persistenceUnknown: Boolean = false,
        val resourceFailure: Boolean = false,
    )
    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("ConfigurePolicy") data class Configure(val desktop: ComputerAccess, val application: ComputerAccess) : Intent
        @Serializable @SerialName("SuspendPolicy") data object SuspendPolicy : Intent
        @Serializable @SerialName("ConditionalEnable") data class Enable(val sessionId: String, val access: ComputerAccess,
            val expectedPolicy: ComputerPolicyRef) : Intent
        @Serializable @SerialName("Begin") data class Begin(val request: Request) : Intent
        @Serializable @SerialName("Revoke") data class Revoke(val lease: ComputerLease? = null) : Intent
        @Serializable @SerialName("Execute") data class Execute(val invocation: Invocation) : Intent
    }
    /** Historical accepted inputs only. The live reducer rejects them without effects. */
    @Serializable sealed interface LegacyInput : Intent {
        @Serializable @SerialName("Configure") data class Configure(val desktop: ComputerAccess, val application: ComputerAccess) : LegacyInput
        @Serializable @SerialName("Enable") data class Enable(val sessionId: String, val access: ComputerAccess) : LegacyInput
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("PermissionsChecked") data class PermissionsChecked(val lease: ComputerLease, val granted: Boolean) : Fact
        @Serializable @SerialName("Returned") data class Returned(val lease: ComputerLease, val callId: String,
            val reference: Reference? = null) : Fact
        @Serializable @SerialName("Failed") data class Failed(val lease: ComputerLease, val callId: String, val unknown: Boolean) : Fact
        @Serializable @SerialName("Restored") data class Restored(val incarnation: String) : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
        @Serializable @SerialName("ReleaseFailed") data object ReleaseFailed : Fact
    }
    sealed interface Effect {
        data class CheckPermissions(val lease: ComputerLease, val access: ComputerAccess) : Effect
        data class Invoke(val invocation: Invocation) : Effect
        /** Cleanup may run immediately on revocation; it cannot grant authority or send input. */
        data class Release(val lease: ComputerLease) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial() = State()
    fun policyRef(state: State): ComputerPolicyRef? = state.incarnation.takeIf {
        it.isNotBlank() && state.policyConfirmed && !state.persistenceUnknown && !state.resourceFailure
    }
        ?.let { ComputerPolicyRef(it, state.policyRevision) }

    fun reduce(state: State, input: Input): Transition = transition(state, input, replay = false)

    /** The interpreter restores accepted history with this rule and never executes its effects. */
    fun replay(state: State, input: Input): Transition = transition(state, input, replay = true)

    private fun transition(state: State, input: Input, replay: Boolean): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input is LegacyInput && !replay) return reject("Устаревшая команда доступа недоступна для выполнения")
        fun revoked(source: State): State {
            val pending = source.pending?.let(source.calls::get)
            val calls = if (pending == null) source.calls else source.calls + (pending.invocation.callId to pending.copy(
                outcome = if (pending.invocation.action.mutating) Outcome.UNKNOWN else Outcome.CANCELLED))
            return source.copy(generation = source.generation + 1, grant = null, permission = null, pending = null, calls = calls,
                closedRequests = source.closedRequests + listOfNotNull(source.grant?.request), references = emptyMap(), observationRequired = emptySet())
        }
        fun release(source: State, next: State): Transition = Transition(next,
            listOfNotNull((source.grant?.lease ?: source.permission?.lease)?.let(Effect::Release)))

        if (input is Fact.Restored) return if (input.incarnation.isBlank() || input.incarnation == state.incarnation)
            reject("Некорректное поколение владельца доступа") else Transition(revoked(state).copy(incarnation = input.incarnation,
                policyRevision = state.policyRevision + 1, policyConfirmed = false, resourceFailure = false))
        // Restore returns no cleanup or OS effects; a fresh incarnation also fences endpoints after a data reset.
        if (input == Fact.PersistenceUnknown) return release(state, revoked(state).copy(persistenceUnknown = true))
        if (input == Fact.ReleaseFailed) return Transition(revoked(state).copy(resourceFailure = true))
        if (input is Intent.Revoke) {
            val lease = state.grant?.lease ?: state.permission?.lease
            if (input.lease != null && input.lease != lease) return Transition(state)
            return release(state, revoked(state))
        }
        if (input is Intent.Configure) {
            val next = state.copy(desktopPolicy = input.desktop, applicationPolicy = input.application,
                policyRevision = state.policyRevision + 1, policyConfirmed = true)
            return if (state.desktopPolicy != input.desktop || state.applicationPolicy != input.application ||
                state.grant != null || state.permission != null) release(state, revoked(next)) else Transition(next)
        }
        if (input == Intent.SuspendPolicy) return release(state, revoked(state).copy(desktopPolicy = ComputerAccess.OFF,
            applicationPolicy = ComputerAccess.OFF, policyRevision = state.policyRevision + 1, policyConfirmed = false))
        if (input is LegacyInput.Configure) {
            // Preserve the original lease generations for historical PermissionsChecked inputs.
            val next = state.copy(desktopPolicy = input.desktop, applicationPolicy = input.application)
            return if (state.desktopPolicy != input.desktop || state.applicationPolicy != input.application ||
                (input.desktop == ComputerAccess.OFF && input.application == ComputerAccess.OFF && state.grant != null))
                release(state, revoked(next)) else Transition(next)
        }
        if (state.incarnation.isBlank()) return reject("Владелец доступа ещё не восстановлен")
        if (state.resourceFailure) return reject("Не удалось полностью отключить управление. Перезапустите приложение.")
        if (state.persistenceUnknown) return reject("Сохранение состояния доступа не подтверждено. Новые действия заблокированы.")
        fun enable(sessionId: String, access: ComputerAccess): Transition = when {
                sessionId.isBlank() || access == ComputerAccess.OFF -> reject("Некорректный запрос доступа")
                state.grant?.lease?.sessionId?.let { it != sessionId } == true || state.permission != null -> reject("Доступ уже принадлежит другому запросу")
                state.grant?.request != null -> reject("Сначала остановите текущий запрос")
                else -> {
                    val lease = ComputerLease(sessionId, "${state.incarnation}:${state.generation + 1}")
                    Transition(state.copy(generation = state.generation + 1, grant = null,
                        permission = PermissionAttempt(lease, access), references = emptyMap()),
                        listOfNotNull(state.grant?.lease?.let(Effect::Release)) + Effect.CheckPermissions(lease, access))
                }
            }
        return when (input) {
            is Intent.Enable -> if (!state.policyConfirmed || input.expectedPolicy != policyRef(state))
                reject("Настройки управления изменились. Повторите включение доступа.") else enable(input.sessionId, input.access)
            is LegacyInput.Enable -> enable(input.sessionId, input.access)
            is Fact.PermissionsChecked -> {
                val attempt = state.permission
                if (attempt?.lease != input.lease) reject("Проверка доступа устарела")
                else Transition(state.copy(permission = null, grant = if (input.granted) Grant(input.lease, attempt.access, ComputerAccess.OFF) else null))
            }
            is Intent.Begin -> when {
                input.request.sessionId.isBlank() || input.request.id.isBlank() -> reject("Отсутствует идентификатор запроса")
                input.request in state.closedRequests -> reject("Доступ завершённого запроса не восстанавливается")
                state.permission != null || state.pending != null -> reject("Предыдущее действие ещё выполняется")
                state.grant?.lease?.sessionId?.let { it != input.request.sessionId } == true -> reject("Доступ принадлежит другой сессии")
                state.grant?.request?.let { it != input.request } == true -> reject("Доступ принадлежит другому запросу")
                state.grant?.request == input.request -> Transition(state)
                state.grant == null && state.desktopPolicy == ComputerAccess.OFF && state.applicationPolicy == ComputerAccess.OFF -> reject("Доступ выключен")
                else -> {
                    val grant = state.grant ?: Grant(ComputerLease(input.request.sessionId, "${state.incarnation}:${state.generation + 1}"),
                        state.desktopPolicy, state.applicationPolicy)
                    Transition(state.copy(generation = if (state.grant == null) state.generation + 1 else state.generation,
                        grant = grant.copy(request = input.request), calls = emptyMap(), references = emptyMap(), observationRequired = emptySet()))
                }
            }
            is Intent.Execute -> {
                val call = input.invocation
                val grant = state.grant
                val access = if (call.action.tool == Tool.DESKTOP) grant?.desktop else grant?.application
                val reference = state.references[call.action.tool]
                when {
                    call.callId.isBlank() || call.fingerprint.isBlank() -> reject("Отсутствует идентификатор действия")
                    grant?.lease != call.lease || grant?.request != call.request -> reject("Доступ запроса отозван")
                    access == ComputerAccess.OFF || (call.action.mutating && access != ComputerAccess.CONTROL) -> reject("Действие не разрешено")
                    state.pending != null -> reject("Предыдущее действие ещё выполняется")
                    call.callId in state.calls -> reject("Идентификатор действия уже использован. Повтор не выполняется.")
                    call.action.mutating && call.action.tool in state.observationRequired -> reject("Исход действия неизвестен. Сначала проверьте состояние.")
                    (call.action.mutating || call.referenceId != null) && (reference == null || reference.id != call.referenceId ||
                        call.atNanos < reference.capturedNanos || call.atNanos - reference.capturedNanos > REFERENCE_LIFETIME_NANOS) -> reject("Состояние устарело. Получите новое наблюдение.")
                    else -> Transition(state.copy(pending = call.callId, calls = state.calls + (call.callId to Call(call, Outcome.PENDING, reference)),
                        references = if (call.action.mutating || call.action == Action.WINDOWS) state.references - call.action.tool else state.references),
                        listOf(Effect.Invoke(call)))
                }
            }
            is Fact.Returned -> {
                val call = state.calls[input.callId]
                val ref = input.reference
                when {
                    call == null || state.pending != input.callId || state.grant?.lease != input.lease || call.invocation.lease != input.lease -> reject("Результат действия устарел")
                    ref != null && (ref.id.isBlank() || ref.tool != call.invocation.action.tool || ref.capturedNanos < call.invocation.atNanos ||
                        (ref.tool == Tool.APPLICATION && call.invocation.action !in APPLICATION_REFERENCES)) -> reject("Наблюдение не подтверждает доступ к действию")
                    else -> Transition(state.copy(pending = null, calls = state.calls + (input.callId to call.copy(outcome = Outcome.SUCCEEDED)),
                        references = if (ref != null) state.references + (ref.tool to ref) else state.references,
                        observationRequired = if (ref != null) state.observationRequired - ref.tool else state.observationRequired))
                }
            }
            is Fact.Failed -> {
                val call = state.calls[input.callId]
                if (call == null || state.pending != input.callId || state.grant?.lease != input.lease || call.invocation.lease != input.lease) reject("Результат действия устарел")
                else Transition(state.copy(pending = null, calls = state.calls + (input.callId to call.copy(outcome = if (input.unknown) Outcome.UNKNOWN else Outcome.FAILED)),
                    references = if (!input.unknown && call.consumedReference != null) state.references + (call.invocation.action.tool to call.consumedReference)
                        else state.references - call.invocation.action.tool,
                    observationRequired = if (input.unknown && call.invocation.action.mutating) state.observationRequired + call.invocation.action.tool else state.observationRequired))
            }
            is Intent.Configure, Intent.SuspendPolicy, is Intent.Revoke, is LegacyInput.Configure,
            is Fact.Restored, Fact.PersistenceUnknown, Fact.ReleaseFailed -> error("Handled above")
        }
    }
    private const val REFERENCE_LIFETIME_NANOS = 30_000_000_000L
    private val APPLICATION_REFERENCES = setOf(Action.INSPECT, Action.INVOKE, Action.SET_VALUE, Action.INCREMENT, Action.DECREMENT)
}
