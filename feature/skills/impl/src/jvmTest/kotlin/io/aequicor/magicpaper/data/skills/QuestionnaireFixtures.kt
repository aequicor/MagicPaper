package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.DefaultRuntimeQuestionnaireFactory
import io.aequicor.magicpaper.data.storage.InMemoryEventJournal

internal fun testQuestionnaireFactory() = DefaultRuntimeQuestionnaireFactory(InMemoryEventJournal())
