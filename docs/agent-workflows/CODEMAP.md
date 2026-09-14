# Карта входа для разработки

Выберите строку по задаче. Прочитайте AGENTS.md по пути к владельцу, затем
указанный контракт/реализацию и нужный тест. Полный граф ответственности —
[MODULES.md](../MODULES.md); команды — [VERIFICATION.md](VERIFICATION.md).
Не загружайте соседние области, пока изменение или найденный вызов их не затрагивает.

В таблице `commonMain`, `jvmMain` и тестовые source set содержат пакет
`kotlin/io/aequicor/magicpaper/`. Сохранённое имя пакета не определяет Gradle-владельца:
после миграции одинаковые пакеты могут находиться в разных модулях.
Корень приложения — `:app` в `app/`; прежнего Gradle project `:shared` и alias нет.

| Задача | Владелец / каталог | Начать с | Ближайшая проверка |
| --- | --- | --- | --- |
| Маршрут, Back/Forward, ссылка, диалог | [app/navigation](../../app/src/commonMain/kotlin/io/aequicor/magicpaper/navigation) | `AppRoute.kt`, `NavigationJournal.kt`, `RootComponent.kt`; browser: `BrowserHistoryBridge.kt` | `:app:jvmTest --tests '*RootComponentTest' --tests '*RootDialogLifecycleTest'` |
| Koin, startup, reset, shutdown | [app/di](../../app/src/commonMain/kotlin/io/aequicor/magicpaper/di) | `AppRuntime.kt`, `Dependencies.kt`, затем платформенный `Dependencies.*.kt` | `:app:jvmTest --tests '*RuntimeLifecycleTest'` |
| Оболочка, sidebar, выбор фичи | [app](../../app/src/commonMain/kotlin/io/aequicor/magicpaper) | `App.kt`, `navigation/AppRoot.kt`, `ui/screens/UnifiedSidebar.kt` | `:app:jvmTest --tests '*AppShellRenderTest' --tests '*ShellSettingsComponentTest'` |
| Чат, отправка, composer | [session](../../feature/session) | `api/.../ui/ChatComponent.kt`, `ChatService.kt`; `impl/.../ui/DefaultChatService.kt`, `DefaultChatComponent.kt` | `:feature:session:impl:jvmTest`; host integration: `:app:jvmTest --tests '*ChatServiceLifecycleTest'` |
| Кодинг, сессия, checkpoint | [session](../../feature/session) | `api/.../ui/CodingService.kt`; `impl/.../ui/DefaultCodingService.kt`, `DefaultCodingComponent.kt` | `:feature:session:impl:jvmTest`; resume integration: `:app:jvmTest --tests '*CodingResumeTest'` |
| Worktree задачи, пул, автослияние | [session worktrees](../../feature/session/WORKTREES.md) | `api/.../domain/TaskWorkspace.kt`; `impl/.../domain/TaskWorktreeService.kt`, JVM `data/planning/GitTaskWorkspace.kt` | `:feature:session:impl:jvmTest --tests '*GitTaskWorkspaceTest'`; `:app:jvmTest --tests '*CodingWorktreeTest'` |
| Планирование, дерево, вмешательство | [coding/domain](../../feature/session/impl/src/commonMain/kotlin/io/aequicor/magicpaper/domain) | `OrchestrationService.kt`, `SessionTreeRuntime.kt`; далее конкретный store/workspace по вызову | Owner `jvmTest --tests '*PlanningChatServiceTest'` либо соответствующий `*Session*Test` |
| Оркестрация, каталог инструментов | [custom-tools](../../feature/custom-tools) | `SessionToolCatalog`, `CustomOrchestration`, `DefaultCustomOrchestration`; эффекты: `session/impl` → `OrchestrationService` | `:feature:custom-tools:impl:jvmTest`; `:feature:session:impl:jvmTest --tests '*SessionAuthorityToolTest'` |
| Pi/Codex, subprocess, wire protocol | [session/jvmMain](../../feature/session/impl/src/jvmMain) | `data/coding`, `data/planning`; ресурсы `resources/coding`; [ENGINES.md](../ENGINES.md) | `:feature:session:impl:nodeProtocolTest` и тест изменённого адаптера |
| Настройки, формы, импорт/экспорт | [settings](../../feature/settings) | `api/.../ui/SettingsService.kt`; `impl/.../ui/DefaultSettingsService.kt`, `SettingsDrafts.kt` | `:feature:settings:impl:jvmTest` |
| Секреты, profile migration | [settings/storage](../../feature/settings/impl/src/commonMain/kotlin/io/aequicor/magicpaper/data/storage) | `JsonSettingsRepository.kt`, `JsonLlmProfileRepository.kt`; затем storage API | Тесты settings и storage, включая reopen/failure |
| Черновики, вложения, постоянная запись | [storage](../../core/storage) и [platform](../../core/platform) | `api/.../data/storage/Persistence.kt`, `DraftSession.kt`; `impl/.../data/storage/DurablePersistence.kt`; platform composer helper | `:core:storage:impl:jvmTest`, `:core:platform:jvmTest`; IndexedDB отдельно в browser |
| LLM, поиск, usage | [ai](../../core/ai) | API порта; реализация клиента/ledger в `impl` | `:core:ai:impl:jvmTest` |
| Документация приложения | [docs feature](../../feature/docs) | `DocsComponent.kt`, `DefaultDocsComponent.kt`, repository по API | `:feature:docs:impl:jvmTest` |
| Плагины и их включение | [plugins](../../feature/plugins) | `api/.../plugins/PluginRegistry.kt`; `impl/.../ui/DefaultPluginService.kt`, `DefaultPluginsComponent.kt` | `:feature:plugins:impl:jvmTest` |
| Навыки внутри приложения | [skills feature](../../feature/skills) | `SkillInstructionRuntime.kt`, `ProjectSkills.kt`; `DefaultSkillsComponent.kt`, нужный panel/adapter | `:feature:skills:impl:jvmTest` |
| Визуальная верстка и каталог Paper | [paper-plugin](../../tools/paper-plugin), [paper-editor](../../tools/paper-editor) | `PaperDesignPlugin`, `PaperFixtures`; [инструкция](../../tools/paper-editor/README.md) | `./gradlew -PpaperEditor=true :tools:paper-plugin:test :tools:paper-editor:compileKotlin` |
| Макет по запросу обычного чата | [session](../../feature/session), [paper-editor](../../tools/paper-editor) | `LayoutEditor`, `LayoutChatAgent`, `DesktopLayoutEditor`, `DefaultChatService`; launcher `Main`, `PaperAgentCommands` | `:feature:session:impl:jvmTest`; `:app:jvmTest --tests '*ChatLayoutWorkflowTest'`; [native-проверка](VERIFICATION.md) |
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
