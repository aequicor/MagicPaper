package io.aequicor.magicpaper.domain

import kotlin.test.*

class PlanningDiagnosticsTest {
    @Test fun stripsKnownAndLabelledCredentialsButKeepsDiagnosis() {
        val redacted = PlanningDiagnostics.redact("HTTP 401 Authorization: Bearer abc123\napi_key=secret-key\nFailed with private-secret", setOf("private-secret"))
        assertTrue("HTTP 401" in redacted)
        listOf("abc123", "secret-key", "private-secret").forEach { assertFalse(it in redacted) }
    }
}
