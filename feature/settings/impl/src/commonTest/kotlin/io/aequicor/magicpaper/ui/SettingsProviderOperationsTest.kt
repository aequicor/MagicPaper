package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.data.storage.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.PluginPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsProviderOperationsTest {
    private val profile = LlmProfile("profile", "Provider", baseUrl = "https://fixture.invalid", modelId = "model",
        modelLibraryVersion = 1, favoriteModels = listOf("model"))
    private val models = ModelDefaults.discover(ProviderType.OPENAI_COMPATIBLE, listOf("new-model"))

    @Test fun draftFailuresAreSafeAndCancellationAlwaysClearsLoading() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.service.start()
            fixture.models = { error("private-header-and-provider-body") }
            fixture.service.fetchModels(profile); runCurrent()
            assertFalse(fixture.service.state.value.editorModelsLoading)
            assertNotNull(fixture.service.state.value.editorModelsError)
            assertFalse(fixture.service.state.value.editorModelsError!!.contains("private"))
            fixture.complete = { error("private-response") }
            fixture.service.testConnection(profile); runCurrent()
            assertFalse(fixture.service.state.value.connectionTesting)
            assertFalse(fixture.service.state.value.editorModelsError!!.contains("private"))
            assertNull(fixture.service.state.value.notice)
            fixture.models = { throw CancellationException("cancelled catalog") }
            fixture.service.fetchModels(profile); runCurrent()
            assertFalse(fixture.service.state.value.editorModelsLoading)
            assertNull(fixture.service.state.value.editorModelsError)
            fixture.complete = { throw CancellationException("cancelled connection") }
            fixture.service.testConnection(profile); runCurrent()
            assertFalse(fixture.service.state.value.connectionTesting)
            assertNull(fixture.service.state.value.editorModelsError)
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    /**
     * Отказ провайдера в доступе к модели показывается в настройках действием: человек меняет
     * адрес подключения или модель, а не повторяет запрос, который провайдер отвергнет снова.
     * Ключ GLM Coding Plan на общем адресе Z.AI — именно этот случай: поле называет парный адрес.
     */
    @Test fun providerRefusalIsShownAsAnActionNotAsARetry() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.service.start()
            fixture.complete = {
                throw LlmTransportException(429, null, "HTTP 429: PRIVATE_BALANCE_BODY",
                    ProviderRejection(code = "1113", refusal = ProviderRefusal.ENTITLEMENT))
            }
            val zai = profile.copy(baseUrl = "https://api.z.ai/api/paas/v4")
            fixture.service.testConnection(zai); runCurrent()
            val shown = fixture.service.state.value.editorModelsError.orEmpty()
            assertContains(shown, "https://api.z.ai/api/coding/paas/v4")
            assertFalse("PRIVATE_BALANCE_BODY" in shown, shown)
            assertFalse("Повторите позже" in shown, shown)
            assertFalse(fixture.service.state.value.connectionTesting)
            // У адреса без пары подсказки нет: отказ остаётся действием без выдуманного адреса.
            fixture.service.testConnection(profile); runCurrent()
            val plain = fixture.service.state.value.editorModelsError.orEmpty()
            assertContains(plain, "адрес подключения")
            assertFalse("api.z.ai" in plain, plain)
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun catalogCompletionAfterProfileDeletionCannotRestoreIt() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            fixture.seed(profile)
            fixture.models = { entered.complete(Unit); release.await(); models }
            fixture.service.refreshModelCatalog(profile.id); runCurrent(); entered.await()
            fixture.configuration.deleteProfile(fixture.configuration.state.value.profileRefs.getValue(profile.id))
            release.complete(Unit); runCurrent()
            assertTrue(fixture.configuration.profiles().isEmpty())
            assertTrue(fixture.service.state.value.llmProfiles.isEmpty())
            assertTrue(fixture.service.state.value.catalogRefreshing.isEmpty())
            assertContains(fixture.service.state.value.notice.orEmpty(), "Источник изменился")
        } finally { release.complete(Unit); fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun descriptionReferencesAreCapturedBeforeAsyncWorkAndStaleInputsNeverReachProvider() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        try {
            fixture.seed(profile)
            fixture.service.generateModelDescriptions()
            fixture.configuration.saveProfile(profile.copy(baseUrl = "https://changed.invalid"),
                fixture.configuration.state.value.profileRefs.getValue(profile.id))
            runCurrent()
            assertEquals(0, fixture.researchCalls)
            assertFalse(fixture.service.state.value.descriptionsGenerating)
            assertEquals(1, fixture.service.state.value.descriptionsErrors.size)
            assertTrue(fixture.configuration.dossiers().isEmpty())
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun descriptionUsesCapturedOperationalJudgeVariantAndEffort() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = Fixture(this)
        val variant = ModelVariant("variant:judge", "Judge", "wire-judge", AdvancedLlmOptions())
        try {
            fixture.seed(profile.copy(variants = listOf(variant)), ModelSelection(profile.id, variant.id, EffortSelection.of(ReasoningEffort.HIGH)))
            fixture.research = { target, judge ->
                assertEquals("wire-judge", judge?.modelId)
                assertEquals(variant.id, judge?.selectionKey)
                assertEquals(EffortSelection.of(ReasoningEffort.HIGH), judge?.effort)
                ModelDossier("generated", target.id, target.selectionKey, source = DossierSource.WEB, strengths = "Known")
            }
            fixture.service.generateModelDescriptions(); runCurrent()
            assertTrue(fixture.researchCalls > 0)
            assertFalse(fixture.service.state.value.descriptionsGenerating)
            assertTrue(fixture.service.state.value.descriptionsErrors.isEmpty())
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun subscriptionErrorsRetainKnownAccountAndDoNotLeakProviderText() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val subscription = Subscription()
        val fixture = Fixture(this, subscription)
        try {
            fixture.service.start()
            fixture.service.refreshOpenAiSubscription(); runCurrent()
            val known = fixture.service.state.value.openAiSubscription.account
            subscription.accountRead = { error("private account response") }
            fixture.service.refreshOpenAiSubscription(); runCurrent()
            assertEquals(known, fixture.service.state.value.openAiSubscription.account)
            assertFalse(fixture.service.state.value.openAiSubscription.loading)
            assertFalse(fixture.service.state.value.openAiSubscription.error!!.contains("private"))
            subscription.accountRead = { throw CancellationException("cancelled") }
            fixture.service.refreshOpenAiSubscription(); runCurrent()
            assertFalse(fixture.service.state.value.openAiSubscription.loading)
            assertNull(fixture.service.state.value.openAiSubscription.error)
            subscription.logoutAction = { error("private logout reply") }
            fixture.service.logoutOpenAiSubscription(); runCurrent()
            assertEquals(known, fixture.service.state.value.openAiSubscription.account)
            assertFalse(fixture.service.state.value.openAiSubscription.error!!.contains("private"))
        } finally { fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun lateLoginResultCannotUndoExplicitLogout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val subscription = Subscription(); val release = CompletableDeferred<Unit>()
        val fixture = Fixture(this, subscription)
        subscription.loginWait = { release.await(); OpenAiSubscriptionAccount(true) }
        try {
            fixture.service.start()
            fixture.service.startOpenAiSubscriptionLogin(); runCurrent()
            assertNotNull(fixture.service.state.value.openAiSubscription.login)
            fixture.service.logoutOpenAiSubscription(); runCurrent()
            release.complete(Unit); runCurrent()
            val state = fixture.service.state.value.openAiSubscription
            assertNull(state.account); assertNull(state.login)
            assertFalse(state.loading); assertFalse(state.signingIn)
        } finally { release.complete(Unit); fixture.service.close(); Dispatchers.resetMain() }
    }

    @Test fun claudeSubscriptionSignInReportsItsOutcomeAndCanBeCancelled() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val claude = Claude()
        val fixture = Fixture(this, claude = claude)
        try {
            fixture.service.start()
            assertTrue(fixture.service.state.value.claudeSubscription.available)
            fixture.service.refreshClaudeSubscription(); runCurrent()
            assertEquals(false, fixture.service.state.value.claudeSubscription.signedIn)
            assertFalse(fixture.service.state.value.claudeSubscription.checking)

            fixture.service.signInClaudeSubscription(); runCurrent()
            assertTrue(fixture.service.state.value.claudeSubscription.signingIn, "Signing in waits for the browser")
            claude.outcome.complete(EngineSignInResult.SignedIn); runCurrent()
            fixture.service.state.value.claudeSubscription.let { assertEquals(true, it.signedIn); assertFalse(it.signingIn); assertNull(it.error) }

            claude.outcome = CompletableDeferred()
            fixture.service.signInClaudeSubscription(); runCurrent()
            claude.outcome.complete(EngineSignInResult.Failed("Вход в Claude Code не завершён.")); runCurrent()
            assertEquals("Вход в Claude Code не завершён.", fixture.service.state.value.claudeSubscription.error)

            claude.outcome = CompletableDeferred()
            fixture.service.signInClaudeSubscription(); runCurrent()
            fixture.service.cancelClaudeSubscriptionSignIn(); runCurrent()
            assertFalse(fixture.service.state.value.claudeSubscription.signingIn, "Cancelling ends the pending sign-in")

            claude.statusRead = { error("private CLI output") }
            fixture.service.refreshClaudeSubscription(); runCurrent()
            fixture.service.state.value.claudeSubscription.let {
                assertEquals(true, it.signedIn, "A failed check keeps the last known account")
                assertFalse(it.error!!.contains("private")); assertFalse(it.checking)
            }
        } finally { claude.outcome.cancel(); fixture.service.close(); Dispatchers.resetMain() }
    }

    private class Claude : ClaudeSubscriptionService {
        var statusRead: suspend () -> Boolean? = { false }
        var outcome = CompletableDeferred<EngineSignInResult>()
        override suspend fun signedIn() = statusRead()
        override suspend fun signIn() = outcome.await()
        override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> = error("Unexpected models")
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Unexpected completion")
    }

    private class Subscription : OpenAiSubscriptionService {
        var accountRead: suspend () -> OpenAiSubscriptionAccount = { OpenAiSubscriptionAccount(true) }
        var loginWait: suspend () -> OpenAiSubscriptionAccount = { OpenAiSubscriptionAccount(true) }
        var logoutAction: suspend () -> Unit = {}
        override suspend fun account(refreshToken: Boolean) = accountRead()
        override suspend fun startLogin() = OpenAiSubscriptionLogin("login", "https://login.invalid")
        override suspend fun awaitLogin(loginId: String) = loginWait()
        override suspend fun cancelLogin(loginId: String) = Unit
        override suspend fun logout() = logoutAction()
        override fun close() = Unit
        override suspend fun models(profile: LlmProfile): List<ModelDefaults.DiscoveredModel> = error("Unexpected models")
        override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String = error("Unexpected completion")
    }

    private class Fixture(scope: TestScope, subscription: OpenAiSubscriptionService? = null, claude: ClaudeSubscriptionService? = null) {
        val store = InMemoryKeyValueStore()
        private val json = Json { encodeDefaults = true }
        var models: suspend (LlmProfile) -> List<ModelDefaults.DiscoveredModel> = { error("Unexpected catalog") }
        var complete: suspend (LlmProfile) -> String = { error("Unexpected connection") }
        var research: suspend (LlmProfile, LlmProfile?) -> ModelDossier = { _, _ -> error("Unexpected research") }
        var researchCalls = 0
        private val directory = object : ModelDirectory {
            override suspend fun models(profile: LlmProfile) = models.invoke(profile)
        }
        private val researcher = object : DossierResearcher {
            override suspend fun research(target: LlmProfile, profile: LlmProfile?, settings: AppSettings, onProgress: (String) -> Unit): ModelDossier {
                researchCalls++; return research.invoke(target, profile)
            }
        }
        val configuration = DefaultSettingsConfiguration(store, InMemoryEventJournal(), InMemorySecretStore(), json,
            directory = directory, researcher = researcher, dispatcher = UnconfinedTestDispatcher(scope.testScheduler))
        val service = DefaultSettingsService(configuration, object : ChatRepository {
            override suspend fun sessions() = emptyList<ChatSession>()
            override suspend fun session(id: String): ChatSession? = null
        }, object : ProfileBridge {
            override val supportsFilePicker = false
            override suspend fun export(json: String) = false
            override suspend fun import(): String? = null
        }, store, json, modelDirectory = directory, dossierResearcher = researcher, openAiSubscription = subscription, claudeSubscription = claude,
            gateway = object : LlmGateway {
                override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>) = complete.invoke(profile)
            }, usage = object : UsageLedger {
                override val state = MutableStateFlow(UsageArchive(startedAt = 0))
                override val failure = MutableStateFlow<String?>(null)
                override suspend fun start() = Unit
                override suspend fun captureObservation(): UsageObservation = error("Unexpected accounting")
                override suspend fun record(observation: UsageObservation, record: UsageRecord, replacesId: String?) = error("Unexpected accounting")
                override suspend fun context(observation: UsageObservation, snapshot: ContextUsageSnapshot) = error("Unexpected accounting")
                override suspend fun cumulative(observation: UsageObservation, key: String, fingerprint: String, total: TokenUsage, last: TokenUsage, record: UsageRecord) = error("Unexpected accounting")
                override suspend fun exportArchive() = state.value
                override suspend fun replace(archive: UsageArchive) = error("Unexpected import")
                override suspend fun clear() = error("Unexpected reset")
                override suspend fun <T> measure(profile: LlmProfile, block: suspend () -> T): T = error("Unexpected model")
            }, chatHistory = object : ChatHistoryCommands {
                override suspend fun importNotebooks(sessions: List<ChatSession>) = error("Unexpected import")
                override suspend fun unlinkProfile(profileId: String) = error("Unexpected unlink")
                override suspend fun wipeHistory() = error("Unexpected reset")
            }, pluginPreferences = object : PluginPreferences {
                override suspend fun exportPreferences() = emptyList<PluginState>()
                override suspend fun importPreferences(preferences: List<PluginState>) = error("Unexpected import")
                override suspend fun clearPreferences() = error("Unexpected reset")
            })
        suspend fun seed(profile: LlmProfile, selection: ModelSelection = ModelSelection(profile.id, profile.modelId)) {
            configuration.start()
            // The legacy bootstrap contains standard providers; this fixture owns exactly one source.
            configuration.state.value.profileRefs.values.toList().forEach { configuration.deleteProfile(it) }
            configuration.saveProfile(profile, null)
            configuration.changeSettings(configuration.settings().copy(defaultModel = selection, activeLlmProfileId = profile.id)).getOrThrow()
            service.start()
        }
    }
}
