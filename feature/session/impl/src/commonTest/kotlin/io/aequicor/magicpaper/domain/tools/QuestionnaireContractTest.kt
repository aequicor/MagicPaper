package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

/**
 * The questionnaire has two surfaces (application tool and engine MCP bridge) and both must accept
 * the same payload. Text of a question is user-facing: a rejected call that reads "invalid questions"
 * sends the agent searching for a cause in the language of the text instead of the structure.
 */
class QuestionnaireContractTest {
    private val context = ToolExecutionContext("p", "s", "s", "request", ToolRole.CHAT, CodingInteractionMode.CODE)

    private fun payload(vararg titles: String, kind: String = "SINGLE", options: Int = 2,
                        extra: String = ""): JsonObject {
        val choices = (0 until options).joinToString(",") { """{"id":"o$it","label":"Вариант $it","description":"Пояснение $it","enabled":true}""" }
        val questions = titles.indices.joinToString(",") { index ->
            """{"id":"q$index","title":"${titles[index]}","kind":"$kind","options":[$choices]$extra}"""
        }
        return Json.parseToJsonElement("""{"questions":[$questions]}""").jsonObject
    }

    private fun question(id: String, title: String = "Вопрос?", kind: QuestionKind = QuestionKind.SINGLE,
                         options: List<QuestionOption> = listOf(QuestionOption("a", "Да"), QuestionOption("b", "Нет"))) =
        PlanningQuestion(id, title, kind, options)

    @Test fun tooManyQuestionsReportTheLimitAndTheRecoveryPath() {
        val problem = listOf(
            question("a"), question("b"), question("c"), question("d"),
        ).questionnaireProblem()
        assertNotNull(problem)
        assertContains(problem, "4")
        assertContains(problem, "${MAX_QUESTIONS_PER_REQUEST}")
        assertContains(problem.lowercase(), "разбейте")
    }

    @Test fun structuralProblemsNameTheBrokenQuestion() {
        assertContains(listOf(question("dup"), question("dup")).questionnaireProblem().orEmpty(), "dup")
        assertContains(listOf(question("t", title = "  ")).questionnaireProblem().orEmpty(), "title")
        assertContains(listOf(question("o", options = listOf(QuestionOption("x", "Да"), QuestionOption("x", "Тоже да"))))
            .questionnaireProblem().orEmpty(), "варианта")
        assertNull(listOf(question("ok")).questionnaireProblem())
        assertNull(emptyList<PlanningQuestion>().questionnaireProblem())
    }

    @Test fun freeTextIsNotAValidationProblemAndLonelyOptionsBecomeFreeText() {
        val cyrillic = listOf(
            question("signing", "Подписывать коммиты личным ключом? (GitHub покажет Unverified)"),
            question("isolation", "Как защитить пет-проекты — «includeIf» или локальные настройки?"),
            question("branch", "Удалить ветку fix/restore-child-sessions-after-crash_DEL?"),
        )
        assertNull(cyrillic.questionnaireProblem())
        val prepared = QuestionnaireContract.prepare(listOf(
            question("single", "Оставить как есть?", options = listOf(QuestionOption("keep", "Оставить"))),
            question("text", "Свой вариант?", options = emptyList()),
        ))
        assertEquals(QuestionKind.SINGLE, prepared[0].kind)
        assertEquals(QuestionKind.TEXT, prepared[1].kind)
        assertTrue(prepared.all { !it.secret && it.canSkip && it.allowCustomInput })
    }

    @Test fun schemaAdvertisesEveryFieldTheDecoderAcceptsAndTheSameLimits() {
        validateToolArguments(QuestionnaireContract.schema, payload("Первый?", "Второй?", "Третий?",
            extra = ""","allowCustomInput":true,"canSkip":false,"secret":true"""))
        assertFailsWith<IllegalArgumentException> { validateToolArguments(QuestionnaireContract.schema, payload("a", "b", "c", "d")) }
        assertFailsWith<IllegalArgumentException> { validateToolArguments(QuestionnaireContract.schema, Json.parseToJsonElement("""{"questions":[]}""").jsonObject) }
        assertFailsWith<IllegalArgumentException> { validateToolArguments(QuestionnaireContract.schema, Json.parseToJsonElement("""{"questions":[{"id":"q","title":"?","forged":true}]}""").jsonObject) }
        val questions = QuestionnaireContract.schema.getValue("properties").jsonObject.getValue("questions").jsonObject
        assertEquals(MAX_QUESTIONS_PER_REQUEST, questions["maxItems"]!!.jsonPrimitive.int)
        assertEquals(1, questions["minItems"]!!.jsonPrimitive.int)
    }

    @Test fun instructionsNameTheToolWithoutInventingASingleWireName() {
        val contract = QuestionnaireContract.instructions
        assertContains(contract, "magicpaper_questionnaire")
        assertContains(contract, "questionnaire")
        assertContains(contract.lowercase(), "any language")
        assertContains(contract.lowercase(), "never invent another")
        assertContains(QuestionnaireContract.description, "${MAX_QUESTIONS_PER_REQUEST}")
    }

    @Test fun rejectedCallReturnsTheConcreteProblemToTheAgent() = runTest {
        val host = ToolHost(MemoryToolReceiptStore())
        val tools = host.session(context)
        // Structure the schema cannot express (a repeated id) still has to produce a concrete correction.
        val duplicate = Json.parseToJsonElement("""{"questions":[
            {"id":"q","title":"Первый?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]},
            {"id":"q","title":"Второй?","kind":"SINGLE","options":[{"id":"x","label":"Да"},{"id":"y","label":"Нет"}]}]}""").jsonObject
        val failure = assertFailsWith<IllegalArgumentException> { tools.call("duplicate", "questionnaire", duplicate) }
        assertContains(failure.message.orEmpty(), "повторяется")
        assertContains(failure.message.orEmpty(), "q")
        assertTrue(host.questions.requests.value.isEmpty(), "A rejected questionnaire must not reach the user")
        // The declared limit is enforced before the receiver runs, so the call never reaches the user.
        val overflow = assertFailsWith<IllegalArgumentException> { tools.call("overflow", "questionnaire", payload("a", "b", "c", "d")) }
        assertContains(overflow.message.orEmpty(), "questions")
        assertTrue(host.questions.requests.value.isEmpty())
    }

    @Test fun acceptedCallInRussianWaitsAndReturnsConfirmedAnswers() = runTest {
        val host = ToolHost(MemoryToolReceiptStore())
        val tools = host.session(context)
        val job = async { tools.call("ask", "questionnaire", payload("Подписывать коммиты?")) }
        runCurrent()
        val request = host.questions.requests.value.single()
        assertEquals("Подписывать коммиты?", request.questions.single().title)
        host.questions.respond(request.id, listOf(PlanningAnswer("q0", selected = listOf("o0"))))
        assertContains(job.await().toString(), "o0")
    }
}
