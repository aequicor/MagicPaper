# Незакоммиченный снимок SKILLS

Снимок снят 2026-09-08 перед планированием следующей работы. Это инвентаризация
текущей рабочей копии, а не утверждение, что все исторические отчёты можно
повторить без нового прогона. Команды и точные результаты внизу документа.

## Git-состояние и пересечения

| Область | Количество уникальных путей | Состояние |
| --- | ---: | --- |
| index (staged) | 3 | три уже проиндексированные правки |
| working tree (unstaged) | 35 | включает три пути, которые также есть в index |
| untracked | 37 | новые исходники, тесты, ресурс и отчёты до добавления этого отчёта |
| всего разных путей | 72 | `3 + 35 + 37 - 3` до добавления этого отчёта |

Единственные staged/unstaged пересечения (статус `MM`) — это продолжения уже
проиндексированных правок, их нельзя частично отменять или добавлять как
независимые изменения:

1. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/CodingScreen.kt` — экран coding/диалог SKILLS и соседняя стабилизация Compose-списка.
2. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/coding/DesktopCodingRuntime.kt` — подготовка SKILLS-входа и добавленный planning runtime.
3. `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/ProjectSkillsTest.kt` — проверки project-SKILLS и их последующие дополнения.

Все остальные модифицированные файлы — только unstaged; новых staged файлов нет.
Ни один существующий файл не удалён (`D`/rename отсутствуют).

## Матрица цель → код → тесты → статус

Статус относится к фактическому дереву и адресному чтению кода. `PASS (история)`
означает, что такой успешный прогон задокументирован в незакоммиченном отчёте,
но не был повторён для этой инвентаризации.

| Требование | Владелец области / код | Проверка | Статус и исключение из будущего объёма |
| --- | --- | --- | --- |
| Единое локальное хранилище пакетов, версии, карантин, review, project pins и восстановление | **Репозиторий SKILLS**: `SkillReleaseStore.kt`, `LocalSkillRepository.kt`, `Dependencies.kt`, `Dependencies.jvm.kt`, `ProjectSkills.kt` | `ProjectSkillsTest`; ранее `LocalSkillRepositoryTest` | Реализовано; не переписывать репозиторий, формат, pin/review или DI. Нужен только потребитель новых источников. |
| Каталог и пользовательская ссылка на репозиторий | **Каталог источников**: `GithubSkillCatalog.kt`, `SkillCatalogPanel.kt`, `SkillPublicDownload.kt`, `ProjectSkillsPanel.kt`, `docs/SKILLS-CATALOG.md` | `GithubSkillCatalogTest`, `GithubSkillCatalogLiveTest`, `SkillCatalogPanelTest`, `SkillCatalogLiveUiTest`, `SkillCatalogUpdateEvidenceTest`, `SkillDownloadPrivacyTest` | Реализовано для публичных HTTPS GitHub repository/tree/blob ссылок: явное network-consent, SHA, preview, quarantine, review, offline reopen. Не дублировать GitHub downloader/catalog UI. |
| Версии, обновление и rollback состава проекта | **Project-SKILLS UI**: `ProjectSkillRollback.kt`, `ProjectSkillsPanel.kt`, `SkillReleaseStore.kt`, `LocalSkillRepository.kt` | `ProjectSkillRollbackUiTest`, `ProjectSkillsTest.projectRollback*` | Реализовано. Не заменять `rollbackProject` на `bindProject`; сохранять generation/checksum и повторное согласие. |
| Доверенная доставка точного подключённого текста в coding | **SKILLS → adapters**: `CodingSkillInput.kt`, `DesktopCodingRuntime.kt`, `PiCodingRuntime.kt`, `CodexAppServerOpenAiSubscription.kt`, `ProjectSkills.kt`, `LocalSkillRepository.kt` | `ProjectSkillsTest.trustedTextConsentPersistsAndDisconnectNeverResumesOldContext`, `adapterStartAndResumeRefuseBeforeTransportWithExactReceipt` | Реализовано до границы адаптера: opt-in на точный состав, fresh session, локальная квитанция. Реальная Pi/Codex wire-приёмка всё ещё NOT_RUN; это объём этапа доставки, а не повторное создание consent/pin. |
| Импорт собственного навыка готовым текстом в **новый** пакетный контур | Будущий владелец: **text import**; целевые границы: `LocalSkillRepository`, `ProjectSkillsPanel`/отдельный editor и валидатор формата | Отсутствуют новые тесты текста → package → quarantine/review/pin | Не реализовано. Старый `SkillDraft`/`SkillInstaller.installDraft` сохраняет legacy `Skill`, не `SkillPackage`, поэтому не закрывает этот критерий и не должен ошибочно считаться импортом нового репозитория. |
| Самообучение по повторяющимся паттернам и создание кандидата | Имеется предыдущий владелец **experience**: `LocalSkillExperience.kt`, `StrictExperienceCatalog.kt`, `LocalExperiencePlugin.kt` | `LocalSkillExperienceTest.candidateAutomaticallyEvaluatesButNeedsReviewAndActivationAfterRestart` | Частично реализовано: явный пользовательский запуск `generate(token, true)` создаёт карантинный кандидат и проверяет его. Автоматический запуск от результатов обычной работы агента и связь coding outcomes → experience отсутствуют; не удалять существующую строгую оценку/карантин. |
| Legacy ручной/диалоговый черновик | Изолированный legacy-владелец: `Skills.kt`, `SkillEducator.kt`, `SelfEducationPlugin.kt`, `SkillInstaller.kt` | `SkillInstallerTest`, `SkillEducatorTest` | Существует отдельно от package repository. Не смешивать его storage/API с package-импортом без явной миграции; он требует кнопки и подтверждения, не является автоматическим обучением. |
| Planning read-only и связанные coding/UI правки | **Planning и соседний UX**, не владелец SKILLS: `PlanningGateway.kt`, `BackgroundCodingProjectRepository.kt`, `CodingDraftUpdates.kt`, `planning-tools.mjs`, `DecisionPlanner.kt`, `OrchestrationService.kt`, `PlanComposer.kt`, `MagicPaperViewModel.kt`, `RequestPins.kt`, `ChatScreen.kt`, `CodingScreen.kt` | `PlanningGatewayTest`, `PlanningRuntimeIntegrationTest`, `CodingDraftUpdatesTest`, render-тесты | Не включать в этапы 2–5 системы SKILLS. Эти изменения принадлежат планированию/coding UX; `CodingScreen.kt` и `DesktopCodingRuntime.kt` имеют только перечисленное выше осмысленное пересечение. |

## Полный инвентарь по функциональному владельцу

### Изменены, unstaged (35; включая три `MM`)

- Документация planning: `docs/ENGINES.md`, `docs/PLANNING.md`; контракт SKILLS: `docs/SKILLS-PROJECT-CODING.md`.
- Planning/coding domain: `shared/src/commonMain/kotlin/io/aequicor/magicpaper/di/Dependencies.kt`, `domain/Coding.kt`, `domain/DecisionPlanner.kt`, `domain/OrchestrationService.kt`, `domain/PlanComposer.kt`, `plugins/builtin/DecisionPlanningPlugin.kt`, `ui/MagicPaperViewModel.kt`.
- Project-SKILLS: `domain/ProjectSkills.kt`, `domain/SkillReleaseStore.kt`, `jvmMain/data/skills/LocalSkillRepository.kt`, `jvmMain/data/skills/SkillPublicDownload.kt`, `jvmMain/di/Dependencies.jvm.kt`, `jvmMain/plugins/builtin/ProjectSkillsPanel.kt`.
- Coding adapters/UX: `jvmMain/data/coding/CodexProviderBridge.kt`, `DesktopCodingRuntime.kt`, `PiCodingRuntime.kt`, `jvmMain/data/llm/CodexAppServerOpenAiSubscription.kt`, `ui/components/RequestPins.kt`, `ui/screens/ChatScreen.kt`, `ui/screens/CodingScreen.kt`.
- Существующие тесты/фикстуры: `commonTest/domain/DecisionPlannerTest.kt`, `PlanningChatServiceTest.kt`, `PlanningConversationTest.kt`, `commonTest/ui/ModelSettingsFixture.kt`, `jvmTest/data/skills/ProjectSkillsTest.kt`, `jvmTest/plugins/OrchestrationRenderTest.kt`, `PlanningChatRenderTest.kt`, `PlanningWizardRenderTest.kt`, `jvmTest/ui/components/OrchestrationFailureRenderTest.kt`, `PlanningProposalRenderTest.kt`, `jvmTest/ui/screens/QueuedMessageCancellationRenderTest.kt`, `RequestPinsRenderTest.kt`.

### Новые untracked на момент снимка (37)

- Каталог и его доказательства: `docs/SKILLS-CATALOG.md`, `docs/skills-catalog/verification.json`, все 8 файлов `docs/skills-catalog/evidence/`.
- Отчёты project-SKILLS: `docs/skills-project/adapter-blockers.md`, `final-matrix.md`, `project-rollback.md`, `trusted-text.md`.
- Planning: `commonMain/data/coding/BackgroundCodingProjectRepository.kt`, `domain/CodingDraftUpdates.kt`, `domain/PlanningGateway.kt`, `commonTest/domain/CodingDraftUpdatesTest.kt`, `PlanningFixtures.kt`, `PlanningGatewayTest.kt`, `jvmMain/data/llm/CodexPlanningPermissions.kt`, `jvmMain/resources/coding/planning-tools.mjs`, `jvmTest/data/coding/PlanningRuntimeIntegrationTest.kt`, `jvmTest/resources/coding/planning-tools.test.mjs`.
- Новый каталог и project rollback: `jvmMain/data/skills/GithubSkillCatalog.kt`, `jvmMain/plugins/builtin/ProjectSkillRollback.kt`, `SkillCatalogPanel.kt`, `jvmTest/data/skills/GithubSkillCatalogLiveTest.kt`, `GithubSkillCatalogTest.kt`, `ProjectSkillRollbackUiTest.kt`, `SkillCatalogLiveUiTest.kt`, `SkillCatalogPanelTest.kt`, `SkillCatalogUpdateEvidenceTest.kt`, `SkillDownloadPrivacyTest.kt`.
- Coding delivery/UI: `jvmMain/data/coding/CodingSkillInput.kt`, `jvmTest/ui/AgentUiThreadTest.kt`, `jvmTest/ui/screens/ActiveCodingChatRenderTest.kt`.

## Границы следующих этапов

1. **Унификация источников:** использовать существующие `LocalSkillRepository.install` и `SkillPackageImporter`; не создавать второе хранилище или второй GitHub клиент.
2. **Импорт текста:** добавить отдельный package-builder/editor с теми же лимитами, checksum, quarantine, review и project bind. Не подключать raw text напрямую к adapter prompt.
3. **Связь с опытом:** передавать только разрешённые структурированные outcomes в `LocalSkillExperience`; `coding-runs` намеренно не является обучающим журналом и содержит полный текст, его нельзя автоматически копировать.
4. **Автокандидаты:** расширить existing `LocalSkillExperience`, сохраняя holdout-оценку, quarantine, review и activation consent. Триггер должен быть идемпотентным и не делать LLM/network запрос без подтверждённой политики.
5. **Доставка адаптерам:** заменить только статус `prepared-not-confirmed` доказуемой Pi/Codex integration-проверкой; не обещать очистку нативной памяти backend без её контракта.

## Выполненные проверки

```sh
git status --short
git diff --cached --name-status
git diff --name-status
git ls-files --others --exclude-standard
git diff --check
git diff --cached --check
```

Результат снимка: 3 staged, 35 unstaged, 37 untracked, 72 различных пути;
пересечения ровно три, перечислены выше. Добавление данного отчёта увеличивает
текущий untracked-счётчик до 38 и число путей до 73; других исходников в этом
этапе не изменено. Обе `diff --check` завершились без вывода (PASS).
