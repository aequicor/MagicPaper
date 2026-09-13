package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.logging.AppLog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PersistenceDiagnosticsTest {
    @Test fun handledFailureIsLoggedOnceWithCauseTypesAndWithoutPrivateExceptionContent() {
        val original = IllegalStateException("private answer and sk-secret-key-in-provider-message")
        val failure = StorageException("write draft", StorageException.Kind.WRITE, original)
        logPersistenceFailure("PersistenceTest", "safe_failure_regression", failure, mapOf("version" to "4"))
        logPersistenceFailure("PersistenceTest", "safe_failure_regression", failure)
        val entries = AppLog.history().filter { it.event == "safe_failure_regression" }
        assertEquals(1, entries.size)
        assertSame(original, failure.cause)
        assertTrue(entries.single().causeTypes.contains("IllegalStateException"))
        assertFalse(entries.single().line().contains("private answer"))
        assertFalse(entries.single().line().contains("sk-secret"))
    }
}
