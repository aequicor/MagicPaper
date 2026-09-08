package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.CodingEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ExperienceResult(val label: String) {
    SUCCESS("Успех"), FAILURE("Ошибка"), CANCELLATION("Отмена"), UNKNOWN("Не проверено")
}
@Serializable
enum class ExperienceVerification { USER_CONFIRMED, PASSED, FAILED, UNAVAILABLE }

/** Application verifier only. Engine prose and tool output cannot construct positive evidence. */
data class SkillRunVerification(
    val verdict: ExperienceVerification = ExperienceVerification.UNAVAILABLE,
    val scenario: ExperienceScenario? = null,
    val features: Set<ExperienceFeature> = emptySet(),
) {
    init { require(verdict != ExperienceVerification.USER_CONFIRMED) }
}

fun interface SkillRunVerifier {
    suspend fun verify(runId: String): SkillRunVerification
}

/** Contains no project/session names, paths or source material. Generation is a deletion fence. */
@Serializable
@ConsistentCopyVisibility
data class SkillRunTicket internal constructor(val runId: String, val generation: String)

/** A stable opaque key, including for old checkpoints whose IDs are not UUIDs. */
fun skillRunIdentity(sessionId: String, requestId: String): String =
    UUID.nameUUIDFromBytes((sessionId.length.toString() + ":" + sessionId + requestId).encodeToByteArray()).toString()

/** Observe the complete flow, not each Finished callback. Finished/AgentEnd never mean success.
 * Verification is independent and sees only the opaque identity. No model/network is used by default.
 */
fun Flow<CodingEvent>.withSkillExperience(
    runId: String,
    journal: () -> LocalSkillExperience?,
    verifier: SkillRunVerifier = SkillRunVerifier { SkillRunVerification() },
    cancelled: () -> Boolean = { false },
): Flow<CodingEvent> = flow {
    val experience = journal()
    val ticket = try { experience?.beginRun(runId) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
    var terminal = ExperienceResult.UNKNOWN
    var finished = false
    try {
        collect { event ->
            if (event is CodingEvent.Failed) terminal = ExperienceResult.FAILURE
            if (event == CodingEvent.Finished) finished = true
            emit(event)
        }
    } catch (e: CancellationException) {
        terminal = ExperienceResult.CANCELLATION
        throw e
    } catch (e: Throwable) {
        terminal = ExperienceResult.FAILURE
        throw e
    } finally {
        if (ticket != null) withContext(NonCancellable) {
            if (cancelled()) terminal = ExperienceResult.CANCELLATION
            // A cancelled/failed/incomplete transport cannot receive positive task evidence.
            try {
                checkNotNull(experience).completeRun(ticket, terminal, if (finished && terminal == ExperienceResult.UNKNOWN) verifier
                    else SkillRunVerifier { SkillRunVerification() })
            } catch (_: Exception) {
                // Learning is optional; never replace a coding result or exception with journal errors.
            }
        }
    }
}
