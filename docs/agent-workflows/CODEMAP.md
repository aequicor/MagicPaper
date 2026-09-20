# Карта входа для разработки

Выберите строку по задаче. Начните с указанного контракта/реализации и теста.
Поддереьевой AGENTS.md читайто только для уникальных ограничений модуля;
общие правила — в корневом AGENTS.md. Полный граф — [MODULES.md](../MODULES.md);
команды — [VERIFICATION.md](VERIFICATION.md). Не загружайте соседние области,
пока изменение или найденный вызов их не затрагивает.

**Минимизация вызовов:** для простой задачи начните сразу с кода по колонке
«Начать с»; не читайте MODULES.md, VERIFICATION.md и skills, если задача
однозначна. Читайте VERIFICATION.md только для подбора команд проверки.

В таблице `commonMain`, `jvmMain` и тестовые source set содержат пакет
`kotlin/io/aequicor/magicpaper/`. Сохранённое имя пакета не определяет Gradle-владельца:
после миграции одинаковые пакеты могут находиться в разных модулях.
Корень приложения — `:app` в `app/`; прежнего Gradle project `:shared` и alias нет.

| Задача | Владелец / каталог | Начать с | Ближайшая проверка |
| --- | --- | --- | --- |
| Маршрут, Back/Forward, ссылка, диалог | [app/navigation](../../app/src/commonMain/kotlin/io/aequicor/magicpaper/navigation) | `AppRoute.kt`, `NavigationJournal.kt`, `RootComponent.kt`; browser: `BrowserHistoryBridge.kt` | `:app:jvmTest --tests '*RootComponentTest' --tests '*RootDialogLifecycleTest'` |
| Прокрутка и сохранение состояния экрана | [app/navigation](../../app/src/commonMain/kotlin/io/aequicor/magicpaper/navigation), [session](../../feature/session) | `VisitPresentation.kt`; `impl/.../ui/components/MessageHistoryActions.kt`; lazy Markdown в `designSystem` | `:app:jvmTest --tests '*VisitPresentation*Test'`; session `jvmTest --tests '*MessageHistoryActionsRenderTest'`. Проверять с visit registry, а не только изолированной лентой |
| Koin, startup, reset, shutdown | [app/di](../../app/src/commonMain/kotlin/io/aequicor/magicpaper/di) | `AppRuntime.kt`, `Dependencies.kt`, затем платформенный `Dependencies.*.kt` | `:app:jvmTest --tests '*RuntimeLifecycleTest'` |
| Оболочка, sidebar, выбор фичи | [app](../../app/src/commonMain/kotlin/io/aequicor/magicpaper) | `App.kt`, `navigation/AppRoot.kt`, `ui/screens/UnifiedSidebar.kt` | `:app:jvmTest --tests '*AppShellRenderTest' --tests '*ShellSettingsComponentTest'` |
| Чат, отправка, composer | [session](../../feature/session) | `api/.../ui/ChatComponent.kt`, `ChatService.kt`; `impl/.../ui/DefaultChatService.kt`, `DefaultChatComponent.kt` | `:feature:session:impl:jvmTest`; host integration: `:app:jvmTest --tests '*ChatServiceLifecycleTest'` |
| Вопросы и источники исследования | [session](../../feature/session), [контракт](../desktop-ui/RESEARCH-CHAT.md) | `api/.../ui/ChatState.kt`, `ChatService.kt`; `impl/.../ui/screens/ResearchWorkspace.kt`, `ResearchSourcesPane.kt`, `domain/ChatResearch.kt`; модели `ChatSession`, `ResearchResource` | `:app:jvmTest --tests '*ChatResearchTest'`; owner `jvmTest --tests '*ResearchWorkspaceRenderTest' --tests '*ResearchSourcesPaneRenderTest' --tests '*ChatResearchContextTest' --tests '*ResearchPageReaderTest'` |
| Кодинг, сессия, checkpoint | [coding](../../feature/coding/impl) | контракт: `session/api/.../ui/CodingService.kt`, `domain/CodingFeature.kt`; сборка: `di/CodingFeatureFactory.kt`; реализация: `ui/DefaultCodingService.kt`, `DefaultCodingComponent.kt` | `:feature:coding:impl:jvmTest`; resume integration: `:app:jvmTest --tests '*CodingResumeTest'` |
| Реактивная проекция планирования | [coding](../../feature/coding/impl) | `ui/PlanningSessionProjection.kt`, `StageChatState.kt`; снимок: `session/api/.../ui/CodingState.kt` (`CodingUi.planning`); публикация: `DefaultCodingService.observeRuntime` | `:feature:coding:impl:jvmTest --tests '*PlanningSessionProjectionTest' --tests '*PlanningSessionStatusTest' --tests '*StageChatStateTest'`; renders: `*PlanningProposalRenderTest`, `*OrchestrationFailureRenderTest` |
| Панель планирования: фоновые запросы и lifecycle | [coding](../../feature/coding/impl) | `plugins/builtin/PlanningPanelController.kt`, `DecisionPlanningPlugin.kt`; application scope, pure `PlanningPanelState.planFor` | `:feature:coding:impl:jvmTest --tests '*PlanningPanelControllerTest' --tests '*PlanningWizardRenderTest'`; граница экрана: `:app:jvmTest --tests '*CodingComponentNavigationTest' --tests '*QuestionnaireViewModelTest'` |
| Worktree задачи, пул, автослияние | [session worktrees](../../feature/session/WORKTREES.md) | контракт: `session/api/.../domain/TaskWorkspace.kt`; `coding/impl/.../domain/TaskWorktreeService.kt`, JVM `data/planning/GitTaskWorkspace.kt` | `:feature:coding:impl:jvmTest --tests '*GitTaskWorkspaceTest'`; `:app:jvmTest --tests '*CodingWorktreeTest'` |
| Планирование, дерево, вмешательство | [coding/domain](../../feature/coding/impl/src/commonMain/kotlin/io/aequicor/magicpaper/domain) | `OrchestrationService.kt`, `SessionTreeRuntime.kt`; далее конкретный store/workspace по вызову | Owner `jvmTest --tests '*PlanningChatServiceTest'` либо соответствующий `*Session*Test` |
| Предложение/подтверждение плана, восстановление назначений, legacy-связи | [model](../../core/model), [coding](../../feature/coding/impl) | `core/model/.../domain/planning/PlanOrchestrationMachine.kt`; адаптеры `domain/OrchestrationService.kt`, `PlanningExecutionService.kt` | Model `jvmTest --tests '*PlanOrchestrationMachineTest'`; coding `jvmTest --tests '*PlanningChatServiceTest' --tests '*PlanningExecutionServiceTest' --tests '*PlanningRetryCheckpointTest' --tests '*OrchestrationToolsTest'` |
| Очередь оркестратора, вопросы, паузы, квитанции координации | [model](../../core/model), [coding](../../feature/coding/impl) | `core/model/.../domain/planning/OrchestrationMachine.kt`, `CoordinationMachine.kt`; адаптер `domain/OrchestrationService.kt` | Model `jvmTest --tests '*OrchestrationMachineTest' --tests '*CoordinationMachineTest'`; coding `jvmTest --tests '*OrchestrationInputAcceptanceTest' --tests '*PlanningChatServiceTest' --tests '*OrchestrationToolsTest'` |
| Переходы этапа, проверка, повтор, объединение, отмена | [model](../../core/model), [coding](../../feature/coding/impl) | `core/model/.../domain/planning/StageMachine.kt`, `StagePrompt.kt`; адаптер `domain/PlanningExecutionService.kt` | Model `jvmTest --tests '*StageMachineTest'`; coding `jvmTest --tests '*PlanningExecutionServiceTest' --tests '*PlanningCheckpointReloadTest'` |
| Классификация повторных сбоев и разрешённые стратегии | [model](../../core/model), [coding](../../feature/coding/impl) | `domain/planning/PlanStrategy.kt`; `domain/PlanStrategyClassifier.kt`, `data/planning/PlanStrategyJournal.kt`; подключение `CodingRuntimeGraph`, `PlanningExecutionService` | Model `*PlanStrategyTest`; coding `*PlanStrategyClassifierTest`, `*PlanJournalReaderTest`, `*PlanningExecutionServiceTest` |
| Plan как проекция журнала, редакции, checkpoint и удаление | [model](../../core/model), [coding](../../feature/coding/impl), [storage](../../core/storage) | `domain/planning/PlanProjection.kt`; `data/planning/PlanJournalProjection.kt`, `PlanningStore.kt`; storage `EventJournal`, `DurableEventJournal` | Model `*PlanProjectionTest`; coding `*PlanningJournalProjectionTest`, `*PlanningStoreCacheTest`, `*PlanningEventJournalTest`; storage `*JournalRevisionTest`, `*DurableEventJournalTest`; JS/Wasm `IndexedDbPersistenceTest` |
| Журнал намерений, неизвестный исход, восстановление | [coding](../../feature/coding/impl) | `data/planning/PlanningStore.kt`, `PlanJournalReader.kt`; `domain/PlanningJournalRecovery.kt`, `PlanningExecutionService.kt`, `PlanRetryNativeRecovery.kt`; модель `StageResumption` | Owner `jvmTest --tests '*PlanJournalReaderTest' --tests '*PlanningJournalRecoveryTest' --tests '*PlanningExecutionServiceTest' --tests '*NativeReceiptRecoveryTest' --tests '*SessionQuarantineRecoveryTest'` |
| Общая презентация сессий | [transcript](../../feature/transcript) | `ui/screens/SessionTranscript.kt` (`MessagesList`, `Composer`), `ui/components/` (вложения, закрепления, действия над историей), `domain/SessionTitles.kt`, `UnreadTracker.kt`, `ConversationHistory.kt` | `:feature:session:impl:jvmTest` и `:feature:coding:impl:jvmTest` — модуль пока покрыт тестами потребителей |
| Инструменты: словарь, схемы, доступ | [tools](../../feature/tools) | `ToolCatalog`, `SessionToolCatalog`, `ToolDefinitions` (матрица доступа), `QuestionnaireContract`, `ToolRejections`, `CustomOrchestration`, `DefaultCustomOrchestration` | `:feature:tools:impl:jvmTest` |
| Исполнение инструментов и эффекты | [coding](../../feature/coding/impl) | `domain/tools/` (`AgentTools`, `ToolHost`, `ToolReceiptIntegrity`, `ToolEnabledCodingRuntime`); эффекты: `domain/OrchestrationService.kt`; native-мост: JVM `data/tools/AgentToolBridge.kt` | `:feature:session:impl:jvmTest --tests '*SessionAuthorityToolTest'` |
| Браузер агента, Google, HTML-проверки | [coding browser](../../feature/coding/impl/src/jvmMain/kotlin/io/aequicor/magicpaper/data/browser) | `BrowserToolCatalog`, `BrowserToolSession`, `HtmlValidation`; мост `data/tools/AgentToolBridge`; [владение и проверки](../AGENT-BROWSER.md) | `:feature:coding:impl:jvmTest --tests '*BrowserToolsTest' --tests '*HtmlValidationTest' --tests '*AgentToolBridgeTest'`; настоящий Chromium: `-Pmagicpaper.browser.native=true --tests '*BrowserNativeTest'` |
| Ручное чтение недоступного источника | [session](../../feature/session) | API `ResearchPageBrowser`; `ui/ResearchBrowserRecovery`, `ui/screens/ResearchBrowserDialog`; JVM `data/browser/DesktopResearchPageBrowser` | `:feature:session:impl:jvmTest --tests '*ResearchBrowserRecoveryTest'`; Chromium: `-Pmagicpaper.browser.native=true --tests '*ResearchPageBrowserNativeTest'`; `:app:jvmTest --tests '*ChatResearchTest'` |
| Pi/Codex, subprocess, wire protocol | [coding/jvmMain](../../feature/coding/impl/src/jvmMain) | `data/coding`, `data/planning`; ресурсы `resources/coding`; [ENGINES.md](../ENGINES.md) | `:feature:coding:impl:nodeProtocolTest` и тест изменённого адаптера |
| Computer/application-use, доступ и native UI | [coding automation](../../feature/coding/impl/src/jvmMain/kotlin/io/aequicor/magicpaper/data/computer) | API `domain/ComputerUse.kt`; `DesktopComputerUse`, `DesktopCaptureRequest`, `ApplicationUse`, `NativeApplicationDesktop`; [протокол и проверки](../COMPUTER-USE.md) | `:feature:coding:impl:jvmTest --tests '*ApplicationUseTest' --tests '*NativeApplicationDesktopTest' --tests '*ComputerUse*Test' --tests '*DesktopScreenshotDetailTest' --tests '*DesktopImageCoordinatesTest'`; policy/restore: `:app:jvmTest --tests '*AutomationPolicyTest' --tests '*SessionInputQueueTest' --tests '*ChatInputQueueTest'` |
| Управление компьютером: меню и разрешения ОС | [settings](../../feature/settings) / [session](../../feature/session) | `SettingsComponent`, `ComputerSettings`, `ComputerPermissionController`; API `ComputerPermissions`, JVM `DesktopComputerPermissions`; DS `PaperFileTransfer` | Settings `*ComputerSettings*Test`, `*ComputerPermissionControllerTest`; session `*DesktopComputerPermissionsTest`; DS `*PaperFileTransferTest`; app `*AppRouteCodecTest`, `*RootComponentTest` |
| Настройки, формы, импорт/экспорт | [settings](../../feature/settings) | `api/.../ui/SettingsService.kt`; `impl/.../ui/DefaultSettingsService.kt`, `SettingsDrafts.kt` | `:feature:settings:impl:jvmTest` |
| Секреты, profile migration | [settings/storage](../../feature/settings/impl/src/commonMain/kotlin/io/aequicor/magicpaper/data/storage) | `JsonSettingsRepository.kt`, `JsonLlmProfileRepository.kt`; затем storage API; пределы моделей: `core/model` `ModelLimitCatalog.kt` | Тесты settings и storage, включая reopen/failure |
| Черновики, вложения, постоянная запись | [storage](../../core/storage) и [platform](../../core/platform) | `api/.../data/storage/Persistence.kt`, `DraftSession.kt`; `impl/.../data/storage/DurablePersistence.kt`; platform composer helper | `:core:storage:impl:jvmTest`, `:core:platform:jvmTest`; IndexedDB отдельно в browser |
| LLM, поиск, usage | [ai](../../core/ai) | API порта; реализация клиента/ledger в `impl`; каталог моделей: `ModelDirectories.kt`, `DeclaredLimitsModelDirectory.kt` | `:core:ai:impl:jvmTest` |
| Изображения/видео в сессии, media provider, placeholder, playback | [media guide](../MEDIA-GENERATION.md): session, ai, storage, settings, Paper | `MediaGenerationService`, `DefaultMediaGenerationService`, `HttpMediaGenerationGateway`; модель `GeneratedMedia` / `TranscriptContent`, `GeneratedMediaView`, `PaperGeneratedMedia` | Session `*MediaGenerationServiceTest`, `*GeneratedMediaTranscriptRenderTest`, `*SessionMediaToolOptionsRenderTest`; DS `*PaperGeneratedMediaTest`, `*PaperMediaImageDecodingTest`; native opt-in `-Pmagicpaper.media.native=true :designSystem:jvmTest --tests '*PaperVideoNativeTest'` |
| Документация приложения | [docs feature](../../feature/docs) | `DocsComponent.kt`, `DefaultDocsComponent.kt`, repository по API | `:feature:docs:impl:jvmTest` |
| Плагины и их включение | [plugins](../../feature/plugins) | `api/.../plugins/PluginRegistry.kt`; `impl/.../ui/DefaultPluginService.kt`, `DefaultPluginsComponent.kt` | `:feature:plugins:impl:jvmTest` |
| Навыки внутри приложения | [skills feature](../../feature/skills) | `SkillInstructionRuntime.kt`, `ProjectSkills.kt`; `DefaultSkillsComponent.kt`, нужный panel/adapter | `:feature:skills:impl:jvmTest` |
| Визуальная верстка и каталог Paper | [paper-plugin](../../tools/paper-plugin), [paper-editor](../../tools/paper-editor) | `PaperDesignPlugin`, `PaperFixtures`; [инструкция](../../tools/paper-editor/README.md) | `./gradlew -PpaperEditor=true :tools:paper-plugin:test :tools:paper-editor:compileKotlin` |
| Макет по запросу обычного чата | [session](../../feature/session), [paper-editor](../../tools/paper-editor) | `LayoutEditor`, `LayoutChatAgent`, `DesktopLayoutEditor`, `DefaultChatService`; launcher `Main`, `PaperAgentCommands` | `:feature:session:impl:jvmTest`; `:app:jvmTest --tests '*ChatLayoutWorkflowTest'`; [native-проверка](VERIFICATION.md) |
| Полосы прокрутки: списки, формы, Markdown, редакторы | [designSystem](../../designSystem), [контракт](../desktop-ui/SCROLLBARS.md) | `PaperScroll.kt`, `skikoMain/.../PaperScrollbar.skiko.kt`, `jvmMain/.../PaperBasicTextField.jvm.kt` | `:designSystem:jvmTest --tests "*PaperScrollbarTest" --tests "*PaperMarkdown*Test"`; consumer `ResearchScrollRegressionTest`, `ResearchWorkspaceRenderTest` |
| Потоковый ответ в чате и журнале прогона: плавность, слежение за концом | [designSystem](../../designSystem), [контракт](../desktop-ui/STREAMING-CHAT.md) | `ChatScroll.kt`, `StreamingText.kt`, `MarkdownDocument.kt`, `ChatMarkdown.kt`, `InlineMessage.kt`; consumer `CodingScreen.kt`, `ChatScreen.kt` | `:designSystem:jvmTest --tests "*StreamingText*"`; consumer `CodingChatScrollTest`, `ChatScrollToBottomTest`, `LargeCodingChatRenderTest` |
| Контрол, тема, Markdown, окно | [designSystem](../../designSystem) | Paper API + конкретный consumer; [brandbook](../desktop-ui/BRANDBOOK.md) | Paper verifier + `:designSystem:jvmTest --tests '*ИМЯ_ТЕСТА*'` + consumer render |
| Общая модель / сериализация | [model](../../core/model) | Тип и его serializer; callers только для изменённого контракта | `:core:model:jvmTest`, тесты владельцев чтения/записи |
| Gradle, зависимости | [build-logic](../../build-logic) | conventions; `settings.gradle.kts`, `gradle/libs.versions.toml`, build-файл владельца | Architecture verifier, затем нужные платформы |
| Native links / упаковка | [desktopApp](../../desktopApp), [androidApp](../../androidApp), [webApp](../../webApp) | Desktop `main.kt`/`DesktopActivationBroker.kt`/`packaging`; Android `MainActivity.kt`/manifest; Web `main.kt`/webpack | Платформенные строки в verification map |

`...` в строках фич означает `src/commonMain/kotlin/io/aequicor/magicpaper`.
Перед Gradle-аргументами из таблицы добавляйте `./gradlew` из корня репозитория.
Заменяйте явные placeholders реальным классом теста.

## Поиск без повторного обхода проекта

Пример для чата:

```sh
rg --files feature/session -g '*.kt' -g '!**/build/**'
rg -n 'sendMessage|composerDraft' feature/session/impl/src feature/session/api/src
```

Если меняется API, ищите его имя в конкретных соседних API/impl и root wiring.
Проверяйте контекст найденного вызова; совпадение строки само по себе не доказывает
владение. Не ищите содержимое секретных хранилищ для диагностики обычного кода.

## Поддержка этой карты

При переносе публичной точки входа обновите её строку и соответствующий AGENTS.md.
Не храните здесь текущие статусы тестов, полные логи или копии исходников.
Проектные development-навыки в `skills/` не являются каталогом навыков пользователя
внутри MagicPaper и не должны автоматически устанавливаться в него.
