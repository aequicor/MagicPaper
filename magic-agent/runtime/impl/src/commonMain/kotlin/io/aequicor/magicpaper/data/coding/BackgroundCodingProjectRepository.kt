package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The compatibility cache never inherits the UI dispatcher. The journal owns all semantic writes. */
class BackgroundCodingProjectRepository(
    private val delegate: CodingCheckpointStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CodingCheckpointStore {
    private val lock = Mutex()
    private suspend fun <T> access(block: suspend () -> T): T = withContext(dispatcher) { lock.withLock { block() } }
    override suspend fun all() = access { delegate.all() }
    override suspend fun sessions(projectId: String) = access { delegate.sessions(projectId) }
    override suspend fun messages(projectId: String, sessionId: String) = access { delegate.messages(projectId, sessionId) }
    override suspend fun orchestration(sessionId: String) = access { delegate.orchestration(sessionId) }
    override suspend fun legacyProjects(excludingIds: Set<String>) = access { delegate.legacyProjects(excludingIds) }
    override suspend fun checkpoint(state: CodingMachine.State) = access { delegate.checkpoint(state) }
    override suspend fun wipe() = access { delegate.wipe() }
}
