package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.MAX_QUESTIONS_PER_REQUEST
import io.aequicor.magicpaper.domain.PlanningQuestion
import io.aequicor.magicpaper.domain.QuestionKind
import io.aequicor.magicpaper.domain.questionnaireProblem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * One contract for both questionnaire surfaces: the application `questionnaire` tool served through
 * [ToolHost] and the engine MCP bridge in `QuestionnaireTool`. The schema is authored here instead of
 * generated from the serializer descriptor because the model must see the limits that the validator
 * applies; an invisible limit turns a normal call into a rejected one and the agent starts guessing
 * about unrelated causes such as the language of the text.
 *
 * Answer policy (`allowCustomInput`, `canSkip`, `secret`, option `enabled`) stays with the host:
 * the fields remain accepted so that older or engine-side calls still decode, but [prepare] rewrites
 * them. A model cannot weaken the user's ability to answer or mark an answer as secret.
 */
object QuestionnaireContract {

    /** Registered names differ per surface; the model must use the exact one it was given. */
    const val toolNames = "questionnaire, magicpaper_questionnaire or an MCP-prefixed variant of them"

    val instructions = "When you need a clarification or a decision from the user, call the questionnaire tool " +
        "instead of asking only in prose. Its registered name depends on how this session exposes the application " +
        "bridge ($toolNames) — use the exact name from your available tools and never invent another one. " +
        "Ask up to $MAX_QUESTIONS_PER_REQUEST questions per call; continue with another call when more are needed. " +
        "Every question needs a unique non-empty id and a title. Question titles, labels and descriptions may be " +
        "written in any language and contain any punctuation: only ids are structural. " +
        "Use kind SINGLE or MULTIPLE with concrete options, or kind TEXT for a free-form answer. " +
        "Supply concise questions with answer options when useful. The user may add text or skip a clarification. " +
        "Wait for the confirmed answers; never interpret silence as consent. This tool cannot authorize commands " +
        "or grant permissions. If exec returns Script running with cell ID, the questionnaire is still pending: " +
        "keep calling wait for that cell until confirmed answers arrive. Do not send a final response while any " +
        "questionnaire or application tool call is pending; ending the turn cancels pending tool calls."

    val description = "Уточнение у пользователя: $MAX_QUESTIONS_PER_REQUEST вопроса за вызов, дальше — новый вызов. " +
        "Каждому вопросу нужны уникальный непустой id и title; текст вопроса и вариантов пишется на любом языке. " +
        "SINGLE/MULTIPLE требуют варианты, TEXT — свободный ответ. Ожидает подтверждённых ответов; " +
        "не выдаёт разрешений и не подтверждает действия."

    /** Constraints of [problem] are visible here so the engine can reject a bad call before it runs. */
    val schema: JsonObject = Json.parseToJsonElement("""
        {"type":"object","additionalProperties":false,"required":["questions"],"properties":{
          "questions":{"type":"array","minItems":1,"maxItems":$MAX_QUESTIONS_PER_REQUEST,
            "description":"One to $MAX_QUESTIONS_PER_REQUEST questions. Use another call for the rest.",
            "items":{"type":"object","additionalProperties":false,"required":["id","title"],"properties":{
              "id":{"type":"string","description":"Unique non-empty id; keep it stable when retrying the same call"},
              "title":{"type":"string","description":"Question text for the user, in any language"},
              "kind":{"type":"string","enum":["SINGLE","MULTIPLE","TEXT"],
                "description":"SINGLE/MULTIPLE need options; TEXT is a free-form answer"},
              "options":{"type":"array","maxItems":8,"description":"Choices for SINGLE/MULTIPLE; at least two give a real choice",
                "items":{"type":"object","additionalProperties":false,"required":["id","label"],"properties":{
                  "id":{"type":"string","description":"Unique non-empty option id inside this question"},
                  "label":{"type":"string","description":"Option text for the user, in any language"},
                  "description":{"type":"string","description":"Short consequence of this option, in any language"},
                  "enabled":{"type":"boolean","description":"Optional; the application presents every option"}
                }}},
              "allowCustomInput":{"type":"boolean","description":"Optional; a custom answer is always allowed"},
              "canSkip":{"type":"boolean","description":"Optional; skipping is always allowed"},
              "secret":{"type":"boolean","description":"Optional; ignored for runtime questions"}
            }}}}}
    """).jsonObject

    /** Host-owned policy applied before validation, so a lonely option cannot fail a whole call. */
    fun prepare(questions: List<PlanningQuestion>): List<PlanningQuestion> = questions.map { question ->
        question.copy(
            kind = if (question.options.isEmpty()) QuestionKind.TEXT else question.kind,
            allowCustomInput = true, canSkip = true, secret = false,
            options = question.options.map { it.copy(enabled = true) },
        )
    }

    /** Null when the user can actually answer; otherwise one actionable sentence for the model. */
    fun problem(questions: List<PlanningQuestion>): String? =
        if (questions.isEmpty()) "Добавьте хотя бы один вопрос: пустой опросник пользователь не увидит."
        else questions.questionnaireProblem()

    /** Validates the prepared list and returns it, or rejects the call with the concrete problem. */
    fun ready(questions: List<PlanningQuestion>): List<PlanningQuestion> {
        val prepared = prepare(questions)
        problem(prepared)?.let { throw ToolArgumentRejection(it) }
        return prepared
    }
}
