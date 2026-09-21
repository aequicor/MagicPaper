package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** All operations run under DurableByteStore.withDraftLock, shared by journal and draft owners. */
internal object DurableResetBarrier {
    private const val KEY = "application-reset"
    private const val LEGACY_KEY = "\u0000magicpaper-reset-epoch"
    private const val FORMAT = "magicpaper-reset"
    private val json = Json { encodeDefaults = true }

    internal data class Admission(val epoch: Long, val id: String)
    @Serializable private enum class Phase { RESETTING, READY }
    @Serializable private data class Fence(
        val storageFormat: String,
        val version: Int,
        val epoch: Long,
        val id: String,
        val phase: Phase,
    )

    suspend fun epoch(backend: DurableByteStore): Long {
        val fence = read(backend) ?: return legacyEpoch(backend)
        if (fence.phase != Phase.READY) {
            throw StorageException("incomplete application reset", StorageException.Kind.RESET_INCOMPLETE)
        }
        return fence.epoch
    }

    suspend fun begin(backend: DurableByteStore): Admission {
        val previous = read(backend)
        if (previous?.phase == Phase.RESETTING) return Admission(previous.epoch, previous.id)
        val previousEpoch = previous?.epoch ?: legacyEpoch(backend)
        if (previousEpoch == Long.MAX_VALUE) throw StorageException("reserve reset epoch", StorageException.Kind.WRITE)
        val fence = Fence(FORMAT, 1, previousEpoch + 1, storageId(), Phase.RESETTING)
        writeVerified(backend, fence)
        return Admission(fence.epoch, fence.id)
    }

    suspend fun complete(backend: DurableByteStore, admission: Admission) {
        val expected = Fence(FORMAT, 1, admission.epoch, admission.id, Phase.RESETTING)
        if (read(backend) != expected) throw StorageException("changed reset admission", StorageException.Kind.CORRUPT)
        writeVerified(backend, expected.copy(phase = Phase.READY))
    }

    private suspend fun read(backend: DurableByteStore): Fence? {
        val bytes = backend.read(StorageArea.CONTROL, KEY) ?: return null
        return try {
            json.decodeFromString(Fence.serializer(), bytes.decodeToString()).also { fence ->
                if (fence.storageFormat != FORMAT || fence.version != 1 || fence.epoch <= 0 ||
                    fence.id.length != 48 || fence.id.any { it !in '0'..'9' && it !in 'a'..'f' }) {
                    throw StorageException("read reset barrier", StorageException.Kind.CORRUPT)
                }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (failure: StorageException) { throw failure }
        catch (failure: Exception) { throw StorageException("read reset barrier", StorageException.Kind.CORRUPT, failure) }
    }

    private suspend fun legacyEpoch(backend: DurableByteStore): Long {
        // Compatibility read only: old installations did not persist a begin-reset barrier.
        // This cannot retrospectively prove whether a pre-barrier reset was interrupted. Do not
        // write a READY receipt on restore, relabel old records, or claim new-format provenance.
        val raw = backend.read(StorageArea.NAVIGATION, LEGACY_KEY) ?: return 0
        return raw.decodeToString().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw StorageException("read reset epoch", StorageException.Kind.CORRUPT)
    }

    private suspend fun writeVerified(backend: DurableByteStore, fence: Fence) {
        val bytes = json.encodeToString(Fence.serializer(), fence).encodeToByteArray()
        withContext(NonCancellable) {
            var writeFailure: Exception? = null
            try { backend.write(StorageArea.CONTROL, KEY, bytes) }
            catch (failure: Exception) { writeFailure = failure }
            try {
                val observed = backend.read(StorageArea.CONTROL, KEY)
                if (observed == null || !observed.contentEquals(bytes)) {
                    throw StorageException("verify reset barrier", StorageException.Kind.CORRUPT)
                }
            } catch (proofFailure: Exception) {
                val primary = writeFailure
                if (primary == null) throw proofFailure
                if (proofFailure is CancellationException && primary !is CancellationException) {
                    if (proofFailure !== primary) proofFailure.addSuppressed(primary)
                    throw proofFailure
                }
                if (primary !== proofFailure) primary.addSuppressed(proofFailure)
                throw primary
            }
            writeFailure?.let { failure ->
                if (failure is CancellationException) throw failure
                AppLog.info("DurableResetBarrier", "reset_fence_ack_recovered", mapOf(
                    "generation" to fence.epoch.toString(), "phase" to fence.phase.name))
            }
        }
        // A cancellation that arrived during durable readback remains control flow, including
        // after READY was positively committed. It must not be reported as an unknown commit.
        currentCoroutineContext().ensureActive()
    }
}
