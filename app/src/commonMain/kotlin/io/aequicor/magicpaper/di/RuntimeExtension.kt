package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.domain.AppSettings
import io.aequicor.magicpaper.domain.MediaGenerationOwner
import io.aequicor.magicpaper.domain.MediaKind
import io.aequicor.magicpaper.ui.SettingsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * An installed feature participates in application lifetime and reset. The common host
 * owns the order; the feature owns its resources. An absent feature has no registration.
 * UI routes and media authority use their own, narrower contracts.
 */
internal interface RuntimeExtension {
    val id: String
    /**
     * Replays the feature's own saved state before [start]. The host runs it beside the owners it starts after
     * assembly, so it reads only the hydrated settings and the feature's own storage, and launches nothing.
     */
    suspend fun restore() = Unit
    suspend fun start()
    fun updateConfiguration(state: SettingsState)
    suspend fun reload()
    suspend fun clearProfileOverrides(profileId: String)
    suspend fun prepareForReset()
    /**
     * [discardUnresolvable] carries the user's explicit consent to erase application data: before pausing, the
     * owner may drop execution evidence it can never resolve — a journal that no longer replays, an outcome no
     * saved completion can settle — which would otherwise refuse every reconciliation and release the reset needs.
     */
    suspend fun pauseForReset(discardUnresolvable: Boolean = false)
    suspend fun clearForReset()
    /**
     * Once the records are erased and before any owner resumes: deletes the files they owned — task worktrees,
     * engine session transcripts. A failure leaves no record pointing at a half-deleted folder; the next reset
     * finishes the deletion.
     */
    suspend fun eraseFilesForReset()
    suspend fun resumeAfterReset()
    /**
     * Once this owner resumed, only after [eraseFilesForReset]: lets external tools forget the erased files — Git's
     * registrations of deleted task worktrees. It runs commands, which a paused owner does not admit.
     */
    suspend fun pruneAfterReset()
    suspend fun close()
}

internal class RuntimeExtensions(extensions: List<RuntimeExtension>) {
    val owners = extensions.toList().also { owners ->
        require(owners.map { it.id }.distinct().size == owners.size) { "Duplicate runtime owner" }
    }
}

/** Resolves media authority only in the scope owned by an installed feature. */
internal interface MediaOwnerPolicy {
    fun owns(owner: MediaGenerationOwner): Boolean
    suspend fun exists(owner: MediaGenerationOwner): Boolean
    suspend fun allows(owner: MediaGenerationOwner, kind: MediaKind): Boolean
}

internal class MediaOwnerPolicies(policies: List<MediaOwnerPolicy>) {
    private val policies = policies.toList()
    fun resolve(owner: MediaGenerationOwner): MediaOwnerPolicy? {
        val matches = policies.filter { it.owns(owner) }
        check(matches.size <= 1) { "Media owner has multiple authorities" }
        return matches.singleOrNull()
    }
}

/** Complete every cleanup participant and retain the first cancellation as control flow. */
internal suspend fun completeRuntimeCleanup(vararg actions: suspend () -> Unit) {
    var first: Throwable? = null
    var cancellation: CancellationException? = null
    fun failed(failure: Throwable) {
        if (failure is CancellationException) {
            if (cancellation == null) cancellation = failure else if (cancellation !== failure) cancellation!!.addSuppressed(failure)
        } else if (first == null) first = failure else if (first !== failure) first!!.addSuppressed(failure)
    }
    withContext(NonCancellable) {
        actions.forEach { action -> try { action() } catch (failure: Throwable) { failed(failure) } }
    }
    try { currentCoroutineContext().ensureActive() } catch (failure: CancellationException) { failed(failure) }
    cancellation?.let { cancelled -> first?.let(cancelled::addSuppressed); throw cancelled }
    first?.let { throw it }
}
