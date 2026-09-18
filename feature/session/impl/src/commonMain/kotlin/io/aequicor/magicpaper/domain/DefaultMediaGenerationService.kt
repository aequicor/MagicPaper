package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.MediaStore
import io.aequicor.magicpaper.data.storage.StorageException
import io.aequicor.magicpaper.domain.tools.ConfirmedToolFailure
import io.aequicor.magicpaper.domain.tools.RejectedToolCall
import io.aequicor.magicpaper.logging.AppLog
import io.aequicor.magicpaper.util.Id
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
private data class MediaOperation(
    val id: String,
    val owner: MediaGenerationOwner,
    val request: MediaGenerationRequest,
    val selection: MediaModelSelection,
    val fingerprint: String,
    val media: GeneratedMedia,
    val jobId: String? = null,
    val output: MediaRemoteOutput? = null,
    val submitted: Boolean = false,
    val deleted: Boolean = false,
    val createdAt: Long = Id.now(),
)

/** Durable submission belongs to the application; screen changes never launch or cancel it. */
class DefaultMediaGenerationService(
    private val settingsRepo: SettingsRepository,
    private val profileRepo: LlmProfileRepository,
    private val gateway: MediaGenerationGateway,
    private val mediaStore: MediaStore,
    private val usage: UsageLedger,
    private val applicationScope: CoroutineScope,
    private val ownerExists: suspend (MediaGenerationOwner) -> Boolean = { true },
    private val pollDelayMillis: Long = 2_000,
    private val waitTimeoutMillis: Long = 20 * 60 * 1_000,
) : MediaGenerationService {
    var onTerminal: suspend (MediaGenerationOwner, String, GeneratedMedia) -> Unit = { _, _, _ -> }
    var authorizeSubmission: suspend (MediaGenerationOwner, MediaKind) -> Boolean = { _, _ -> true }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutableState = MutableStateFlow(MediaKind.entries.associateWith { MediaConnectionStatus(it) })
    override val state: StateFlow<Map<MediaKind, MediaConnectionStatus>> = mutableState.asStateFlow()
    private val mutableOperations = MutableStateFlow<Map<String, GeneratedMedia>>(emptyMap())
    override val operations: StateFlow<Map<String, GeneratedMedia>> = mutableOperations.asStateFlow()
    private val locks = MutableStateFlow<Map<String, Mutex>>(emptyMap())
    private val deletedSessions = MutableStateFlow<Set<String>>(emptySet())
    private val recoveryJobs = mutableMapOf<String, Job>()
    private val recoveryLock = Mutex()
    private val pendingRuns = MutableStateFlow<Map<String, Deferred<Result<GeneratedMedia>>>>(emptyMap())
    private val lifecycleLock = Mutex()
    private val connectionLock = Mutex()
    private val accepting = MutableStateFlow(true)

    private suspend fun fingerprint(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): String =
        mediaStore.fingerprint(kind.name + "\n" + json.encodeToString(selection) + "\n" + profile.provider.name + "\n" +
            profile.baseUrl + "\n" + profile.apiKey + "\n" + profile.authType?.name + "\n" + profile.enabled)

    private suspend fun selected(kind: MediaKind): Pair<MediaModelSelection, LlmProfile>? {
        if (!mediaStore.available) return null
        val selection = settingsRepo.load().media.selection(kind) ?: return null
        val profile = profileRepo.load().firstOrNull { it.id == selection.profileId && it.enabled }
            ?: return null
        if (!validSelection(kind, selection, profile)) return null
        if (selection.modelId.isBlank() || selection.baseUrl.isBlank() && profile.baseUrl.isBlank()) return null
        return selection to profile
    }

    override suspend fun refreshAvailability() {
        for (kind in MediaKind.entries) {
            val current = selected(kind)
            val status = if (current == null) MediaConnectionStatus(kind, MediaAvailability.UNAVAILABLE,
                message = "Выберите и проверьте подключение") else {
                val fingerprint = fingerprint(kind, current.first, current.second)
                val active = mutableState.value.getValue(kind)
                if (active.fingerprint == fingerprint && active.availability == MediaAvailability.CHECKING) active
                else mediaStore.readRecord("connection-$fingerprint")?.let { json.decodeFromString<MediaConnectionStatus>(it) }
                    ?: MediaConnectionStatus(kind, fingerprint = fingerprint, message = "Проверьте подключение")
            }
            mutableState.update { it + (kind to status) }
        }
    }

    override suspend fun available(kind: MediaKind): Boolean {
        refreshAvailability()
        return state.value[kind]?.availability == MediaAvailability.AVAILABLE
    }

    override suspend fun check(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): MediaConnectionStatus {
        check(accepting.value) { "Приложение завершает работу" }
        val fingerprint = fingerprint(kind, selection, profile)
        if (!validSelection(kind, selection, profile)) return MediaConnectionStatus(kind, MediaAvailability.UNAVAILABLE,
            fingerprint, "Выберите совместимое API-подключение и тип генерации").also { refreshAvailability() }
        val request = mediaRequestDefaults(selection, MediaGenerationRequest(kind, "A simple blue circle on a plain white background, no text.",
            caption = "Проверка подключения", width = 0, height = 0, durationSeconds = 0))
        val id = Id.uuid()
        val operation = MediaOperation(id, MediaGenerationOwner("connection-check", id, id), request, selection, fingerprint,
            GeneratedMedia(id, kind, caption = request.caption, width = request.width, height = request.height,
                durationSeconds = request.durationSeconds.toDouble().takeIf { kind == MediaKind.VIDEO }))
        val checking = MediaConnectionStatus(kind, MediaAvailability.CHECKING, fingerprint, "Создаю пробный результат", preview = operation.media)
        connectionLock.withLock {
            mediaStore.writeRecord(connectionAttemptKey(kind), id)
            mutableState.update { it + (kind to checking) }
        }
        val status = try {
            val result = runOperation(operation, profile, allowSubmit = true) { preview ->
                mutableState.update { values ->
                    val active = values[kind]
                    if (active?.fingerprint == fingerprint && active.preview?.id == id)
                        values + (kind to active.copy(preview = preview)) else values
                }
            }
            checking.copy(availability = MediaAvailability.AVAILABLE, message = "Подключение проверено", preview = result, checkedAt = Id.now())
        } catch (cancelled: CancellationException) {
            mutableState.update { values ->
                val active = values[kind]
                if (active?.fingerprint == fingerprint && active.preview?.id == id && pendingRuns.value[id]?.isActive != true)
                    values + (kind to active.copy(availability = MediaAvailability.UNCHECKED, message = "Проверка прервана")) else values
            }
            throw cancelled
        } catch (failure: StorageException) {
            mutableState.update { values ->
                val active = values[kind]
                if (active?.fingerprint == fingerprint && active.preview?.id == id)
                    values + (kind to active.copy(availability = MediaAvailability.UNAVAILABLE,
                        message = "Не удалось сохранить проверку подключения")) else values
            }
            throw failure
        }
        catch (failure: Exception) {
            AppLog.error("media", "connection.check.failed", fields = mapOf("kind" to kind.name, "operationId" to id,
                "failure" to failure::class.simpleName.orEmpty()))
            checking.copy(availability = MediaAvailability.UNAVAILABLE, message = safeMessage(failure),
                preview = operations.value[id] ?: operation.media.copy(phase = MediaPhase.FAILED, message = safeMessage(failure)), checkedAt = Id.now())
        }
        publishCheckResult(operation, status)
        return status
    }

    private fun connectionAttemptKey(kind: MediaKind) = "connection-attempt-${kind.name.lowercase()}"

    private suspend fun publishCheckResult(operation: MediaOperation, status: MediaConnectionStatus) = connectionLock.withLock {
        val kind = operation.request.kind
        // A newer explicit probe owns the proof even when it uses exactly the same configuration.
        if (mediaStore.readRecord(connectionAttemptKey(kind)) != operation.id) return@withLock
        mediaStore.writeRecord("connection-${operation.fingerprint}", json.encodeToString(status))
        val current = selected(kind)
        if (current != null && fingerprint(kind, current.first, current.second) == operation.fingerprint)
            mutableState.update { it + (kind to status) }
        else refreshAvailability()
    }

    override suspend fun generate(owner: MediaGenerationOwner, operationId: String, request: MediaGenerationRequest,
        authorize: suspend () -> Unit,
        onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia = operationLock(operationId).withLock {
        if (!accepting.value) throw MediaUnavailable("Приложение завершает работу")
        load(operationId)?.let { old ->
            require(old.owner == owner && old.request == mediaRequestDefaults(old.selection, request)) { "Идентификатор генерации уже использован" }
            return@withLock resume(old, onUpdate)
        }
        require(request.prompt.isNotBlank()) { "Опишите изображение или видео" }
        val current = selected(request.kind) ?: throw MediaUnavailable()
        val fp = fingerprint(request.kind, current.first, current.second)
        if (!available(request.kind) || state.value[request.kind]?.fingerprint != fp) throw MediaUnavailable()
        if (!live(owner)) throw MediaUnavailable("Сессия больше недоступна")
        val effective = mediaRequestDefaults(current.first, request)
        val operation = MediaOperation(operationId, owner, effective, current.first, fp,
            GeneratedMedia(owner.callId, effective.kind, caption = effective.caption, width = effective.width, height = effective.height,
                durationSeconds = effective.durationSeconds.toDouble().takeIf { effective.kind == MediaKind.VIDEO }))
        runOperation(operation, current.second, allowSubmit = true, authorize = authorize, onUpdate = onUpdate)
    }

    override suspend fun recover(operationId: String, onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia? =
        operationLock(operationId).withLock {
            if (!accepting.value) throw MediaUnavailable("Приложение завершает работу")
            load(operationId)?.let { resume(it, onUpdate) }
        }

    override suspend fun recoverMedia(mediaId: String): GeneratedMedia? {
        val operation = mediaStore.records("operation-").values.asSequence().map { json.decodeFromString<MediaOperation>(it) }
            .firstOrNull { it.media.id == mediaId } ?: return null
        return recover(operation.id)
    }

    private suspend fun resume(operation: MediaOperation, onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia {
        if (operation.deleted || !live(operation.owner)) throw MediaUnavailable("Сессия больше недоступна")
        if (operation.media.phase == MediaPhase.READY) return operation.media.also {
            onTerminal(operation.owner, operation.id, it)
            onUpdate(it)
        }
        if (operation.media.phase == MediaPhase.FAILED) {
            onTerminal(operation.owner, operation.id, operation.media)
            throw MediaConfirmedFailure(operation.media.message)
        }
        if (operation.jobId == null && operation.output == null) throw MediaUnknownFailure()
        val profile = profileRepo.load().firstOrNull { it.id == operation.selection.profileId }
            ?: throw MediaUnknownFailure()
        return runOperation(operation, profile, allowSubmit = false, onUpdate = onUpdate)
    }

    private suspend fun runOperation(initial: MediaOperation, profile: LlmProfile, allowSubmit: Boolean,
        authorize: suspend () -> Unit = {},
        onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia {
        val observer = currentCoroutineContext()[Job]
        val pending = lifecycleLock.withLock {
            if (!accepting.value) throw MediaUnavailable("Приложение завершает работу")
            pendingRuns.value[initial.id] ?: applicationScope.async(start = CoroutineStart.LAZY) {
                val notify: suspend (GeneratedMedia) -> Unit = { media ->
                    if (observer?.isActive != false) {
                        try { onUpdate(media) }
                        catch (failure: Exception) {
                            currentCoroutineContext().ensureActive()
                            if (failure is CancellationException && observer?.isActive == false)
                                AppLog.debug("media", "observer.detached", mapOf("operationId" to initial.id))
                            else AppLog.error("media", "observer.update.failed", fields = mapOf("operationId" to initial.id,
                                "failure" to failure::class.simpleName.orEmpty(), "recovery" to "operation_state_available"))
                        }
                    }
                }
                try {
                    val result = withTimeout(waitTimeoutMillis) { runAttempt(initial, profile, allowSubmit, authorize, notify) }
                    if (allowSubmit && observer?.isActive == false) cleanup(initial, "receipt.reconcile") {
                        onTerminal(initial.owner, initial.id, result)
                    }
                    Result.success(result)
                } catch (timeout: TimeoutCancellationException) {
                    currentCoroutineContext().ensureActive()
                    Result.failure(MediaUnknownFailure())
                } catch (failure: Exception) {
                    if (allowSubmit && observer?.isActive == false) cleanup(initial, "receipt.reconcile") {
                        load(initial.id)?.takeIf { it.media.phase == MediaPhase.FAILED }?.let {
                            onTerminal(it.owner, it.id, it.media)
                        }
                    }
                    if (failure is CancellationException) throw failure
                    // The operation owns and publishes its failure; a detached awaiter must not cancel the application scope.
                    Result.failure(failure)
                }
            }.also { deferred ->
                pendingRuns.update { it + (initial.id to deferred) }
                deferred.invokeOnCompletion { pendingRuns.update { values -> if (values[initial.id] === deferred) values - initial.id else values } }
            }
        }
        pending.start()
        return pending.await().getOrThrow()
    }

    private suspend fun runAttempt(initial: MediaOperation, profile: LlmProfile, allowSubmit: Boolean,
        authorize: suspend () -> Unit,
        onUpdate: suspend (GeneratedMedia) -> Unit): GeneratedMedia {
        var operation = initial
        suspend fun publish(phase: MediaPhase, message: String = "") {
            operation = operation.copy(media = operation.media.copy(phase = phase, message = message))
            save(operation)
            if (live(operation.owner)) onUpdate(operation.media)
        }
        try {
            if (allowSubmit) {
                save(operation)
                publish(MediaPhase.GENERATING)
                // Persist the boundary before calling the provider. A crash here never grants resubmission.
                operation = operation.copy(submitted = true)
                save(operation)
                if (operation.owner.sessionId != "connection-check") {
                    val current = selected(operation.request.kind)
                    if (current == null || fingerprint(operation.request.kind, current.first, current.second) != operation.fingerprint ||
                        !live(operation.owner) || !authorizeSubmission(operation.owner, operation.request.kind))
                        throw MediaUnavailable("Подключение или разрешения сессии изменились до отправки")
                }
                currentCoroutineContext().ensureActive()
                try { authorize() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { throw MediaUnavailable("Разрешение на генерацию отозвано до отправки", failure) }
                when (val submission = gateway.submit(profile, operation.selection, operation.request)) {
                    is MediaSubmission.Accepted -> operation = operation.copy(jobId = submission.jobId)
                    is MediaSubmission.Completed -> operation = operation.copy(output = submission.output)
                }
                save(operation)
            }
            var failedPolls = 0
            while (operation.output == null) {
                val job = operation.jobId ?: throw MediaUnknownFailure()
                delay(pollDelayMillis)
                val result = try { gateway.poll(profile, operation.selection, job, operation.request.kind) }
                catch (failure: MediaGatewayException) {
                    if (failure.kind == MediaFailureKind.TRANSIENT && ++failedPolls < 4) {
                        AppLog.debug("media", "poll.retry", mapOf("operationId" to operation.id, "attempt" to failedPolls.toString()))
                        continue
                    }
                    throw failure
                }
                failedPolls = 0
                when (result) {
                    MediaPollResult.Pending -> Unit
                    is MediaPollResult.Completed -> { operation = operation.copy(output = result.output); save(operation) }
                    is MediaPollResult.Failed -> {
                        if (result.failure.outcomeUnknown || result.failure.kind in setOf(MediaFailureKind.TRANSIENT, MediaFailureKind.INVALID_RESPONSE))
                            throw result.failure
                        if (result.failure.kind in setOf(MediaFailureKind.AUTHENTICATION, MediaFailureKind.UNAVAILABLE))
                            invalidate(operation, safeMessage(result.failure))
                        throw MediaConfirmedFailure(safeMessage(result.failure))
                    }
                }
            }
            publish(MediaPhase.DOWNLOADING)
            val downloaded = gateway.download(checkNotNull(operation.output))
            val asset = mediaStore.put(downloaded.bytes, downloaded.mimeType,
                downloaded.width.takeIf { it > 0 } ?: operation.request.width,
                downloaded.height.takeIf { it > 0 } ?: operation.request.height,
                downloaded.durationSeconds ?: operation.media.durationSeconds)
            operation = operation.copy(media = operation.media.copy(phase = MediaPhase.READY, asset = asset,
                width = asset.width.takeIf { it > 0 } ?: operation.media.width,
                height = asset.height.takeIf { it > 0 } ?: operation.media.height,
                durationSeconds = asset.durationSeconds ?: operation.media.durationSeconds, message = ""), output = null)
            save(operation)
            recordUsage(operation, profile, completed = true)
            if (!allowSubmit) onTerminal(operation.owner, operation.id, operation.media)
            if (operation.owner.sessionId == "connection-check") {
                publishCheckResult(operation, MediaConnectionStatus(
                    operation.request.kind, MediaAvailability.AVAILABLE, operation.fingerprint, "Подключение проверено",
                    preview = operation.media, checkedAt = Id.now()))
            }
            if (!live(operation.owner)) throw MediaUnavailable("Сессия больше недоступна")
            onUpdate(operation.media)
            AppLog.info("media", "generation.completed", mapOf("operationId" to operation.id, "kind" to operation.request.kind.name))
            return operation.media
        } catch (failure: Exception) {
            // A known accepted job is reconciled by polling. Cancellation never cancels it by assumption.
            val confirmed = failure is MediaConfirmedFailure || failure is RejectedToolCall || failure is MediaGatewayException &&
                operation.jobId == null && operation.output == null && !failure.outcomeUnknown &&
                failure.kind in setOf(MediaFailureKind.VALIDATION, MediaFailureKind.AUTHENTICATION, MediaFailureKind.UNAVAILABLE,
                    MediaFailureKind.REJECTED, MediaFailureKind.TRANSIENT)
            val message = safeMessage(failure)
            if (operation.media.phase != MediaPhase.READY) withContext(NonCancellable) {
                operation = operation.copy(media = operation.media.copy(phase = if (confirmed) MediaPhase.FAILED else MediaPhase.UNKNOWN, message = message))
                cleanup(operation, "outcome.state") {
                    if (live(operation.owner)) mutableOperations.update { it + (operation.media.id to operation.media) }
                }
                cleanup(operation, "outcome.save") { save(operation) }
                cleanup(operation, "outcome.publish") { if (live(operation.owner)) onUpdate(operation.media) }
                cleanup(operation, "usage.record") { recordUsage(operation, profile, completed = false) }
                if (!allowSubmit && confirmed) cleanup(operation, "receipt.reconcile") { onTerminal(operation.owner, operation.id, operation.media) }
                if (operation.owner.sessionId == "connection-check") cleanup(operation, "connection.check.outcome") {
                    publishCheckResult(operation, MediaConnectionStatus(operation.request.kind, MediaAvailability.UNAVAILABLE,
                        operation.fingerprint, message, preview = operation.media, checkedAt = Id.now()))
                }
            }
            if (failure is MediaGatewayException && (failure.kind in setOf(MediaFailureKind.AUTHENTICATION, MediaFailureKind.UNAVAILABLE) ||
                    failure.outcomeUnknown && operation.jobId == null && operation.output == null))
                withContext(NonCancellable) { cleanup(operation, "connection.invalidate") { invalidate(operation, message) } }
            AppLog.error("media", "generation.failed", fields = mapOf("operationId" to operation.id,
                "kind" to operation.request.kind.name, "outcome" to if (confirmed) "failed" else "unknown"))
            if (failure is CancellationException) throw failure
            if (confirmed && failure !is MediaConfirmedFailure) throw MediaConfirmedFailure(message)
            throw failure
        }
    }

    private suspend fun cleanup(operation: MediaOperation, event: String, action: suspend () -> Unit) {
        try { action() }
        catch (failure: Exception) {
            AppLog.error("media", event + ".failed", fields = mapOf("operationId" to operation.id,
                "failure" to failure::class.simpleName.orEmpty()))
        }
    }

    private suspend fun recordUsage(operation: MediaOperation, profile: LlmProfile, completed: Boolean) = usage.record(
        UsageRecord("media:${operation.id}", createdAt = operation.createdAt,
            scope = if (operation.owner.sessionId == "connection-check") UsageScope() else if (operation.owner.projectId == null)
                UsageScope.chat(operation.owner.sessionId) else UsageScope("coding:${operation.owner.sessionId}", projectId = operation.owner.projectId),
            kind = if (operation.request.kind == MediaKind.IMAGE) UsageKind.IMAGE else UsageKind.VIDEO,
            provider = profile.provider.name, model = operation.selection.modelId, completed = completed,
            generatedImages = if (completed && operation.request.kind == MediaKind.IMAGE) 1 else null,
            generatedVideoSeconds = if (completed && operation.request.kind == MediaKind.VIDEO)
                operation.media.asset?.durationSeconds ?: operation.request.durationSeconds.toDouble() else null),
        replacesId = "media:${operation.id}")

    private suspend fun invalidate(operation: MediaOperation, message: String) {
        val status = MediaConnectionStatus(operation.request.kind, MediaAvailability.UNAVAILABLE, operation.fingerprint, message,
            preview = operation.media.takeIf { operation.owner.sessionId == "connection-check" }, checkedAt = Id.now())
        if (operation.owner.sessionId == "connection-check") {
            publishCheckResult(operation, status)
            return
        }
        mediaStore.writeRecord("connection-${operation.fingerprint}", json.encodeToString(status))
        if (state.value[operation.request.kind]?.fingerprint == operation.fingerprint) mutableState.update { it + (operation.request.kind to status) }
    }

    override suspend fun recoverPending() {
        if (!mediaStore.available || !accepting.value) return
        for ((_, value) in mediaStore.records("operation-")) {
            val operation = json.decodeFromString<MediaOperation>(value)
            if (!operation.deleted && live(operation.owner)) mutableOperations.update { it + (operation.media.id to operation.media) }
            if (operation.deleted) continue
            if (operation.media.phase in setOf(MediaPhase.READY, MediaPhase.FAILED, MediaPhase.CANCELLED)) {
                if (live(operation.owner)) cleanup(operation, "receipt.reconcile") { onTerminal(operation.owner, operation.id, operation.media) }
                continue
            }
            if (operation.jobId == null && operation.output == null) {
                save(operation.copy(media = operation.media.copy(phase = MediaPhase.UNKNOWN,
                    message = "Исход отправки неизвестен. Автоматический повтор отключён.")))
                continue
            }
            recoveryLock.withLock {
                if (recoveryJobs[operation.id]?.isActive == true) return@withLock
                recoveryJobs[operation.id] = applicationScope.launch {
                    try { recover(operation.id) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { AppLog.error("media", "recovery.failed", fields = mapOf("operationId" to operation.id,
                        "failure" to failure::class.simpleName.orEmpty())) }
                }
            }
        }
    }

    override suspend fun deleteSession(sessionId: String) {
        deletedSessions.update { it + sessionId }
        for ((_, value) in mediaStore.records("operation-")) {
            val operation = json.decodeFromString<MediaOperation>(value)
            if (operation.owner.sessionId == sessionId) {
                pendingRuns.value[operation.id]?.cancelAndJoin()
                operationLock(operation.id).withLock { load(operation.id)?.let { save(it.copy(deleted = true)) } }
                mutableOperations.update { it - operation.media.id }
            }
        }
    }
    private suspend fun live(owner: MediaGenerationOwner) = owner.sessionId == "connection-check" ||
        owner.sessionId !in deletedSessions.value && ownerExists(owner)
    private fun operationLock(id: String): Mutex {
        locks.update { if (id in it) it else it + (id to Mutex()) }
        return locks.value.getValue(id)
    }
    private suspend fun key(id: String) = "operation-${mediaStore.fingerprint(id)}"
    private suspend fun load(id: String): MediaOperation? = mediaStore.readRecord(key(id))?.let { json.decodeFromString(it) }
    private suspend fun save(operation: MediaOperation) {
        val saved = if (operation.deleted || operation.owner.sessionId in deletedSessions.value) operation.copy(deleted = true,
            request = operation.request.copy(prompt = "", caption = ""), output = null,
            media = operation.media.copy(caption = "", asset = null, message = "")) else operation
        // Inline provider bytes are immediately downloaded into the binary store, never copied into metadata JSON.
        val persisted = if (saved.output?.dataBase64?.isNotEmpty() == true) saved.copy(output = null) else saved
        mediaStore.writeRecord(key(saved.id), json.encodeToString(persisted))
        if (!saved.deleted && live(saved.owner)) mutableOperations.update { it + (saved.media.id to saved.media) }
    }
    override suspend fun read(asset: MediaAsset): ByteArray = mediaStore.read(asset)
    override suspend fun localPath(asset: MediaAsset): String? = mediaStore.localPath(asset)
    override suspend fun prepareForReset() {
        val jobs = lifecycleLock.withLock {
            accepting.value = false
            pendingRuns.value.values.toList()
        }
        val current = currentCoroutineContext()[Job]
        jobs.filter { it !== current }.forEach { it.cancel() }
        jobs.filter { it !== current }.joinAll()
        recoveryLock.withLock {
            recoveryJobs.values.forEach { it.cancel() }
            recoveryJobs.values.toList().joinAll()
            recoveryJobs.clear()
        }
    }
    override suspend fun resumeAfterReset() {
        mutableOperations.value = emptyMap()
        deletedSessions.value = emptySet()
        accepting.value = true
        refreshAvailability()
    }
    override suspend fun close() = prepareForReset()
    private fun validSelection(kind: MediaKind, selection: MediaModelSelection, profile: LlmProfile): Boolean =
        profile.provider != ProviderType.OPENAI_SUBSCRIPTION && profile.id == selection.profileId &&
            if (kind == MediaKind.VIDEO) selection.protocol == MediaProtocol.DASHSCOPE_VIDEO
            else selection.protocol in setOf(MediaProtocol.OPENAI_IMAGES, MediaProtocol.DASHSCOPE_IMAGE)
    private fun safeMessage(failure: Throwable): String = when (failure) {
        is MediaConfirmedFailure -> failure.message ?: "Провайдер не создал результат"
        is MediaGatewayException -> when (failure.kind) {
            MediaFailureKind.AUTHENTICATION -> "Проверьте ключ API в настройках подключения"
            MediaFailureKind.VALIDATION -> "Проверьте модель и параметры генерации"
            MediaFailureKind.REJECTED -> "Провайдер отклонил генерацию. Измените запрос и повторите попытку"
            MediaFailureKind.UNAVAILABLE -> "Модель генерации недоступна. Проверьте подключение"
            MediaFailureKind.TRANSIENT -> "Провайдер временно занят. Повторите попытку позже"
            else -> "Не удалось получить результат. Сохранённая операция будет проверена без повторной генерации"
        }
        else -> "Не удалось получить результат. Сохранённая операция будет проверена без повторной генерации"
    }
}

private class MediaUnavailable(message: String = "Создание медиа недоступно. Проверьте подключение в настройках", cause: Throwable? = null) :
    IllegalStateException(message, cause), RejectedToolCall
private class MediaConfirmedFailure(message: String) : IllegalStateException(message), ConfirmedToolFailure
private class MediaUnknownFailure : IllegalStateException("Исход генерации неизвестен; повторная отправка не выполнена")

/** Zero means the caller left the option to the selected model's documented default. */
internal fun mediaRequestDefaults(selection: MediaModelSelection, request: MediaGenerationRequest): MediaGenerationRequest {
    val imageSize = if (selection.protocol == MediaProtocol.DASHSCOPE_IMAGE &&
        (selection.modelId == "qwen-image" || selection.modelId.startsWith("qwen-image-plus") || selection.modelId.startsWith("qwen-image-max"))) 1328 else 1024
    val width = if (request.kind == MediaKind.VIDEO) 1280 else imageSize
    val height = if (request.kind == MediaKind.VIDEO) 720 else imageSize
    return request.copy(width = request.width.takeIf { it > 0 } ?: width,
        height = request.height.takeIf { it > 0 } ?: height,
        durationSeconds = request.durationSeconds.takeIf { it > 0 } ?: selection.minimumProbeVideoDurationSeconds())
}
