package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.data.storage.EventJournal
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.ComputerAccess
import kotlinx.coroutines.*

internal fun testComputer(desktop: ComputerDesktop, applicationFactory: () -> ApplicationDesktop = { NativeApplicationDesktop() },
    journal: EventJournal = InMemoryEventJournal(), clock: () -> Long = System::nanoTime) =
    DesktopComputerUse(desktop, journal, applicationFactory, clock).also { it.configure(ComputerAccess.OFF, ComputerAccess.OFF) }

/** A fixture explicitly installs its policy before enabling; production callers carry an older captured ref. */
internal suspend fun DesktopComputerUse.enableForTest(sessionId: String, access: ComputerAccess): Boolean {
    val ref = withTimeout(5_000) {
        while (capturePolicy() == null && !state.value.error) yield()
        checkNotNull(capturePolicy())
    }
    return enable(sessionId, access, ref)
}

/** Fixture setup is explicit; the production endpoint consumes an already bound lease. */
internal suspend fun DesktopComputerUse.testBridge(sessionId: String, requestId: String = "test-run"): ComputerUseBridge? {
    val lease = begin(sessionId, requestId) ?: return null
    return endpoint(lease, requestId) as ComputerUseBridge
}
