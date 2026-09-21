package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.data.storage.InMemoryEventJournal
import io.aequicor.magicpaper.data.storage.InMemoryKeyValueStore
import io.aequicor.magicpaper.domain.tools.*

/** Explicit text-only test provider; production uses the gateway's real tool protocol. */
fun testGatewayRuntime(
    gateway: LlmGateway,
    searchEngine: SearchEngine,
    docs: DocRepository,
    skillLibrary: SkillLibrary = EmptySkillLibrary,
    skillSelector: SkillSelector = SkillSelector(),
    packageRuntime: SkillInstructionRuntime? = null,
    settings: suspend () -> AppSettings = { AppSettings() },
    readResearchPage: (suspend (String) -> String)? = null,
): GatewaySessionRuntime {
    val journal = InMemoryEventJournal()
    val receipts = MemoryToolReceiptStore()
    val textProvider = object : LlmGateway {
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = gateway.complete(profile, messages)
        override suspend fun turn(profile: LlmProfile, messages: List<LlmMessage>, tools: List<LlmToolDefinition>,
            exchanges: List<LlmToolExchange>): LlmToolTurn {
            check(exchanges.isEmpty()) { "This fixture only represents a text-only provider" }
            return LlmToolTurn(text = complete(profile, messages), provider = profile.provider)
        }
    }
    val tools = DefaultChatToolSessions(searchEngine, docs::articles, { docs.search(it) }, { emptyList() },
        null, DefaultRuntimeQuestionnaireService(journal, "fixture"), receipts, DefaultMediaToolReceiptOwner(receipts) { null })
    return GatewaySessionRuntime(DefaultProviderToolLoop(textProvider, journal, StoredProviderToolOutputs(InMemoryKeyValueStore())), tools, searchEngine, docs,
        skillLibrary, skillSelector, packageRuntime, settings, readResearchPage)
}
