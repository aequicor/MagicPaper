package io.aequicor.magicpaper.domain

import kotlin.test.*
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.CodingSessionUi

class CodingSessionNamingTest {
    @Test fun automaticNamesPreserveManualAndWorkerNames() {
        val session = CodingSession("s", "p", "Сессия 1", 1)
        assertEquals("🗓️ Найти ошибку", session.namedFromPrompt("  Найти ошибку\nПодробности").name)
        assertEquals(session, session.namedFromPrompt(" "))
        val manual = session.copy(nameManuallySet = true)
        assertEquals(manual, manual.namedFromPrompt("Запрос"))
        val worker = session.copy(parentSessionId = "parent")
        assertEquals(worker, worker.namedFromPrompt("Запрос"))
    }

    @Test fun newSessionTitleIsReplacedByFirstPrompt() {
        val session = CodingSession("s", "p", "Новая сессия", 1)
        assertEquals("🗓️ Исправить меню", session.namedFromPrompt("Исправить меню").name)
        val manual = session.copy(nameManuallySet = true)
        assertEquals(manual, manual.namedFromPrompt("Исправить меню"))
    }

    @Test fun modelNamingOwnsTheTitleWhileALocalSummaryIsOnlyAFallback() {
        val session = CodingSession("s", "p", "Новая сессия", 1)
        // Пока модель может озаглавить запрос, список ждёт её ответа, а не обрезка первой строки.
        assertEquals(session, session.namedFromPrompt("Исправить меню", localSummaryAllowed = false))
    }

    @Test fun pastedLogsAndCodeNeverBecomeSessionNames() {
        val session = CodingSession("s", "p", "Новая сессия", 1)
        assertEquals(session, session.namedFromPrompt("""{"time":1789370239679,"level":"ERROR","msg":"boom"}"""))
        assertEquals("🗓️ Автонейминг создаёт названия сессий",
            session.namedFromPrompt("""{"time":1789370239679,"level":"ERROR"}
                |Автонейминг создаёт названия сессий, не закрывающие задачу
                |""".trimMargin()).name)
    }

    @Test fun localSummaryKeepsWholeWordsAndOneSentence() {
        assertEquals("Найти причину падения сбора",
            localSessionSummary("Найти причину падения сбора \nподробный лог ниже"))
        assertEquals("Почему тест падает", localSessionSummary("Почему тест падает. Потому что"))
        assertEquals("Рефакторинг", localSessionSummary("Рефакторинг"))
        assertNull(localSessionSummary("val x = foo();"))
        assertNull(localSessionSummary("""[{"a":1}]"""))
        assertNull(localSessionSummary("/home/user/project/build.log"))
    }

    @Test fun everyStartedRootIsWorthOneTitleButWorkersAreNot() {
        val root = CodingSession("s", "p", "Новая сессия", 1)
        assertTrue(root.needsShortTitle())
        assertTrue(root.copy(planningMode = true).needsShortTitle())
        assertFalse(root.copy(parentSessionId = "parent").needsShortTitle())
        assertFalse(root.copy(shortTitle = "Готово").needsShortTitle())
        assertFalse(root.copy(nameManuallySet = true).needsShortTitle())
        assertFalse(root.copy(sessionKind = SessionKind.IMMUNITY).needsShortTitle())
        assertFalse(root.copy(archived = true).needsShortTitle())
        assertEquals("🗓️ Найти ошибку", root.copy(shortTitle = "Найти ошибку").sidebarTitle())
    }

    @Test fun modelAnswerIsStrippedToItsTaskWords() {
        assertEquals("Найти ошибку", compactSessionTitle("🗓️ «Найти ошибку»"))
        assertEquals("Исправить меню", compactSessionTitle("- **Исправить меню**"))
        assertNull(compactSessionTitle("   "))
    }

    @Test fun sidebarPlacesNewSessionsFirstRegardlessOfInsertionOrder() {
        val older = CodingSession("older", "p", "Older", 1)
        val newer = older.copy(id = "newer", createdAt = 2)
        val otherProject = older.copy(id = "other", projectId = "other", createdAt = 3)
        val ui = CodingUi(sessions = listOf(older, otherProject, newer).map { CodingSessionUi(it) })
        assertEquals(listOf("newer", "older"), ui.sessionsOf("p").map { it.session.id })
    }

    @Test fun descendantsIncludeArchivedAndNestedWorkersOnly() {
        val parent = CodingSession("parent", "p", "Parent", 1)
        val worker = parent.copy(id = "worker", parentSessionId = parent.id, archived = true)
        val nested = parent.copy(id = "nested", parentSessionId = worker.id)
        val sibling = parent.copy(id = "sibling")
        assertEquals(setOf("parent", "worker", "nested"), listOf(parent, worker, nested, sibling).sessionTreeIds(parent.id))
    }
}
