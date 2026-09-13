package io.aequicor.magicpaper.navigation

import io.aequicor.magicpaper.designsystem.PaperDialogLifecycle

/** Every feature modal participates in the same Decompose ChildSlot as shell dialogs. */
class RootDialogLifecycle(private val root: RootComponent<*>) : PaperDialogLifecycle {
    private val dismissals = linkedMapOf<String, () -> Unit>()
    private val routes = mutableMapOf<String, DialogRoute>()
    private var displayedId: String? = null
    private val subscription = root.dialogSlot.subscribe { slot ->
        val next = slot.child?.configuration?.takeIf { it.kind == KIND }?.entityId
        val previous = displayedId
        displayedId = next
        if (previous != null && previous != next) {
            routes.remove(previous)
            dismissals.remove(previous)?.invoke()
        }
    }

    override fun register(id: String, onDismiss: () -> Unit) {
        dismissals[id] = onDismiss
        val route = routes.getOrPut(id) { DialogRoute(KIND, id) }
        root.showDialog(route)
    }

    override fun unregister(id: String) {
        dismissals.remove(id)
        routes.remove(id)?.let(root::dismissDialog)
    }

    fun close() { subscription.cancel(); dismissals.clear(); routes.clear() }

    private companion object { const val KIND = "feature-modal" }
}
