package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistenceDiagnosticsTest {
    @Test fun handledFailureIsLoggedOnceWithSanitizedCauseContent() {
        val original = IllegalStateException("private answer and sk-secret-key-in-provider-message")
        val failure = StorageException("write draft", StorageException.Kind.WRITE, original)
        logPersistenceFailure("PersistenceTest", "safe_failure_regression", failure, mapOf("version" to "4"))
        logPersistenceFailure("PersistenceTest", "safe_failure_regression", failure)
        val entries = AppLog.history().filter { it.event == "safe_failure_regression" }
        assertEquals(1, entries.size)
        assertSame(original, failure.cause)
        val entry = entries.single()
        assertTrue(entry.causeTypes.contains("IllegalStateException"))
        assertEquals("Storage write draft failed (WRITE)", entry.causeMessage,
            "The sanitized cause message stays available for diagnostics")
        assertFalse(entry.line().contains("sk-secret"), "Credentials are redacted at every level")
        assertTrue(entry.causeStack != null, "The stack trace is recorded for the handling owner")
    }
}
