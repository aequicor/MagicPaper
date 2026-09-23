package org.w3c.dom.events

/**
 * Compose Foundation's JS clipboard support links this legacy DOM SAM factory.
 * kotlinx-browser 0.5 supplies the DOM classes but omits the factory; including
 * kotlin-dom-api-compat as well binds EventListener twice in Kotlin 2.4.
 * Keep this exact public signature for that binary caller.
 */
@Suppress("UNUSED_PARAMETER")
fun EventListener(handler: (Event) -> Unit): EventListener = js("({ handleEvent: handler })")
