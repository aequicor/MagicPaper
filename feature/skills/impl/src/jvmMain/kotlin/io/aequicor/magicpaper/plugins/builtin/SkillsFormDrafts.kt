package io.aequicor.magicpaper.plugins.builtin

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Native form ownership is independent of panel composition. No DTO represents an accepted action. */
internal class SkillsFormDrafts(private val repository: DraftRepository, private val scope: CoroutineScope) {
    private val owners = mutableMapOf<String, PersistentDraftValue<*>>()
    private val removedProjects = mutableSetOf<String>()
    private var paused = false
    private var actionGeneration = 0L
    private val actions = mutableMapOf<String, SkillFormAction>()
    private val mutableLifecycle = kotlinx.coroutines.flow.MutableStateFlow(0L)
    val lifecycle: kotlinx.coroutines.flow.StateFlow<Long> = mutableLifecycle
    fun action(key: String, component: String, projectId: String? = null): SkillFormAction = actions.getOrPut(key) {
        val generation = actionGeneration
        SkillFormAction(scope, { generation == actionGeneration && !paused && (projectId == null || projectId !in removedProjects) }, component, projectId)
    }
    private fun <T> owner(key: String, serializer: kotlinx.serialization.KSerializer<T>, initial: T): PersistentDraftValue<T> {
        @Suppress("UNCHECKED_CAST")
        return owners.getOrPut(key) { PersistentDraftValue(repository, key, serializer, initial, scope) } as PersistentDraftValue<T>
    }
    fun imports() = owner("skills:library:import", SkillImportForm.serializer(), SkillImportForm())
    fun text() = owner("skills:library:text", SkillTextForm.serializer(), SkillTextForm())
    fun backup() = owner("skills:library:backup", SkillBackupForm.serializer(), SkillBackupForm())
    fun library() = owner("skills:library:selection", SkillLibraryForm.serializer(), SkillLibraryForm())
    fun activation() = owner("skills:library:activation", SkillActivationForm.serializer(), SkillActivationForm())
    fun review(key: String, checksum: String) = owner("skills:review:$key:$checksum", SkillReviewForm.serializer(), SkillReviewForm())
    fun project(projectId: String): PersistentDraftValue<ProjectSkillForm> {
        check(projectId !in removedProjects) { "Project skill draft owner was removed" }
        return owner("skills:project:$projectId:selection", ProjectSkillForm.serializer(), ProjectSkillForm())
    }
    fun catalog(projectId: String) = projectOwner(projectId, "skills:project:$projectId:catalog", SkillCatalogForm.serializer(), SkillCatalogForm())
    fun rollback(projectId: String) = projectOwner(projectId, "skills:project:$projectId:rollback", SkillActivationForm.serializer(), SkillActivationForm())
    private fun <T> projectOwner(projectId: String, key: String, serializer: kotlinx.serialization.KSerializer<T>, initial: T): PersistentDraftValue<T> {
        check(projectId !in removedProjects) { "Project skill draft owner was removed" }
        return owner(key, serializer, initial)
    }
    fun available(projectId: String) = !paused && projectId !in removedProjects
    suspend fun flush() = drain { it.flushDrafts() }
    suspend fun prepareForReset() {
        paused = true; mutableLifecycle.value++
        drain { it.prepareForReset() }
    }
    private suspend fun drain(finishOwner: suspend (PersistentDraftValue<*>) -> Unit) {
        var failure: Exception? = null
        try { awaitActions(actions.values.toList()) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { failure = error }
        try { forEachOwner(finishOwner) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) { if (failure == null) failure = error else failure.addSuppressed(error) }
        failure?.let { throw it }
    }
    private suspend fun awaitActions(values: List<SkillFormAction>) {
        var failure: Exception? = null
        values.forEach { action ->
            try { action.awaitIdle() } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
    private suspend fun forEachOwner(action: suspend (PersistentDraftValue<*>) -> Unit) {
        var failure: Exception? = null
        owners.values.toList().forEach { owner ->
            try { action(owner) }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { if (failure == null) failure = error else failure?.addSuppressed(error) }
        }
        failure?.let { throw it }
    }
    fun resumeAfterReset() {
        // Old delegates stay paused, and old action callbacks keep a retired generation.
        actionGeneration++; owners.clear(); actions.clear()
        removedProjects.clear(); paused = false; mutableLifecycle.value++
    }
    suspend fun removeProject(projectId: String) {
        removedProjects += projectId; mutableLifecycle.value++
        awaitActions(actions.values.filter { it.projectId == projectId })
        val prefix = "skills:project:$projectId:"
        val cached = owners.filterKeys { it.startsWith(prefix) }
        // Revoke first: an old panel's callbacks and queued autosaves cannot resurrect removed data.
        cached.values.forEach { it.draft.revoke(); it.prepareForReset() }
        (repository.keys(prefix) + cached.keys).toSet().forEach { repository.remove(it) }
        cached.keys.forEach(owners::remove)
    }
}

/** A local delegate keeps the existing Paper fields concise while writes stay with their owner. */
internal fun <T, V> PersistentDraftValue<T>.field(
    @Suppress("UNUSED_PARAMETER") state: DraftSessionState<T>, read: (T) -> V, write: T.(V) -> T,
): ReadWriteProperty<Any?, V> = object : ReadWriteProperty<Any?, V> {
    override fun getValue(thisRef: Any?, property: KProperty<*>): V = read(draft.state.value.value)
    override fun setValue(thisRef: Any?, property: KProperty<*>, value: V) = update { it.write(value) }
}

@Serializable internal data class SkillImportForm(
    val kind: SkillImportKind = SkillImportKind.LOCAL_DIRECTORY, val location: String = "", val version: String = "1.0.0",
    val skillId: String = "", val name: String = "", val description: String = "", val revision: String = "", val origins: String = "", val network: Boolean = false,
)
@Serializable internal data class SkillTextForm(
    val readyText: String = "", val skillId: String = "", val version: String = "1.0.0", val name: String = "", val description: String = "",
    val previewChecksum: String? = null, val confirmed: Boolean = false,
)
@Serializable internal data class SkillBackupForm(val path: String = "", val hash: String = "", val confirmed: Boolean = false)
@Serializable internal data class SkillLibraryForm(val query: String = "", val reviewKey: String? = null, val reviewChecksum: String? = null)
@Serializable internal data class SkillReviewForm(val evidence: String = "", val origin: Boolean = false, val license: Boolean = false, val content: Boolean = false)
@Serializable internal data class SkillActivationForm(
    val target: Map<String, String>? = null, val generation: Long = -1, val checksums: Map<String, String> = emptyMap(),
    val permissions: Set<SkillPermission> = emptySet(), val details: String = "", val changes: Boolean = false, val permissionConsent: Boolean = false,
) {
    fun matches(snapshot: SkillReleaseSnapshot): Boolean = generation == snapshot.generation && checksums.all { (key, hash) -> snapshot.installed[key]?.pkg?.checksum == hash }
    fun consent() = SkillActivationConsent(generation, checksums, changes, if (permissionConsent) permissions else emptySet())
}
@Serializable internal data class ProjectSkillForm(
    val pending: Map<String, String>? = null, val generation: Long = -1, val permissionConsent: Boolean = false, val trustedTextConsent: Boolean = false,
    val catalogOpen: Boolean = false, val connectKey: String? = null,
) {
    fun matches(snapshot: SkillReleaseSnapshot): Boolean = generation == snapshot.generation && pending.orEmpty().all { (key, hash) -> snapshot.installed[key]?.pkg?.checksum == hash }
}
@Serializable internal data class SkillCatalogForm(
    val query: String = "", val link: String = "", val network: Boolean = false, val reviewKey: String? = null, val reviewChecksum: String? = null,
)
