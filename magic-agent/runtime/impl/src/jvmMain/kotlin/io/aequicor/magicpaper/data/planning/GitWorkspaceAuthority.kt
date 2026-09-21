package io.aequicor.magicpaper.data.planning

import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.domain.checks.*
import io.aequicor.magicpaper.logging.AppLog
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One application-lived lease registry for both Git adapters. It owns OS locks and the exact
 * checks admitted while each handle is held; process state remains in the checks journal.
 * The lock-file root preserves the existing planning path and canonical-checkout identity.
 */
class GitWorkspaceAuthority(
    internal val checks: CommandChecks,
    private val lockRoot: File = File(System.getProperty("user.home"), ".MagicPaper/planning"),
    private val checkpoint: (String) -> Unit = {},
) {
    private class ProjectLock(val lease: WorkspaceLease, val resources: Pair<RandomAccessFile, FileLock>,
        var abandoned: Boolean = false, var closing: Boolean = false,
        val active: MutableSet<String> = mutableSetOf(), val commands: MutableSet<CheckRef> = mutableSetOf(),
        val affectedResources: MutableSet<String> = mutableSetOf(lease.canonicalPath),
        val operations: Mutex = Mutex())
    private val locks = mutableMapOf<String, ProjectLock>()
    private fun root(project: CodingProject) = File(lockRoot, MessageDigest.getInstance("SHA-256")
        .digest(File(project.path).canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }.take(24))

    internal suspend fun <T> owned(
        leases: List<WorkspaceLease>, scope: CheckScope, operationId: String,
        affected: Set<String> = emptySet(), action: suspend (OwnedGitCommands) -> T,
    ): T {
        require(leases.isNotEmpty() && leases.map { it.token }.distinct().size == leases.size && operationId.isNotBlank()) {
            "Не указан точный захват операции рабочей папки"
        }
        val entries = synchronized(locks) { leases.map { lease ->
            requireNotNull(locks[lease.ownerId]).also { require(it.lease == lease) { "Захват рабочей папки изменился" } }
        }.sortedBy { it.lease.canonicalPath + ":" + it.lease.token } }
        // Global ordering is shared by OPEN and DELIVER. Never suspend while holding the map monitor.
        suspend fun locked(index: Int): T {
            if (index < entries.size) return entries[index].operations.withLock { locked(index + 1) }
            synchronized(locks) {
                require(entries.all { locks[it.lease.ownerId] === it && !it.closing && !it.abandoned }) {
                    "Захват рабочей папки больше не действует"
                }
                check(entries.all { operationId !in it.active }) { "Операция рабочей папки уже выполняется" }
                entries.forEach { it.active += operationId }
            }
            try {
                for (ref in synchronized(locks) { entries.flatMap { it.commands }.distinct() })
                    if (checks.inspect(ref) == null) throw CheckOutcomeUnknown()
                val resources = affected.map { File(it).canonicalPath }.toSet() + leases.map { it.canonicalPath }
                val commands = OwnedGitCommands(checks, leases.first().canonicalPath, scope, operationId, resources) { ref, paths ->
                    synchronized(locks) {
                        check(entries.all { locks[it.lease.ownerId] === it })
                        entries.forEach { it.commands += ref; it.affectedResources += paths }
                    }
                }
                return withContext(Dispatchers.IO) { action(commands) }
            } finally { synchronized(locks) { entries.forEach { it.active -= operationId } } }
        }
        return locked(0)
    }

    internal suspend fun inspectHeld(operation: WorkspaceOperation) = withContext(Dispatchers.IO) {
        val held = synchronized(locks) {
            checkNotNull(locks[operation.lease.ownerId]).also {
                require(it.lease == operation.lease && operation.operationId.isNotBlank()) { "Захват рабочей папки изменился" }
                check(it.active.isEmpty()) { "Операция рабочей папки ещё выполняется" }
            }
        }
        for (ref in synchronized(locks) { held.commands.toList() }) if (checks.inspect(ref) == null) throw CheckOutcomeUnknown()
    }

    /** Exclusivity belongs to one canonical checkout: its `owner.lock` file also excludes other
     * application processes on the same path, while unrelated paths — including those of a
     * parallel application instance sharing this profile — stay usable. */
    suspend fun acquire(project: CodingProject, requestId: String): WorkspaceLease? {
        require(project.id.isNotBlank() && requestId.isNotBlank()) { "Не указан владелец запроса рабочей папки" }
        var createdLease: WorkspaceLease? = null
        try {
            return withContext(Dispatchers.IO) {
                val protectedPaths = gitMetadataResources(File(project.path)) + File(project.path).canonicalPath
                if (protectedPaths.any { checks.unresolved(it).isNotEmpty() }) throw CheckOutcomeUnknown()
                synchronized(locks) {
                    val path = File(project.path).canonicalPath
                    // Only a lease which never reached its caller can be cleaned up here. Delivered
                    // leases always require their exact release handle, including after a failed close.
                    locks.values.firstOrNull { it.abandoned && it.lease.canonicalPath == path }?.let { abandoned ->
                        try {
                            releaseResources(abandoned.resources, "project-lock")
                            locks.remove(abandoned.lease.ownerId)
                        } catch (failure: Throwable) {
                            logRetainedLease(abandoned.lease, failure)
                            throw failure
                        }
                    }
                    if (project.id in locks || locks.values.any { it.lease.canonicalPath == path }) return@synchronized null
                    val dir = root(project).apply { mkdirs() }
                    val lease = WorkspaceLease(UUID.randomUUID().toString(), requestId, project.id, path)
                    // Writer identity may change between generations; exclusivity belongs to the canonical checkout.
                    val file = RandomAccessFile(File(dir, "owner.lock"), "rw")
                    val lock = try { file.channel.tryLock() }
                    catch (_: java.nio.channels.OverlappingFileLockException) { null }
                    catch (primary: Throwable) {
                        try { file.close() } catch (cleanup: Throwable) { if (cleanup !== primary) primary.addSuppressed(cleanup) }
                        throw primary
                    }
                    if (lock == null) { file.close(); null }
                    else {
                        locks[project.id] = ProjectLock(lease, file to lock)
                        createdLease = lease
                        checkpoint("project-lock-acquired")
                        lease
                    }
                }
            }
        } catch (primary: Throwable) {
            // withContext can discard the return value when its caller is cancelled after the OS
            // lock was acquired. Keep the exact handle locally until it has reached the caller.
            createdLease?.let { lease ->
                synchronized(locks) {
                    locks[lease.ownerId]?.takeIf { it.lease == lease }?.let {
                        it.abandoned = true
                    }
                }
                try { withContext(NonCancellable) { release(lease) } }
                catch (cleanup: Throwable) {
                    if (cleanup !== primary) primary.addSuppressed(cleanup)
                    logRetainedLease(lease, cleanup)
                }
            }
            throw primary
        }
    }

    private fun logRetainedLease(lease: WorkspaceLease, failure: Throwable) {
        AppLog.error("planning.workspace", "acquire_cleanup.failed", mapOf(
            "projectId" to lease.ownerId, "requestId" to lease.requestId,
            "operation" to "release_abandoned_lease", "result" to "lease_retained",
            "failure" to (failure::class.simpleName ?: "Throwable"),
        ))
    }

    suspend fun holderOf(path: String): String? = withContext(Dispatchers.IO) {
        synchronized(locks) {
            locks.values.firstOrNull { it.lease.canonicalPath == File(path).canonicalPath }?.lease?.ownerId
        }
    }

    suspend fun release(lease: WorkspaceLease) = withContext(Dispatchers.IO) {
        val held = synchronized(locks) {
            val value = locks.values.firstOrNull { it.lease.token == lease.token } ?: return@withContext
            require(value.lease == lease) { "Идентичность захвата рабочей папки изменилась" }
            check(value.active.isEmpty()) { "Операция рабочей папки ещё выполняется" }
            value.closing = true
            value
        }
        // No signalling or generic reconciliation: only receipts registered by this exact lease
        // can establish its release. A failed/unknown receipt retains the handle and OS lock.
        for (ref in synchronized(locks) { held.commands.toList() }) {
            if (checks.inspect(ref) == null) throw CheckOutcomeUnknown()
        }
        for (path in synchronized(locks) { held.affectedResources.toList() }) {
            if (checks.unresolved(path).isNotEmpty()) throw CheckOutcomeUnknown()
        }
        synchronized(locks) {
            if (locks[lease.ownerId] !== held) return@withContext
            releaseResources(held.resources, "project-lock")
            locks.remove(lease.ownerId)
        }
        Unit
    }

    private fun releaseResources(resources: Pair<RandomAccessFile, FileLock>, boundary: String) {
        val (file, lock) = resources
        if (lock.isValid) {
            checkpoint("$boundary-releasing")
            lock.release()
        }
        checkpoint("$boundary-released")
        file.close()
    }

}
