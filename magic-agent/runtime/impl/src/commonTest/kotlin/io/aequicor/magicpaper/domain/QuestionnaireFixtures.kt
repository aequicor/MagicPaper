package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal

fun testQuestionnaires(): RuntimeQuestionnaireService = DefaultRuntimeQuestionnaireService(InMemoryEventJournal(), "test")
fun testQuestionnaireFactory(): RuntimeQuestionnaireFactory = DefaultRuntimeQuestionnaireFactory(InMemoryEventJournal())
