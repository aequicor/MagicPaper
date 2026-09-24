package io.aequicor.magicpaper.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/** A failure's recovery is offered from the saved transcript, so it must survive the timeline and the log. */
class CodingRecoveryTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun failureRecoveryReachesTheLiveAndTheSavedErrorStep() {
        val recorder = CodingRunRecorder()
        val recovery = CodingRecovery.SignIn(CodingEngine.CLAUDE_CODE)
        recorder.apply(CodingEvent.Failed("Claude Code не авторизован.", recovery))
        assertEquals(recovery, recorder.timeline().single { it.kind == CodingStepKind.ERROR }.recovery)
        assertEquals(recovery, recorder.message("m", 0).steps.single { it.kind == CodingStepKind.ERROR }.recovery)
    }

    @Test fun plainFailureOffersNoRecovery() {
        val recorder = CodingRunRecorder()
        recorder.apply(CodingEvent.Failed("Claude Code завершился с кодом 7."))
        assertNull(recorder.timeline().single { it.kind == CodingStepKind.ERROR }.recovery)
    }

    @Test fun recoveryKeepsItsSerializedIdentityAndOldLogsStillDecode() {
        val step = CodingStep(CodingStepKind.ERROR, "Claude Code не авторизован.", ok = false,
            recovery = CodingRecovery.SignIn(CodingEngine.CLAUDE_CODE))
        val encoded = json.encodeToString(CodingStep.serializer(), step)
        assertTrue("\"type\":\"sign_in\"" in encoded && "\"engine\":\"CLAUDE_CODE\"" in encoded,
            "Saved logs name the recovery; renaming it is a data migration: $encoded")
        assertEquals(step, json.decodeFromString(CodingStep.serializer(), encoded))
        val legacy = json.decodeFromString(CodingStep.serializer(), """{"kind":"ERROR","title":"Ошибка","ok":false}""")
        assertNull(legacy.recovery)
    }
}
