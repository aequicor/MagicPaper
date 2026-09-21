package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted operation metadata. Prompt bytes stay in the original invocation, never in its journal. */
@Serializable data class MediaOperationData(
    val id: String,
    val owner: MediaGenerationOwner,
    val request: MediaGenerationRequest,
    val selection: MediaModelSelection,
    val fingerprint: String,
    val media: GeneratedMedia,
    val requestFingerprint: String = "",
    val jobId: String? = null,
    val output: MediaRemoteOutput? = null,
    val submitted: Boolean = false,
    val deleted: Boolean = false,
    val createdAt: Long,
)

/** One operation owns submission, observation and local download. Replaying inputs executes no effects. */
object MediaGenerationMachine : Machine<MediaGenerationMachine.State, MediaGenerationMachine.Input, MediaGenerationMachine.Effect> {
    override val id = MachineId("media-generation")
    override val space get() = MediaGenerationSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    enum class Stage { NEW, CREATED, SUBMITTING, WAITING, POLLING, OUTPUT_AVAILABLE, DOWNLOADING, READY, FAILED, UNKNOWN, DELETED }
    @ConsistentCopyVisibility data class State internal constructor(
        val operation: MediaOperationData? = null,
        val stage: Stage = Stage.NEW,
        val persistenceUnknown: Boolean = false,
    )
    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Create") data class Create(val operation: MediaOperationData) : Intent
        @Serializable @SerialName("Submit") data object Submit : Intent
        @Serializable @SerialName("Poll") data object Poll : Intent
        /** True only while the original interpreter still holds provider bytes in memory. */
        @Serializable @SerialName("Download") data class Download(val ephemeralOutput: Boolean = false) : Intent
        @Serializable @SerialName("Delete") data object Delete : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("Import") data class Import(val operation: MediaOperationData) : Fact
        @Serializable @SerialName("Accepted") data class Accepted(val jobId: String) : Fact
        @Serializable @SerialName("Pending") data object Pending : Fact
        @Serializable @SerialName("Output") data class Output(val output: MediaRemoteOutput) : Fact
        @Serializable @SerialName("Asset") data class Asset(val asset: MediaAsset) : Fact
        @Serializable @SerialName("Failure") data class Failure(val message: String, val confirmed: Boolean) : Fact
        @Serializable @SerialName("Restored") data object Restored : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect {
        data class Submit(val operationId: String) : Effect
        data class Poll(val jobId: String) : Effect
        data class Download(val output: MediaRemoteOutput) : Effect
        data class Reject(val reason: String) : Effect
    }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())
    fun initial() = State()
    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        fun invalid(operation: MediaOperationData) = operation.id.isBlank() || operation.requestFingerprint.isBlank() ||
            operation.request.prompt.isNotEmpty() || operation.output?.dataBase64?.isNotEmpty() == true ||
            operation.media.kind != operation.request.kind || operation.media.id != operation.owner.callId
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(stage = Stage.UNKNOWN, persistenceUnknown = true,
            operation = state.operation?.let { it.copy(media = it.media.copy(phase = MediaPhase.UNKNOWN,
                message = "Состояние генерации не удалось подтвердить. Повторная отправка отключена.")) }))
        if (state.persistenceUnknown) return if (input == Fact.Restored) Transition(state) else reject("Состояние генерации не подтверждено хранилищем")
        if (input is Intent.Create || input is Fact.Import) {
            val operation = if (input is Intent.Create) input.operation else (input as Fact.Import).operation
            if (state.stage != Stage.NEW || invalid(operation)) return reject("Некорректная или повторная операция генерации")
            if (input is Intent.Create && (operation.submitted || operation.jobId != null || operation.output != null ||
                    operation.deleted || operation.media.phase != MediaPhase.PENDING)) return reject("Новая генерация уже содержит внешний исход")
            return Transition(State(operation, if (input is Intent.Create) Stage.CREATED else restoredStage(operation)))
        }
        val operation = state.operation ?: return if (input == Fact.Restored) Transition(state) else reject("Операция генерации не создана")
        fun next(stage: Stage, operation: MediaOperationData, effects: List<Effect> = emptyList()) = Transition(state.copy(operation = operation, stage = stage), effects)
        if (input == Fact.Restored) {
            // Inline bytes belonged to the old process. A known provider job can still be polled;
            // retaining an empty output marker would incorrectly skip that read-only recovery.
            val recoverable = if (operation.output?.url?.isBlank() == true) operation.copy(output = null) else operation
            val stage = restoredStage(recoverable)
            val updated = if (stage == Stage.UNKNOWN) recoverable.copy(media = recoverable.media.copy(phase = MediaPhase.UNKNOWN,
                message = "Исход отправки неизвестен. Автоматический повтор отключён.")) else recoverable
            return next(stage, updated)
        }
        if (input == Intent.Delete) return next(Stage.DELETED, operation.copy(deleted = true,
            request = operation.request.copy(caption = ""), output = null, media = operation.media.copy(caption = "", asset = null, message = "")))
        if (state.stage in setOf(Stage.READY, Stage.FAILED, Stage.DELETED)) return reject("Операция генерации уже завершена")
        return when (input) {
            Intent.Submit -> if (state.stage != Stage.CREATED || operation.submitted) reject("Повторная отправка генерации запрещена")
                else next(Stage.SUBMITTING, operation.copy(submitted = true, media = operation.media.copy(phase = MediaPhase.GENERATING)), listOf(Effect.Submit(operation.id)))
            is Fact.Accepted -> if (state.stage != Stage.SUBMITTING || input.jobId.isBlank()) reject("Неподтверждённый идентификатор задания")
                else next(Stage.WAITING, operation.copy(jobId = input.jobId))
            Intent.Poll -> if (operation.jobId == null || state.stage !in setOf(Stage.WAITING, Stage.UNKNOWN)) reject("Нет задания для проверки")
                else next(Stage.POLLING, operation, listOf(Effect.Poll(operation.jobId)))
            Fact.Pending -> if (state.stage != Stage.POLLING) reject("Ответ не принадлежит проверке задания") else next(Stage.WAITING, operation)
            is Fact.Output -> if (state.stage !in setOf(Stage.SUBMITTING, Stage.POLLING) || input.output.kind != operation.request.kind || input.output.dataBase64.isNotEmpty())
                reject("Некорректный результат провайдера") else next(Stage.OUTPUT_AVAILABLE, operation.copy(output = input.output))
            is Intent.Download -> if (state.stage !in setOf(Stage.OUTPUT_AVAILABLE, Stage.UNKNOWN) || operation.output == null ||
                operation.output.url.isBlank() && !input.ephemeralOutput) reject("Нет доступного результата для загрузки")
                else next(Stage.DOWNLOADING, operation.copy(media = operation.media.copy(phase = MediaPhase.DOWNLOADING)), listOf(Effect.Download(operation.output)))
            is Fact.Asset -> if (state.stage != Stage.DOWNLOADING || input.asset.id.isBlank()) reject("Файл не принадлежит текущей загрузке")
                else next(Stage.READY, operation.copy(output = null, media = operation.media.copy(phase = MediaPhase.READY, asset = input.asset,
                    width = input.asset.width.takeIf { it > 0 } ?: operation.media.width,
                    height = input.asset.height.takeIf { it > 0 } ?: operation.media.height,
                    durationSeconds = input.asset.durationSeconds ?: operation.media.durationSeconds, message = "")))
            is Fact.Failure -> if (state.stage == Stage.UNKNOWN && input.confirmed) reject("Неподтверждённый исход нельзя объявить отказом")
                else next(if (input.confirmed) Stage.FAILED else Stage.UNKNOWN, operation.copy(media = operation.media.copy(
                    phase = if (input.confirmed) MediaPhase.FAILED else MediaPhase.UNKNOWN, message = input.message)))
            is Intent.Create, is Fact.Import, Intent.Delete, Fact.Restored, Fact.PersistenceUnknown -> error("Handled above")
        }
    }
    private fun restoredStage(operation: MediaOperationData): Stage = when {
        operation.deleted -> Stage.DELETED
        operation.media.phase == MediaPhase.READY -> Stage.READY
        operation.media.phase in setOf(MediaPhase.FAILED, MediaPhase.CANCELLED) -> Stage.FAILED
        operation.output?.url?.isNotBlank() == true -> Stage.OUTPUT_AVAILABLE
        operation.jobId != null -> Stage.WAITING
        else -> Stage.UNKNOWN
    }
}
