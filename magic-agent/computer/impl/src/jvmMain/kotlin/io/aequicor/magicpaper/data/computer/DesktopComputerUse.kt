package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.util.Id
import io.aequicor.magicpaper.logging.AppLog
import java.awt.Desktop
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

class DesktopComputerUse internal constructor(
    private val desktop: ComputerDesktop,
    private val journal: EventJournal,
    private val applicationFactory: () -> ApplicationDesktop = { NativeApplicationDesktop() },
    private val clock: () -> Long = System::nanoTime,
) : NativeComputerUse {
    constructor(journal: EventJournal) : this(AwtComputerDesktop(), journal)
    private val mutableState = MutableStateFlow(ComputerUseState())
    override val state = mutableState.asStateFlow()
    override val supported get() = desktop.supported
    override val applicationSupported get() = NativeApplicationDesktop.supported
    override val permissions: ComputerPermissions by lazy { DesktopComputerPermissions() }
    private val lock = Any()
    private val operations = Mutex()
    private var frame: Frame? = null
    private var application: ApplicationUse? = null
    private val failedApplications = mutableListOf<ApplicationUse>()
    private var resourceLease: ComputerLease? = null
    @Volatile private var resetting = false
    @Volatile private lateinit var authority: ComputerAuthority
    init { authority = ComputerAuthority(journal, ::authorityChanged) }

    private fun authorityChanged(value: ComputerMachine.State) = synchronized(lock) {
        // Publication can race revocation or a completed reset; stale projections cannot own resources.
        if (!this::authority.isInitialized || authority.state !== value) return@synchronized
        val lease = value.grant?.lease ?: value.permission?.lease
        if (resourceLease != lease) {
            val previous = resourceLease
            val previousApplication = application
            frame = null
            application = null
            resourceLease = lease
            var failure: Throwable? = null
            try { previousApplication?.close() } catch (cleanup: Throwable) {
                if (previousApplication != null) failedApplications += previousApplication
                failure = cleanup
            }
            try { if (previous != null) desktop.close() } catch (cleanup: Throwable) {
                failure = combineComputerCleanup(failure, cleanup)
            }
            if (failure != null) {
                resourceLease = null
                authority.releaseFailed()
                AppLog.error("computer", "resource_cleanup_failed", mapOf("causeType" to failure::class.simpleName.orEmpty()))
                mutableState.value = ComputerUseState(error = true, detail = "Не удалось полностью отключить управление. Перезапустите приложение.")
                if (failure is kotlinx.coroutines.CancellationException) throw failure
                return@synchronized
            }
            mutableState.value = ComputerUseState()
        }
        val grant = value.grant
        mutableState.value = when {
            value.resourceFailure -> ComputerUseState(error = true, detail = "Не удалось полностью отключить управление. Перезапустите приложение.")
            value.persistenceUnknown -> ComputerUseState(error = true, detail = "Не удалось подтвердить сохранение доступа. Новые действия отключены.")
            grant != null -> mutableState.value.copy(sessionId = grant.lease.sessionId, access = grant.desktop,
                applicationAccess = grant.application, busy = value.pending != null)
            value.permission != null -> ComputerUseState(sessionId = checkNotNull(value.permission).lease.sessionId, busy = true)
            else -> ComputerUseState()
        }
    }

    override fun configure(computer: ComputerAccess, application: ComputerAccess) {
        if (resetting) return
        authority.enqueue(ComputerMachine.Intent.Configure(computer, application))
        AppLog.info("computer", "policy.changed", mapOf("computer" to computer.name, "application" to application.name))
    }

    override fun capturePolicy(): ComputerPolicyRef? = if (resetting) null else ComputerMachine.policyRef(authority.state)

    override fun invalidatePolicy() {
        if (resetting) return
        authority.enqueue(ComputerMachine.Intent.SuspendPolicy)
    }

    override suspend fun begin(sessionId: String, requestId: String): ComputerLease? {
        val next = try { authority.dispatch(ComputerMachine.Intent.Begin(ComputerMachine.Request(sessionId, requestId))) }
        catch (_: ComputerAuthorityRejected) { return null }
        return next.state.grant?.lease?.takeIf { grant(sessionId) == it }
    }
    private data class Frame(val id: String, val display: ComputerDisplay, val region: DesktopRegion,
        val width: Int, val height: Int, val created: Long)

    override suspend fun enable(sessionId: String, access: ComputerAccess, expectedPolicy: ComputerPolicyRef): Boolean {
        if (access == ComputerAccess.OFF) { disable(sessionId); return false }
        val admission = authority.admit(ComputerMachine.Intent.Enable(sessionId, access, expectedPolicy))
        val admittedLease = admission.transition.effects.filterIsInstance<ComputerMachine.Effect.CheckPermissions>().singleOrNull()?.lease
        val transition = try { admission.acknowledgement.await() }
        catch (cancelled: kotlinx.coroutines.CancellationException) {
            try { admittedLease?.let(::release) } catch (cleanup: Throwable) {
                if (cleanup !== cancelled) cancelled.addSuppressed(cleanup)
                AppLog.error("computer", "cancelled_admission_cleanup_failed", mapOf("causeType" to cleanup::class.simpleName.orEmpty()))
            }
            throw cancelled
        }
        catch (rejected: ComputerAuthorityRejected) {
            AppLog.info("computer", "permission_request_rejected", mapOf("sessionId" to sessionId))
            synchronized(lock) { mutableState.value = mutableState.value.copy(error = true, detail = rejected.message.orEmpty()) }
            return false
        }
        val effect = transition.effects.filterIsInstance<ComputerMachine.Effect.CheckPermissions>().single()
        try {
            withContext(Dispatchers.IO) {
                check(authority.state.permission?.lease == effect.lease) { "Доступ отключён" }
                desktop.checkPermissions(access, request = true)
            }
            authority.dispatch(ComputerMachine.Fact.PermissionsChecked(effect.lease, true))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            try { release(effect.lease) } catch (cleanup: Throwable) {
                if (cleanup !== cancelled) cancelled.addSuppressed(cleanup)
                AppLog.error("computer", "cancelled_cleanup_failed", mapOf("causeType" to cleanup::class.simpleName.orEmpty()))
            }
            throw cancelled
        } catch (failure: Exception) {
            if (authority.state.permission?.lease == effect.lease) {
                try { authority.dispatch(ComputerMachine.Fact.PermissionsChecked(effect.lease, false)) }
                catch (cancelled: kotlinx.coroutines.CancellationException) { cancelled.addSuppressed(failure); throw cancelled }
                catch (recording: Exception) { failure.addSuppressed(recording) }
            }
            AppLog.error("computer", "permission_check_failed", mapOf("sessionId" to sessionId, "causeType" to failure::class.simpleName.orEmpty()))
            synchronized(lock) { if (authority.state.grant == null && !authority.state.resourceFailure && !authority.state.persistenceUnknown)
                mutableState.value = ComputerUseState(error = true,
                    detail = safeComputerFailure(failure, "Не удалось проверить доступ. Проверьте системные разрешения.")) }
        }
        return grant(sessionId) == effect.lease
    }

    override fun disable(sessionId: String?) {
        val value = authority.state
        val lease = value.grant?.lease ?: value.permission?.lease
        if (sessionId == null || lease?.sessionId == sessionId) authority.enqueue(ComputerMachine.Intent.Revoke(lease))
    }

    override fun grant(sessionId: String): ComputerLease? = authority.state.grant?.lease?.takeIf { it.sessionId == sessionId }

    override fun release(lease: ComputerLease) { authority.enqueue(ComputerMachine.Intent.Revoke(lease)) }

    private fun checkActive(sessionId: String, epoch: ComputerLease, control: Boolean = false, applicationTool: Boolean = false) {
        val grant = authority.state.grant
        val access = if (applicationTool) grant?.application else grant?.desktop
        check(grant?.lease == epoch && epoch.sessionId == sessionId && access != null && access != ComputerAccess.OFF) {
            "Доступ отключён. Проверьте настройки MagicPaper → Управление компьютером и отправьте новый запрос."
        }
        check(!control || access == ComputerAccess.CONTROL) { "Разрешён только просмотр. Управление выключено." }
    }

    override fun endpoint(lease: ComputerLease, requestId: String): ComputerEndpoint {
        check(authority.state.grant?.let { it.lease == lease && it.request == ComputerMachine.Request(lease.sessionId, requestId) } == true) {
            "Доступ принадлежит другому запросу"
        }
        return ComputerUseBridge(this, lease.sessionId, lease)
    }

    override fun close() {
        var failure: Throwable? = null
        fun cleanup(block: () -> Unit) { try { block() } catch (error: Throwable) {
            failure = combineComputerCleanup(failure, error)
        } }
        cleanup { disable() }
        cleanup { authority.close() }
        val applications = synchronized(lock) { failedApplications.toList().also { failedApplications.clear() } }
        applications.forEach { app -> cleanup { app.close() } }
        cleanup { desktop.close() }
        failure?.let { throw it }
    }

    override suspend fun prepareForReset() {
        resetting = true
        var failure: Throwable? = null
        try { disable() } catch (cleanup: Throwable) { failure = cleanup }
        try { authority.close() } catch (cleanup: Throwable) { failure = combineComputerCleanup(failure, cleanup) }
        authority.awaitClosed()
        failure?.let { throw it }
        check(!authority.state.resourceFailure) { "Не удалось полностью отключить управление. Перезапустите приложение." }
    }

    override suspend fun resumeAfterReset() {
        check(resetting) { "Computer reset was not prepared" }
        authority = ComputerAuthority(journal, ::authorityChanged)
        resetting = false
    }

    internal suspend fun execute(sessionId: String, lease: ComputerLease, args: JsonObject, callId: String = Id.new()): JsonObject =
        executeCommand(sessionId, lease, args, callId, ComputerMachine.Tool.DESKTOP)

    internal suspend fun executeApplication(sessionId: String, lease: ComputerLease, args: JsonObject, callId: String = Id.new()): JsonObject =
        executeCommand(sessionId, lease, args, callId, ComputerMachine.Tool.APPLICATION)

    private suspend fun executeCommand(sessionId: String, lease: ComputerLease, suppliedArgs: JsonObject, callId: String,
        tool: ComputerMachine.Tool): JsonObject = withContext(Dispatchers.IO) {
        operations.withLock {
            val args = Json.parseToJsonElement(suppliedArgs.toString()).jsonObject
            var started = false
            var mutation = false
            try {
                checkActive(sessionId, lease, applicationTool = tool == ComputerMachine.Tool.APPLICATION)
                val action = ComputerMachine.Action.entries.firstOrNull { it.tool == tool && it.wireName == args.requiredString("action") }
                    ?: error("Неизвестное действие")
                val invocation = ComputerMachine.Invocation(lease, authority.state.grant?.request, callId, action,
                    computerArgumentsFingerprint(args), args.optionalString(if (tool == ComputerMachine.Tool.DESKTOP) "screenshot_id" else "snapshot_id"), clock())
                authority.dispatch(ComputerMachine.Intent.Execute(invocation))
                started = true
                checkActive(sessionId, lease, action.mutating, tool == ComputerMachine.Tool.APPLICATION)
                val result = if (tool == ComputerMachine.Tool.DESKTOP) executeRaw(sessionId, lease, args) { mutation = true }
                    else executeApplicationRaw(sessionId, lease, args) { mutation = true }
                if ((result["isError"] as? JsonPrimitive)?.booleanOrNull == true) {
                    authority.dispatch(ComputerMachine.Fact.Failed(lease, callId, unknown = mutation))
                } else {
                    val reference = if (tool == ComputerMachine.Tool.DESKTOP) synchronized(lock) { frame }?.takeIf {
                        action != ComputerMachine.Action.DISPLAYS && it.created >= invocation.atNanos
                    }?.let { ComputerMachine.Reference(it.id, tool, it.created) }
                    else application?.reference()?.let { (id, at) -> ComputerMachine.Reference(id, tool, at) }
                    authority.dispatch(ComputerMachine.Fact.Returned(lease, callId, reference))
                }
                result
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                if (started) recordAborted(lease, callId, mutation, cancelled)
                throw cancelled
            } catch (failure: Exception) {
                if (started) recordAborted(lease, callId, mutation, failure)
                val message = if (failure is ComputerAuthorityRejected) failure.message.orEmpty()
                    else if (authority.state.persistenceUnknown) "Не удалось подтвердить сохранение доступа. Новые действия отключены."
                    else "Действие не завершено. Проверьте экран или окно перед новым действием."
                AppLog.error("computer", "tool_failed", mapOf("sessionId" to sessionId, "callId" to callId,
                    "causeType" to failure::class.simpleName.orEmpty()))
                synchronized(lock) { if (grant(sessionId) == lease || authority.state.persistenceUnknown)
                    mutableState.value = mutableState.value.copy(error = true, detail = message, busy = false) }
                toolText(message, error = true)
            }
        }
    }

    private suspend fun recordAborted(lease: ComputerLease, callId: String, unknown: Boolean, primary: Exception) {
        if (authority.state.pending != callId || authority.state.grant?.lease != lease) return
        try { withContext(kotlinx.coroutines.NonCancellable) { authority.dispatch(ComputerMachine.Fact.Failed(lease, callId, unknown)) } }
        catch (failure: Exception) {
            primary.addSuppressed(failure)
            AppLog.error("computer", "tool_outcome_unknown", mapOf("callId" to callId, "causeType" to failure::class.simpleName.orEmpty()))
        }
    }

    private suspend fun executeApplicationRaw(sessionId: String, epoch: ComputerLease, args: JsonObject, beforeMutation: () -> Unit): JsonObject = withContext(Dispatchers.IO) {
        run {
            val context = currentCoroutineContext()
            val action = (args["action"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val control = action !in listOf("windows", "inspect", "screenshot")
            val fields = mapOf("sessionId" to sessionId, "generation" to epoch.id,
                "operationId" to UUID.randomUUID().toString(), "action" to (action?.takeIf { it in ApplicationTool.actions } ?: "invalid"))
            try {
                val owner = synchronized(lock) {
                    checkActive(sessionId, epoch, control, applicationTool = true)
                    mutableState.value = state.value.copy(busy = true, detail = "Приложение в фоне", error = false)
                    application ?: ApplicationUse(applicationFactory(), clock).also { application = it }
                }
                AppLog.info("computer", "application.started", fields)
                val result = owner.execute(args, control, beforeMutation) { context.ensureActive(); checkActive(sessionId, epoch, control, applicationTool = true) }
                synchronized(lock) {
                    checkActive(sessionId, epoch, applicationTool = true)
                    mutableState.value = state.value.copy(detail = "Приложение · действие завершено", error = false)
                }
                AppLog.info("computer", "application.completed", fields)
                result
            } catch (error: kotlinx.coroutines.CancellationException) {
                // Cancelling a native call invalidates its retained references; a later request uses a fresh host.
                synchronized(lock) { if (grant(sessionId) == epoch) {
                    val previous = application
                    application = null
                    try { previous?.close() } catch (cleanup: Throwable) {
                        error.addSuppressed(cleanup)
                        if (previous != null) failedApplications += previous
                        try { authority.releaseFailed() } catch (secondary: Throwable) { error.addSuppressed(secondary) }
                        AppLog.error("computer", "cancelled_cleanup_failed", mapOf("causeType" to cleanup::class.simpleName.orEmpty()))
                    }
                } }
                AppLog.info("computer", "application.cancelled", fields)
                throw error
            } catch (error: Exception) {
                val message = safeComputerFailure(error, "Не удалось выполнить действие. Проверьте окно через inspect перед повтором.")
                AppLog.error("computer", "application.failed", fields + ("causeType" to error.javaClass.simpleName))
                synchronized(lock) { if (grant(sessionId) == epoch) mutableState.value = state.value.copy(detail = message, error = true) }
                toolText(message, error = true)
            } finally {
                synchronized(lock) { if (grant(sessionId) == epoch) mutableState.value = state.value.copy(busy = false) }
            }
        }
    }

    override suspend fun preview(sessionId: String) {
        val epoch = grant(sessionId) ?: return
        execute(sessionId, epoch, buildJsonObject { put("action", "screenshot") })
    }

    override fun openSystemSettings() {
        val uri = when {
            System.getProperty("os.name").startsWith("Mac") -> "x-apple.systempreferences:com.apple.preference.security"
            System.getProperty("os.name").startsWith("Windows") -> "ms-settings:privacy"
            else -> return
        }
        try { Desktop.getDesktop().browse(URI(uri)) }
        catch (error: Exception) {
            AppLog.error("computer", "system-settings.failed", fields = mapOf("causeType" to error.javaClass.simpleName))
            synchronized(lock) { mutableState.value = state.value.copy(detail = "Не удалось открыть системные настройки. Откройте разрешения конфиденциальности вручную.", error = true) }
        }
    }

    private suspend fun executeRaw(sessionId: String, epoch: ComputerLease, args: JsonObject, beforeMutation: () -> Unit): JsonObject = withContext(Dispatchers.IO) {
        run {
            val context = currentCoroutineContext()
            try {
                checkActive(sessionId, epoch)
                computerRequire(args.keys.all { it in ComputerTool.fields }) { "Неизвестный параметр computer" }
                val action = args.requiredString("action")
                computerRequire(action in ComputerTool.actions) { "Неизвестное действие computer" }
                val isControl = action !in listOf("screenshot", "displays", "wait")
                val crop = args["region"]?.takeUnless { it == JsonNull }?.let {
                    computerRequire(action == "screenshot") { "region доступен только для screenshot" }
                    it as? JsonObject ?: error("region: требуется объект x, y, width, height")
                }
                val imageSize = args["image_size"]?.takeUnless { it == JsonNull }?.let {
                    computerRequire(action in ComputerTool.pointerActions || crop != null) { "image_size доступен для действий мыши и screenshot с region" }
                    DesktopImageSize.parse(it)
                }
                val resolution = args.optionalString("resolution")?.let { value ->
                    computerRequire(action == "screenshot") { "resolution доступен только для screenshot" }
                    ScreenshotResolution.entries.firstOrNull { it.wireName == value } ?: error("resolution: overview или native")
                } ?: if (crop != null) ScreenshotResolution.NATIVE else ScreenshotResolution.OVERVIEW
                val format = args.optionalString("format") ?: if (resolution == ScreenshotResolution.NATIVE) "png" else "jpeg"
                computerRequire(format in listOf("jpeg", "png")) { "format: jpeg или png" }
                checkActive(sessionId, epoch, isControl)
                desktop.checkPermissions(if (isControl) ComputerAccess.CONTROL else ComputerAccess.SCREEN)
                val displays = desktop.displays()
                check(displays.isNotEmpty()) { "Нет доступных экранов" }
                if (action == "displays") return@run toolText(JsonArray(displays.map { display -> buildJsonObject {
                    put("display_id", display.id); put("width", display.width); put("height", display.height)
                } }).toString())
                fun referenceFrame(): Frame {
                    val previous = synchronized(lock) { frame } ?: error("Сначала вызовите screenshot и изучите изображение.")
                    computerRequire(args.requiredString("screenshot_id") == previous.id) { "Снимок уже изменился. Получите новый screenshot." }
                    check(clock() - previous.created <= 30_000_000_000L) { "Снимок старше 30 секунд. Получите новый screenshot." }
                    check(displays.any { it == previous.display }) { "Конфигурация экранов изменилась. Получите новый screenshot." }
                    return previous
                }
                synchronized(lock) {
                    checkActive(sessionId, epoch, isControl)
                    mutableState.value = state.value.copy(busy = true, detail = ComputerTool.label(action), error = false)
                }
                var pointer: ComputerAction? = null
                var captureRegion: DesktopRegion? = null
                val selected = if (isControl) {
                    val previous = referenceFrame()
                    // Map directly from the agent's pixel grid to the saved viewport once. Going
                    // through the encoded image first would round twice on resized HiDPI crops.
                    val input = parseAction(action, args, imageSize?.width ?: previous.width,
                        imageSize?.height ?: previous.height, previous.region)
                    pointer = input.takeIf { action in ComputerTool.pointerActions }
                    // Consume before input: a retry after an uncertain result cannot repeat a click/paste.
                    synchronized(lock) { checkActive(sessionId, epoch, true); frame = null }
                    beforeMutation()
                    desktop.perform(input, previous.display) { context.ensureActive(); checkActive(sessionId, epoch, true) }
                    delay(200)
                    previous.display
                } else if (crop != null) {
                    val previous = referenceFrame()
                    computerRequire(args.optionalString("display_id").let { it == null || it == previous.display.id }) { "region относится к экрану последнего снимка" }
                    captureRegion = previous.region.crop(crop, imageSize?.width ?: previous.width,
                        imageSize?.height ?: previous.height)
                    previous.display
                } else {
                    if (action == "wait") delay(args.optionalInt("duration_ms", 500).also {
                        computerRequire(it in 1..2000) { "duration_ms: от 1 до 2000" }
                    }.toLong())
                    args.optionalString("display_id")?.let { id ->
                        displays.firstOrNull { it.id == id } ?: error("Экран не найден. Вызовите displays.")
                    } ?: synchronized(lock) { frame?.display }?.takeIf { it in displays } ?: displays.first()
                }
                checkActive(sessionId, epoch)
                val region = captureRegion ?: DesktopRegion.full(selected)
                val shot = desktop.capture(selected, DesktopCaptureRequest(region, resolution)) { context.ensureActive(); checkActive(sessionId, epoch) }
                context.ensureActive()
                checkActive(sessionId, epoch)
                val captured = Frame(UUID.randomUUID().toString(), selected, region, shot.width, shot.height, clock())
                val encoded = encodeScreenshot(shot.png, format)
                val attachment = Attachment.fromBytes("screen.${encoded.extension}", encoded.mimeType, encoded.bytes)
                synchronized(lock) {
                    checkActive(sessionId, epoch)
                    frame = captured
                    mutableState.value = state.value.copy(preview = attachment, detail = "${ComputerTool.label(action)} · ${shot.width} × ${shot.height}", error = false,
                        desktopActive = true, activity = ComputerActivity((state.value.activity?.sequence ?: 0) + 1,
                            selected.x, selected.y, selected.width, selected.height, action,
                            pointer?.let { if (it.kind == "drag") it.toX else it.x },
                            pointer?.let { if (it.kind == "drag") it.toY else it.y }))
                }
                buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", buildJsonObject {
                            put("screenshot_id", captured.id); put("display_id", selected.id)
                            put("width", shot.width); put("height", shot.height)
                            put("image_size", buildJsonObject { put("width", shot.width); put("height", shot.height) })
                            put("resolution", resolution.wireName)
                            put("display_region", buildJsonObject {
                                put("x", region.x); put("y", region.y); put("width", region.width); put("height", region.height)
                            })
                            put("coordinates", "Send local pixel coordinates from THIS image. MagicPaper automatically maps them to the screen, including scaling, HiDPI and crop/display offsets; do not scale them or add offsets yourself. If you measured a resized copy, pass image_size={width,height} of that copy with the mouse action or region capture. Omit image_size when using this image's returned width/height. Use this screenshot_id (valid for 30 seconds).")
                        }.toString()) })
                        add(buildJsonObject { put("type", "image"); put("mimeType", encoded.mimeType); put("data", attachment.dataBase64) })
                    })
                    put("isError", false)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = safeComputerFailure(error, "Не удалось выполнить действие с экраном. Проверьте параметры и получите новый screenshot.")
                AppLog.error("computer", "operation.failed", mapOf("sessionId" to sessionId, "generation" to epoch.id,
                    "action" to args.optionalString("action").orEmpty().takeIf { it in ComputerTool.actions }.orEmpty(),
                    "causeType" to error.javaClass.simpleName))
                synchronized(lock) { if (grant(sessionId) == epoch) mutableState.value = state.value.copy(detail = message, error = true) }
                toolText(message, error = true)
            } finally {
                synchronized(lock) { if (grant(sessionId) == epoch) mutableState.value = state.value.copy(busy = false) }
            }
        }
    }
}

internal fun parseAction(kind: String, args: JsonObject, width: Int, height: Int, region: DesktopRegion): ComputerAction {
    fun coordinate(name: String, imageSize: Int, desktopSize: Int, origin: Int): Int {
        val value = args.requiredInt(name)
        computerRequire(value in 0 until imageSize) { "$name: координата вне изображения" }
        return origin + (value.toLong() * desktopSize / imageSize).toInt().coerceAtMost(desktopSize - 1)
    }
    val pointer = kind in ComputerTool.pointerActions
    val button = args.optionalString("button") ?: "left"
    computerRequire(button in listOf("left", "right", "middle")) { "button: left, right или middle" }
    val text = if (kind == "type") args.requiredString("text").also {
        computerRequire(it.length in 1..10_000) { "text: от 1 до 10000 символов" }
    } else ""
    val keys = if (kind == "key") ComputerKeys.parse((args["keys"] as? JsonArray ?: error("Требуется массив keys"))
        .map { (it as? JsonPrimitive)?.takeIf { key -> key.isString }?.content ?: error("keys: только строки") }) else emptyList()
    return ComputerAction(kind,
        x = if (pointer) coordinate("x", width, region.width, region.x) else 0,
        y = if (pointer) coordinate("y", height, region.height, region.y) else 0,
        toX = if (kind == "drag") coordinate("to_x", width, region.width, region.x) else 0,
        toY = if (kind == "drag") coordinate("to_y", height, region.height, region.y) else 0,
        button = button, text = text, keys = keys,
        amount = if (kind == "scroll") args.requiredInt("amount").also { computerRequire(it in -20..20 && it != 0) { "amount: от -20 до 20, кроме 0" } } else 0,
    )
}

internal fun JsonObject.optionalString(name: String): String? {
    val value = get(name)?.takeUnless { it == JsonNull } ?: return null
    return (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("$name: требуется строка")
}
internal fun JsonObject.requiredString(name: String) = optionalString(name) ?: error("Требуется $name")
internal fun JsonObject.requiredInt(name: String): Int =
    (get(name) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("$name: требуется целое число")
internal fun JsonObject.optionalInt(name: String, default: Int) = if (get(name) == null || get(name) == JsonNull) default else requiredInt(name)
internal fun toolText(text: String, error: Boolean = false) = buildJsonObject {
    put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", text) }) })
    put("isError", error)
}
