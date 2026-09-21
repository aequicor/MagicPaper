package io.aequicor.magicpaper.data.computer

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.domain.ComputerAccess
import kotlinx.coroutines.*
import kotlin.test.*

class ComputerFailureTest {
    @Test fun arbitraryNativePermissionCaptureAndApplicationErrorsNeverReachUiOrToolText(): Unit = runBlocking {
        val secret = "private native response body"
        val fake = FakeComputerDesktop()
        val desktop = object : ComputerDesktop by fake {
            override fun checkPermissions(access: ComputerAccess, request: Boolean) { error(secret) }
        }
        val computer = testComputer(desktop)
        try {
            computer.enableForTest("session", ComputerAccess.CONTROL)
            assertNull(computer.grant("session"))
            assertTrue(computer.state.value.error)
            assertFalse(computer.state.value.detail.orEmpty().contains(secret))
        } finally { computer.close() }
        val native = FakeApplicationDesktop().apply { before = { throw IllegalArgumentException(secret) } }
        val owner = testComputer(fake, applicationFactory = { native })
        try {
            owner.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            val lease = checkNotNull(owner.begin("session", "request"))
            fake.onCapture = { error(secret) }
            for (reply in listOf(owner.execute("session", lease, request("screenshot")),
                owner.executeApplication("session", lease, request("windows")))) {
                assertTrue(reply.failed())
                assertFalse(reply.toString().contains(secret))
            }
            assertTrue(owner.state.value.error)
            assertFalse(owner.state.value.detail.orEmpty().contains(secret))
        } finally { owner.close() }
    }

    @Test fun failedResourceReleaseIsVisibleRevokesImmediatelyAndBlocksNewAcquisition(): Unit = runBlocking {
        val journal = InMemoryEventJournal()
        val fake = FakeComputerDesktop()
        var failClose = true
        var desktopClosed = 0
        var applicationClosed = 0
        val desktop = object : ComputerDesktop by fake {
            override fun close() { desktopClosed++; if (failClose) error("private desktop cleanup body") }
        }
        val application = object : ApplicationDesktop by FakeApplicationDesktop() {
            override fun close() { applicationClosed++; if (failClose) error("private application cleanup body") }
        }
        val owner = testComputer(desktop, { application }, journal)
        try {
            owner.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            val lease = checkNotNull(owner.begin("session", "request"))
            val policy = checkNotNull(owner.capturePolicy())
            assertFalse(owner.executeApplication("session", lease, request("windows")).failed())
            owner.disable()
            assertNull(owner.grant("session"))
            assertTrue(owner.state.value.error)
            assertContains(owner.state.value.detail.orEmpty(), "Перезапустите")
            assertFalse(owner.state.value.detail.orEmpty().contains("private"))
            assertEquals(1, desktopClosed)
            assertEquals(1, applicationClosed)
            owner.configure(ComputerAccess.SCREEN, ComputerAccess.SCREEN)
            assertNull(owner.begin("session", "next-request"))
            assertNull(owner.capturePolicy())
            assertFalse(owner.enable("session", ComputerAccess.CONTROL, policy))
            assertNull(owner.grant("session"))
            withTimeout(5_000) {
                while (journal.streams().flatMap { journal.read(it) }.none { it.detail.contains("ReleaseFailed") }) delay(10)
            }
            assertFailsWith<IllegalStateException> { owner.prepareForReset() }
        } finally { failClose = false; owner.close() }
        assertEquals(2, applicationClosed)
    }

    @Test fun cancellationOfNativeCallSurvivesApplicationAndDesktopCleanupFailures(): Unit = runBlocking {
        val cancelled = CancellationException("Original cancellation")
        val native = FakeApplicationDesktop().apply { before = { throw cancelled } }
        var failClose = true
        val application = object : ApplicationDesktop by native {
            override fun close() { if (failClose) error("private application cleanup body") }
        }
        val desktop = object : ComputerDesktop by FakeComputerDesktop() {
            override fun close() { if (failClose) throw CancellationException("Cleanup cancellation must not replace original cancellation") }
        }
        val owner = testComputer(desktop, { application })
        try {
            owner.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            val lease = checkNotNull(owner.begin("session", "request"))
            val error = assertFailsWith<CancellationException> { owner.executeApplication("session", lease, request("windows")) }
            // Coroutine stack-trace recovery may copy CancellationException while retaining its cause.
            assertTrue(generateSequence(error as Throwable) { it.cause }.any { it === cancelled })
            assertTrue(cancelled.suppressedExceptions.isNotEmpty())
            assertNull(owner.grant("session"))
            assertTrue(owner.state.value.error)
            assertFalse(owner.state.value.detail.orEmpty().contains("private"))
        } finally { failClose = false; owner.close() }
    }

    @Test fun cancellationDuringCleanupIsNotHiddenBehindAnEarlierOrdinaryCleanupFailure(): Unit = runBlocking {
        val cancelled = CancellationException("Cleanup cancelled")
        var failClose = true
        val application = object : ApplicationDesktop by FakeApplicationDesktop() {
            override fun close() { if (failClose) error("Application cleanup failed") }
        }
        val desktop = object : ComputerDesktop by FakeComputerDesktop() {
            override fun close() { if (failClose) throw cancelled }
        }
        val owner = testComputer(desktop, { application })
        try {
            owner.configure(ComputerAccess.CONTROL, ComputerAccess.CONTROL)
            val lease = checkNotNull(owner.begin("session", "request"))
            owner.executeApplication("session", lease, request("windows"))
            assertSame(cancelled, assertFailsWith<CancellationException> { owner.disable() })
            assertTrue(cancelled.suppressedExceptions.any { it.message == "Application cleanup failed" })
            assertNull(owner.grant("session"))
            assertTrue(owner.state.value.error)
        } finally { failClose = false; owner.close() }
    }
}
