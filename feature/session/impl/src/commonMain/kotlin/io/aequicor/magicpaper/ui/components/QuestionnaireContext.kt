package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.staticCompositionLocalOf
import io.aequicor.magicpaper.domain.Attachment
import io.aequicor.magicpaper.domain.ComposerDraftData
import io.aequicor.magicpaper.domain.InteractionKind
import io.aequicor.magicpaper.data.storage.DraftSession
import io.aequicor.magicpaper.data.storage.StorageException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal val LocalOpenQuestionnaire = staticCompositionLocalOf<(InteractionKind, String) -> Unit> { { _, _ -> } }

class CodingComposerDraft(
    private val session: DraftSession<ComposerDraftData>? = null,
    scope: CoroutineScope? = null,
) {
    private var hydrating = false
    private var edits = 0L
    private val textState = mutableStateOf("")
    private val attachmentsState = mutableStateOf<List<Attachment>>(emptyList())
    val error = mutableStateOf<StorageException?>(null)
    val text: MutableState<String> = DraftMutableState(textState) { persist() }
    val attachments: MutableState<List<Attachment>> = DraftMutableState(attachmentsState) { persist() }
    val version: Long get() = session?.state?.value?.version ?: edits

    init {
        if (session != null) checkNotNull(scope).launch {
            session.state.collect { saved ->
                if (saved.version == session.state.value.version && saved.loaded) {
                    hydrating = true
                    textState.value = saved.value.text
                    attachmentsState.value = saved.value.attachments
                    hydrating = false
                }
                error.value = saved.error
            }
        }
    }

    private fun persist() {
        if (hydrating) return
        edits++
        session?.update(ComposerDraftData(textState.value, attachmentsState.value))
    }

    suspend fun awaitSaved() { session?.awaitSaved() }
    fun revoke() { session?.revoke() }

    suspend fun clearIfUnchanged(expectedVersion: Long): Boolean {
        if (session != null) return session.clearIfUnchanged(expectedVersion)
        if (expectedVersion != edits) return false
        textState.value = ""
        attachmentsState.value = emptyList()
        edits++
        return true
    }
}

private class DraftMutableState<T>(private val state: MutableState<T>, private val changed: () -> Unit) : MutableState<T> {
    override var value: T
        get() = state.value
        set(value) { if (state.value != value) { state.value = value; changed() } }
    override fun component1(): T = value
    override fun component2(): (T) -> Unit = { value = it }
}
