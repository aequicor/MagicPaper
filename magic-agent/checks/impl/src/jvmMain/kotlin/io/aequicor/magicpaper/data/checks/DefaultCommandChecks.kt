package io.aequicor.magicpaper.data.checks

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.checks.*
import io.aequicor.magicpaper.domain.checks.CommandCheckMachine.Input
import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json

/** The only writer and effect interpreter for command checks; no process-global registry or shutdown hook. */
internal class DefaultCommandChecks(private val events: EventJournal, private val payloads: KeyValueStore,
    private val driver: CheckProcessDriver,
    private val probeRetryDelayMs: Long = PROBE_RETRY_DELAY_MS) : CommandChecks {
    private class Entry(val journal: CheckInputJournal) { val lock = Mutex() }
    private val entries = ConcurrentHashMap<String, Entry>()
    private val running = ConcurrentHashMap<CheckRef, Job>()
    private val completions = ConcurrentHashMap<CheckRef, CompletableDeferred<Unit>>()
    private val activeCommands = ConcurrentHashMap<CheckRef, CheckCommand>()
    private val admission = Mutex()
    private val probeLock = Mutex()
    private var probeVerified = false
    @Volatile private var accepting = true
    @Volatile private var closed = false
    /** A failed probe marks checks unavailable but does not abort the owning runtime restore.
     *  The failure timestamp allows retry after a transient glitch without permanently disabling checks. */
    @Volatile private var probeFailure: Throwable? = null
    @Volatile private var probeFailureAt: Long = 0
    override val progress = MutableSharedFlow<CheckProgress>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override suspend fun run(command: CheckCommand): CheckResult {
        // Preparation commands belong to this interpreter, not to callers of the public command port.
        if (command.metadataSource != null || command.policy == CheckPolicy.METADATA_READ_ONLY || command.protectedResource?.isBlank() == true)
            throw CheckRejected(CommandCheckMachine.Reason.INVALID)
        val frozen = command.copy(arguments = command.arguments.toList(), environment = command.environment.toMap(), affectedResources = command.affectedResources.toSet())
        val job = currentCoroutineContext().job
        val completed = CompletableDeferred<Unit>()
        // A caller can already be cancelled after its workspace registered this ref. Capture
        // our reservation without dispatching a process, then let the cancellable boundary
        // enter the same positive no-dispatch catch as any later preparation failure.
        val (normalized, entry) = withContext(NonCancellable) { withContext(Dispatchers.IO) {
            val normalized = frozen.copy(workspace = Paths.get(frozen.workspace).toRealPath().toString(),
                protectedResource = frozen.protectedResource?.let(::canonicalResource),
                affectedResources = frozen.affectedResources.map(::canonicalResource).toSet())
            val entry = admission.withLock {
                check(accepting && !closed) { "Проверки временно остановлены" }
                claim(normalized)
                val owner = entries.computeIfAbsent(normalized.resource) { Entry(CheckInputJournal(events, payloads, it)) }
                // Reset must see every admitted caller before it closes admission and cancels writers.
                check(running.putIfAbsent(normalized.ref, job) == null) { "Проверка этого запроса уже выполняется" }
                completions[normalized.ref] = completed
                activeCommands[normalized.ref] = normalized
                owner
            }
            normalized to entry
        } }
        // This live scope owns the only possible parent Submit. Once attempted, even a
        // failed append cannot be reclassified as pre-admission by absence of a receipt.
        var parentAdmissionAttempted = false
        try {
            return withContext(Dispatchers.IO) {
                checkResourceConflicts(normalized)
                val saved = entry.lock.withLock {
                    check(accepting && !closed) { "Проверки временно остановлены" }
                    entry.journal.initialize()
                    val preview = CommandCheckMachine.reduce(entry.journal.state, Input.Intent.Submit(normalized))
                    preview.effects.filterIsInstance<CommandCheckMachine.Effect.Reject>().firstOrNull()?.let {
                        throw CheckRejected(it.reason)
                    }
                    if (preview.effects.isEmpty()) checkNotNull(entry.journal.state.checks[normalized.ref]?.result) else null
                }
                if (saved != null) return@withContext saved
                // A lookup never starts a probe. Do not hold the target lock while probing: its
                // canonical workspace can equal the probe workspace. Actual admission is checked again below.
                if (normalized.policy in setOf(CheckPolicy.PROTECTED_PROJECT, CheckPolicy.GIT_READ_ONLY)) ensureProbe()
                // A prior probe failure marks all sandbox-dependent checks unavailable without aborting the runtime.
                probeFailure?.let { throw CheckOutcomeUnknown(it) }
                entry.lock.withLock {
                    currentCoroutineContext().ensureActive()
                    check(accepting && !closed) { "Проверки временно остановлены" }
                    // Same resource lock, same reducer and journal. The parent has not been admitted
                    // yet, so every child has its own durable Submit before any native grant.
                    val preview = CommandCheckMachine.reduce(entry.journal.state, Input.Intent.Submit(normalized))
                    preview.effects.filterIsInstance<CommandCheckMachine.Effect.Reject>().firstOrNull()?.let { throw CheckRejected(it.reason) }
                    if (preview.effects.isEmpty()) return@withLock checkNotNull(entry.journal.state.checks[normalized.ref]?.result)
                    val metadata = prepareGitMetadata(entry.journal, normalized)
                    currentCoroutineContext().ensureActive()
                    parentAdmissionAttempted = true
                    val transition = entry.journal.append(Input.Intent.Submit(normalized))
                    if (transition.effects.isEmpty()) return@withLock checkNotNull(entry.journal.state.checks[normalized.ref]?.result)
                    val effect = transition.effects.single() as CommandCheckMachine.Effect.Prepare
                    execute(entry.journal, effect.command, metadata)
                }
            }
        } catch (failure: Throwable) {
            var primary = failure
            if (!parentAdmissionAttempted) withContext(NonCancellable + Dispatchers.IO) {
                try { entry.lock.withLock {
                    entry.journal.initialize()
                    // An earlier admission (including a payload conflict) belongs to its
                    // original lifecycle. This failure supplies no new evidence about it.
                    if (normalized.ref !in entry.journal.state.checks) {
                        val rejected = Input.Fact.PreparationRejected(normalized, CheckResult("", null,
                            if (failure is CancellationException) "Проверка отменена до запуска"
                            else "Проверка не запущена: подготовка недоступна"))
                        val preview = CommandCheckMachine.reduce(entry.journal.state, rejected)
                        if (preview.effects.none { it is CommandCheckMachine.Effect.Reject }) entry.journal.append(rejected)
                    }
                } } catch (persistenceFailure: Throwable) {
                    primary = combine(primary, persistenceFailure)
                    entry.journal.uncertain(persistenceFailure, "preparation.commit_unknown")
                }
            }
            throw primary
        } finally {
            activeCommands.remove(normalized.ref)
            running.remove(normalized.ref, job)
            // A free journal lock is not proof that a registered caller has finished its
            // noncancellable rejection/cleanup write. Reset waits for this exact call boundary.
            completed.complete(Unit)
            completions.remove(normalized.ref, completed)
        }
    }

    private suspend fun prepareGitMetadata(journal: CheckInputJournal, parent: CheckCommand): CheckGitMetadata? {
        if (parent.policy != CheckPolicy.PROTECTED_PROJECT || !driver.needsGitMetadata(parent)) return null
        val outputs = linkedMapOf<CheckGitMetadataQuery, CheckOutputRef>()
        for (query in CheckGitMetadataQuery.entries) {
            currentCoroutineContext().ensureActive()
            check(accepting && !closed) { "Проверки временно остановлены" }
            // Fresh reads after a crash before parent admission cannot reuse stale tracked-file data.
            val child = CheckCommand(parent.ref.copy(callId = "metadata-${UUID.randomUUID()}"), parent.workspace,
                query.arguments(), policy = CheckPolicy.METADATA_READ_ONLY, outputMode = CheckOutputMode.BINARY_STDOUT,
                protectedResource = parent.resource, metadataSource = CheckMetadataSource(parent.ref, query), affectedResources = parent.affectedResources)
            claim(child)
            journal.append(Input.Intent.Submit(child))
            val result = execute(journal, child)
            if (result.exitCode != 0 || result.blockedReason != null) {
                AppLog.error("checks", "metadata.unavailable", mapOf("sessionId" to parent.ref.scope.sessionId,
                    "requestId" to parent.ref.scope.requestId, "callId" to parent.ref.callId,
                    "metadataCallId" to child.ref.callId, "query" to query.name, "result" to "parent_not_dispatched"))
                error("Не удалось прочитать сведения Git для защиты файлов")
            }
            outputs[query] = checkNotNull(result.binaryOutput) { "Сведения Git не сохранены" }
        }
        return CheckGitMetadata(parent.ref, parent.resource, outputs.toMap())
    }

    private suspend fun execute(journal: CheckInputJournal, command: CheckCommand, metadata: CheckGitMetadata? = null): CheckResult {
        val ref = command.ref
        var prepared: PreparedCommandCheck? = null
        var outcome: CheckResult? = null
        var cleanup: CheckCleanup? = null
        var output = ""
        var preparationStarted = false
        try {
            currentCoroutineContext().ensureActive()
            if (!accepting || closed) throw CheckNotDispatched("Проверки временно остановлены")
            preparationStarted = true
            prepared = driver.prepareWithMetadata(command, UUID.randomUUID().toString(), CheckAuthorityRecorder { id, bytes ->
                journal.recordAuthority(ref, id, bytes)
            }, metadata)
            journal.append(Input.Fact.ProcessPrepared(ref, prepared.receipt))
            currentCoroutineContext().ensureActive()
            check(accepting && !closed) { "Проверки временно остановлены" }
            journal.append(Input.Intent.Release(ref, prepared.receipt.id))
            currentCoroutineContext().ensureActive()
            prepared.release()
            outcome = prepared.awaitResult { text -> output = text; progress.tryEmit(CheckProgress(ref, text)) }
            journal.append(Input.Fact.Exited(ref, prepared.receipt.id, outcome))
            cleanup = withContext(NonCancellable) { prepared.stopAndConfirm() }
            return finish(journal, ref, prepared, outcome, cleanup)
        } catch (failure: Throwable) {
            var primary = failure
            withContext(NonCancellable) {
                try {
                    when {
                        prepared == null && failure is CheckNotDispatched -> journal.append(Input.Fact.NotDispatched(ref,
                            failure.restoredAuthority, CheckResult("", null, failure.safeReason)))
                        prepared == null && failure is CheckPreparationCancelled -> journal.append(Input.Fact.NotDispatched(ref,
                            null, CheckResult("", null, "Проверка отменена до запуска")))
                        prepared == null && !preparationStarted && failure is CancellationException && journal.state.checks[ref]?.authorityReceipt == null ->
                            journal.append(Input.Fact.NotDispatched(ref, null, CheckResult("", null, "Проверка отменена до запуска")))
                        prepared != null -> {
                            // Cancellation is observed and journaled before signalling a native process.
                            // A journal outage cannot suppress the independent native cleanup attempt.
                            if (!journal.state.persistenceUnknown) try { journal.append(Input.Intent.Stop(ref)) }
                            catch (stopWriteFailure: Throwable) {
                                primary = combine(primary, stopWriteFailure)
                                journal.uncertain(stopWriteFailure)
                            }
                            if (cleanup == null) try { cleanup = prepared.stopAndConfirm() }
                            catch (stopFailure: Throwable) {
                                primary = combine(primary, stopFailure)
                                journal.uncertain(stopFailure)
                            }
                            if ((failure is CancellationException || failure is CheckTimedOut || failure is CheckOutputLimitExceeded) && !journal.state.persistenceUnknown && !journal.state.unknown) {
                                val stopped = outcome ?: CheckResult(output, null,
                                    when (failure) {
                                        is CheckTimedOut -> "Проверка остановлена по таймауту"
                                        is CheckOutputLimitExceeded -> "Вывод команды превышает допустимый размер"
                                        else -> "Проверка отменена"
                                    })
                                if (journal.state.checks[ref]?.result == null) journal.append(Input.Fact.Exited(ref, prepared.receipt.id, stopped))
                                finish(journal, ref, prepared, stopped, checkNotNull(cleanup))
                            } else if (!journal.state.persistenceUnknown) journal.append(Input.Fact.Failed(ref))
                        }
                        else -> if (!journal.state.persistenceUnknown) journal.append(Input.Fact.Failed(ref))
                    }
                } catch (cleanupFailure: Throwable) {
                    primary = combine(primary, cleanupFailure)
                    journal.uncertain(cleanupFailure)
                }
            }
            AppLog.error("checks", "run.failed", primary, mapOf("sessionId" to ref.scope.sessionId, "requestId" to ref.scope.requestId,
                "callId" to ref.callId, "causeType" to primary.javaClass.simpleName,
                "result" to if (journal.state.unknown) "unknown" else "stopped"))
            if (primary is CancellationException) throw primary
            if ((failure is CheckNotDispatched || failure is CheckTimedOut || failure is CheckOutputLimitExceeded) && !journal.state.unknown)
                return checkNotNull(journal.state.checks[ref]?.result)
            throw CheckOutcomeUnknown(primary)
        }
    }

    private suspend fun finish(journal: CheckInputJournal, ref: CheckRef, prepared: PreparedCommandCheck,
        result: CheckResult, cleanup: CheckCleanup): CheckResult {
        val receipt = prepared.receipt.id
        if (journal.state.checks[ref]?.groupStopped == null) journal.append(Input.Fact.GroupStopped(ref, receipt, cleanup.groupStopped))
        if (journal.state.checks[ref]?.authorityRestored == null) journal.append(Input.Fact.AuthorityRestored(ref, receipt, cleanup.authorityRestored))
        val artifacts = prepared.attest()
        val proof = CheckCompletionProof(receipt, cleanup.groupStopped, cleanup.authorityRestored, artifacts)
        withContext(NonCancellable) { prepared.discard() }
        journal.saveCompletion(ref, proof, result)
        journal.append(Input.Fact.ArtifactsCommitted(ref, receipt, artifacts))
        return result
    }

    override suspend fun inspect(ref: CheckRef): CheckResult? = withContext(Dispatchers.IO) {
        discover()
        val matches = entries.values.filter { entry -> entry.lock.withLock { entry.journal.initialize(); ref in entry.journal.state.checks } }
        check(matches.size <= 1) { "Не удалось однозначно определить проверку" }
        val entry = matches.singleOrNull() ?: return@withContext null
        entry.lock.withLock {
            if (entry.journal.state.persistenceUnknown) throw CheckOutcomeUnknown()
            val check = entry.journal.state.checks.getValue(ref)
            if (check.phase == CommandCheckMachine.Phase.FINISHED) return@withLock check.result
            entry.journal.append(Input.Intent.Inspect(ref))
            val completion = try { entry.journal.completion(ref) } catch (failure: Exception) {
                entry.journal.uncertain(failure, "inspection.failed"); throw CheckOutcomeUnknown(failure)
            } ?: return@withLock null
            entry.journal.append(completion)
            completion.result
        }
    }

    override suspend fun unresolved(resource: String): Set<CheckRef> = withContext(Dispatchers.IO) {
        val path = canonicalResource(resource)
        discover()
        entries.values.flatMap { entry -> entry.lock.withLock {
            entry.journal.initialize()
            if (entry.journal.state.persistenceUnknown) throw CheckOutcomeUnknown()
            entry.journal.state.checks.filterValues { it.phase != CommandCheckMachine.Phase.FINISHED && path in it.command.resources }.keys
        } }.toSet()
    }

    /** Called after resource reservation, outside admission and entry locks. No native effects. */
    private suspend fun checkResourceConflicts(command: CheckCommand) {
        val resources = command.resources
        val active = activeCommands.values.toList()
        if (active.any { it.resource != command.resource && it.resources.any(resources::contains) })
            throw CheckRejected(CommandCheckMachine.Reason.BUSY)
        discover()
        for ((resource, entry) in entries) {
            // The same primary already serializes callers. A reservation for another primary
            // has not yet passed its own reducer and must never mask its older unknown history.
            if (resource == command.resource) continue
            entry.lock.withLock {
                entry.journal.initialize()
                if (entry.journal.state.persistenceUnknown) throw CheckOutcomeUnknown()
                if (entry.journal.state.checks.values.any { it.phase != CommandCheckMachine.Phase.FINISHED &&
                        it.command.resources.any(resources::contains) }) throw CheckRejected(CommandCheckMachine.Reason.UNKNOWN)
            }
        }
    }

    private fun canonicalResource(value: String): String {
        val path = Paths.get(value).toAbsolutePath().normalize()
        var ancestor = path
        while (!java.nio.file.Files.exists(ancestor, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            ancestor = ancestor.parent ?: error("Ресурс рабочей папки недоступен")
        return ancestor.toRealPath().resolve(ancestor.relativize(path)).normalize().toString()
    }

    override suspend fun readOutput(ref: CheckRef): ByteArray = withContext(Dispatchers.IO) {
        val result = inspect(ref) ?: throw CheckOutcomeUnknown()
        val output = checkNotNull(result.binaryOutput) { "Команда не сохранила двоичный вывод" }
        try { driver.readOutput(output) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Throwable) {
            AppLog.error("checks", "output.read_failed", mapOf("sessionId" to ref.scope.sessionId,
                "requestId" to ref.scope.requestId, "callId" to ref.callId,
                "causeType" to failure.javaClass.simpleName, "result" to "output_unavailable"))
            throw CheckOutcomeUnknown(failure)
        }
    }

    override fun abort(sessionId: String) { running.filterKeys { it.scope.sessionId == sessionId }.values.forEach { it.cancel() } }
    override fun abortAll() { running.values.forEach { it.cancel() } }
    override suspend fun reconcile(sessionId: String) {
        discover()
        val refs = entries.values.flatMap { entry -> entry.lock.withLock {
            entry.journal.initialize()
            entry.journal.state.checks.keys.filter { it.scope.sessionId == sessionId }
        } }
        refs.forEach { if (inspect(it) == null) throw CheckOutcomeUnknown() }
    }
    override suspend fun prepareForReset() {
        val pending = admission.withLock { accepting = false; completions.values.toList() }
        abortAll()
        withContext(NonCancellable) { pending.awaitAll() }
        drain(requireKnown = true)
    }
    override suspend fun resumeAfterReset() {
        entries.values.forEach { entry -> entry.lock.withLock { } }
        admission.withLock { entries.clear(); probeVerified = false; probeFailure = null; probeFailureAt = 0; if (!closed) accepting = true }
    }
    override suspend fun close() {
        val pending = admission.withLock { closed = true; accepting = false; completions.values.toList() }
        abortAll()
        withContext(NonCancellable) { pending.awaitAll() }
        drain(requireKnown = false)
    }
    private suspend fun discover() = withContext(Dispatchers.IO) {
        events.streams().filter { it.startsWith("command-check:") }.forEach { stream ->
            val workspace = try { CheckInputJournal.workspace(events, payloads, stream) } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                AppLog.error("checks", "discovery.failed", failure, mapOf("causeType" to failure.javaClass.simpleName))
                throw CheckOutcomeUnknown(failure)
            } ?: return@forEach
            entries.computeIfAbsent(workspace) { Entry(CheckInputJournal(events, payloads, it)) }
        }
    }
    private suspend fun drain(requireKnown: Boolean) {
        val failures = mutableListOf<Throwable>()
        withContext(NonCancellable) {
            if (requireKnown) try { discover() } catch (failure: Throwable) { failures += failure }
            entries.values.forEach { entry ->
                try { entry.lock.withLock {
                    if (requireKnown) {
                        entry.journal.initialize()
                        // Stale sandbox-probe artifacts are abandoned by declaration: the fixture is
                        // disposable and a fresh probe re-verifies it, so they never fenced user work.
                        if (entry.journal.state.persistenceUnknown || entry.journal.state.checks.values.any {
                                it.phase != CommandCheckMachine.Phase.FINISHED &&
                                    it.command.ref.scope.projectId != CommandCheckMachine.SANDBOX_PROBE_PROJECT
                            }) throw CheckOutcomeUnknown()
                    }
                } } catch (failure: Throwable) { failures += failure }
            }
            // Unknown/corrupt journals cannot prevent attempts to release every process resource still owned in memory.
            try { driver.cleanup() } catch (failure: Throwable) { failures += failure }
        }
        try { currentCoroutineContext().ensureActive() } catch (failure: CancellationException) { failures += failure }
        val primary = failures.firstOrNull { it is CancellationException } ?: failures.firstOrNull() ?: return
        failures.filter { it !== primary }.forEach(primary::addSuppressed)
        AppLog.error("checks", "lifecycle.cleanup.failed", primary, mapOf("causeType" to primary.javaClass.simpleName))
        throw primary
    }
    /** The real OS probe is another journaled command in a fixed owned workspace, with the same lifecycle proofs.
     *  A probe failure marks checks unavailable for this runtime visit; it does not throw into the caller,
     *  so session restore and other startup work can proceed without sandbox-protected checks.
     *  The failure is retried after a short delay so a transient storage glitch does not permanently
     *  disable sandbox-dependent checks, but a genuine sandbox outage is not hammered repeatedly. */
    private suspend fun ensureProbe() = probeLock.withLock {
        if (probeVerified) return@withLock
        // A prior probe failure is retried after a short delay; a transient journal outage must not
        // permanently disable git checks, but a genuine sandbox outage should not be hammered either.
        val now = System.currentTimeMillis()
        if (probeFailure != null && now - probeFailureAt < probeRetryDelayMs) return@withLock
        probeFailure = null
        val workspace = driver.probeWorkspace() ?: return@withLock
        val entry = admission.withLock {
            check(accepting && !closed) { "Проверки временно остановлены" }
            entries.computeIfAbsent(workspace) { Entry(CheckInputJournal(events, payloads, it)) }
        }
        entry.lock.withLock {
            entry.journal.initialize()
            // The probe workspace may hold stale unfinished checks from a prior crash; they do not
            // reflect the current sandbox state and must not block the fresh probe attempt.
            val unfinishedNonProbe = entry.journal.state.checks.filter { (ref, check) ->
                check.phase != CommandCheckMachine.Phase.FINISHED && ref.scope.projectId != CommandCheckMachine.SANDBOX_PROBE_PROJECT
            }
            if (entry.journal.state.persistenceUnknown || unfinishedNonProbe.isNotEmpty()) {
                probeFailure = CheckOutcomeUnknown()
                probeFailureAt = now
                return@withLock
            }
            check(accepting && !closed) { "Проверки временно остановлены" }
            val ref = CheckRef(CheckScope(CommandCheckMachine.SANDBOX_PROBE_PROJECT,
                CommandCheckMachine.SANDBOX_PROBE_PROJECT, UUID.randomUUID().toString(), 0), "probe")
            val probe = driver.createProbe(ref)
            check(probe.command.workspace == workspace && probe.command.ref == ref)
            entry.journal.append(Input.Intent.Submit(probe.command))
            val job = currentCoroutineContext().job
            running[ref] = job
            try {
                val result = execute(entry.journal, probe.command)
                probe.verify(result)
                probeVerified = true
            } catch (failure: Throwable) {
                // The cause is the only evidence of why every sandbox-dependent check is unavailable;
                // recording the class name alone left a broken OS sandbox indistinguishable from a
                // storage outage and sent diagnosis after the journal instead of the runner.
                AppLog.error("checks", "sandbox.probe.failed", failure, mapOf("causeType" to failure.javaClass.simpleName))
                if (failure is CancellationException) throw failure
                probeFailure = IllegalStateException("ОС не подтвердила защиту исходников. Проверка недоступна", failure)
                probeFailureAt = System.currentTimeMillis()
            } finally { running.remove(ref, job) }
        }
    }
    /** A protocol call ID cannot acquire another workspace's authority after a retry or reconnect. */
    private fun claim(command: CheckCommand) {
        val key = "check-call:" + CheckInputJournal.hash(Json.encodeToString(CheckRef.serializer(), command.ref))
        val prior = payloads.read(key)
        if (prior != null) {
            if (prior != command.resource) throw CheckRejected(CommandCheckMachine.Reason.PAYLOAD_CHANGED)
            return
        }
        var failure: Exception? = null
        try { payloads.write(key, command.resource) } catch (error: Exception) { failure = error }
        val observed = try { payloads.read(key) } catch (readFailure: Exception) {
            if (failure == null) throw CheckOutcomeUnknown(readFailure)
            failure.addSuppressed(readFailure); throw failure
        }
        if (observed != command.resource) throw CheckOutcomeUnknown(failure)
        if (failure is CancellationException) throw failure
    }
    private fun combine(primary: Throwable, cleanup: Throwable): Throwable = if (primary === cleanup) primary
    else if (cleanup is CancellationException && primary !is CancellationException) {
        cleanup.addSuppressed(primary); cleanup
    } else { primary.addSuppressed(cleanup); primary }
    companion object {
        /** Minimum delay between probe retries; transient glitches recover faster than this, genuine outages wait. */
        const val PROBE_RETRY_DELAY_MS = 30_000L
    }
}
