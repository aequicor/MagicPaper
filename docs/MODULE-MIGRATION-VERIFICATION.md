# Проверка модульной миграции

Текущий результат после удаления `:shared` приведён в следующем разделе `:app`.
[Таблицы прежней фазы](#зафиксированные-результаты-до-удаления-shared) сохранены
ниже как историческое доказательство; их пути и числа не описывают текущий checkout.

## Завершающий перенос корня в `:app`

Корень приложения, Koin composition, Decompose navigation и интеграционные
фикстуры перенесены в реальный каталог `app/`. Платформенные hosts зависят от
`:app`; прежнего Gradle project `:shared` и совместимого alias нет. Сохранены
Kotlin-идентификаторы данных, используемые fonts и engine classpath resources.
Удалены неиспользуемые Platform/Fonts/Chip и шаблонная иконка вместе со старой
генерацией app `Res`; renderer-фикстура находится только в `app/src/jvmTest`, а
переиспользуемый `PaperBackground` принадлежит designSystem.
В desktop app image присутствует `app-jvm` JAR, JAR модуля `shared` отсутствует.

Итог: **1605 выполнений JVM-тестов, 1597 уникальных имён, 14 ошибок исходного
baseline и 2 пропуска**. Новых или изменённых ошибок нет ни относительно исходного
commit, ни относительно последнего снимка перед удалением `:shared`.
Полный набор остаётся `BUILD FAILED` из-за известных 14 ошибок coding;
платформенные задачи, app browser tests и реальные JS/Wasm сценарии проходят.

### Последовательность проверки

Все логи новой фазы находятся в `/private/tmp/magicpaper-shared-removal`.

| Лог | Выполненные задачи и результат |
| --- | --- |
| `verification.log` | `verifyMigration :app:jsBrowserTest :app:wasmJsBrowserTest :desktopApp:packageDmg`; 3 мин 30 с. Все JVM-владельцы, платформенные сборки, Android host, браузерные и static tasks выполнены. Общий статус FAILED: 14 baseline failures и одна ошибка UI-фикстуры, исправленная и перепроверенная ниже |
| `queued-focused.log` | `:feature:coding:impl:jvmTest --tests '*QueuedMessageCancellationRenderTest'`; BUILD SUCCESSFUL за 25 с |
| `coding-final.log` | Повтор **всего** `:feature:coding:impl:jvmTest`; 1021 тест, только 14 baseline failures, BUILD FAILED за 2 мин 26 с. Исправленная UI-проверка проходит в полном наборе |
| `browser/js-smoke-run.log`, `browser/wasmJs-smoke-run.log` | Реальный Chromium, обе свежие app distributions: PASS, `errors=[]` |

UI-фикстура отмены ожидает готовность владельца черновика и использует управляемый
worker dispatcher; исходные assertions и pointer-сценарии шириной 1000/360
сохранены. Production-поведение для исправления этой проверки не менялось.
Окончательный повтор coding выполнен командой:

```sh
./gradlew -Pkotlin.daemon.jvmargs=-Xmx6144M --max-workers=2 :feature:coding:impl:jvmTest --continue --no-parallel
```

| Текущий владелец / проверка | Результат |
| --- | --- |
| `:app:jvmTest` | 91 тест, 0 ошибок |
| `:designSystem:jvmTest` | 57 тестов, 0 ошибок |
| `:feature:coding:impl:jvmTest` | 1021 тест, 14 совпадающих baseline failures |
| Остальные JVM-владельцы | 436 выполнений, 0 ошибок, 2 пропуска в skills; полный состав в `counts.json` |
| `:app:jsBrowserTest`, `:app:wasmJsBrowserTest`, `:app:testAndroidHostTest` | По 81 тесту, 0 ошибок |
| `:desktopApp:test` | 6 тестов, 0 ошибок; включены в строку остальных JVM-владельцев |
| `compileMigrationTargets`, Android APK, JS/Wasm executable linking и distributions | PASS |
| `:androidApp:testDebugUnitTest` | NO-SOURCE; не считается проверенным поведением |
| `:desktopApp:packageDmg` | PASS; проверены `magicpaper` URL scheme и состав app image |
| Архитектура, Paper API, surface map | Self-tests и проверки PASS; 229 bindings: 226 composables и 3 entry points. Архитектура запрещает повторное появление `:shared`, включая alias и тестовые зависимости |

Четыре JVM-проверки фона и animation policy перенесены из прежнего shared-владельца
в designSystem: `95 + 53` стало `91 + 57`, все 148 идентификаторов сохранены.
Три common policy-проверки также сменили владельца; app browser/Android набор
содержит 81 тест вместо прежних 84. Они проходят в JVM-наборе designSystem;
отдельный browser-прогон designSystem в этой фазе не запускался.
Неизменённые storage/logging browser suites и целевые coding Android host tests
сохраняют ранее полученное доказательство: storage по 33 JS/Wasm, logging по 14,
coding host 17. Их отдельные логи перечислены в manifest; они не выдаются за
повторный запуск в составе `verification.log`.

### Неизменяемый снимок и две сверки

После завершения Gradle и обоих живых browser smoke сохранены **374 XML**,
включая **293 JVM reports**, в
`/private/tmp/magicpaper-shared-removal/final-verification-xml`.
Время фиксации: `2026-09-13T11:39:47.894588+00:00`.
SHA-256 всех отчётов совпадают с [manifest.json](/private/tmp/magicpaper-shared-removal/final-verification-xml/manifest.json);
[counts.json](/private/tmp/magicpaper-shared-removal/final-verification-xml/counts.json)
разделяет реальные выполнения и уникальные имена.
Восемь одинаковых имён по-прежнему проверяют отдельные chat/coding реализации.

| Сравнение по точной test identity и нормализованной ошибке | Результат |
| --- | --- |
| [Исходный HEAD `2a45da7c04f2`](/private/tmp/magicpaper-shared-removal/final-verification-xml/jvm-comparison.json) | 1438 → 1597 уникальных имён; 0 новых/изменённых ошибок, 14 совпадающих, 35 исправленных; 169 новых имён и 10 ранее объяснённых замен |
| [Последний снимок до удаления shared](/private/tmp/magicpaper-shared-removal/final-verification-xml/previous-jvm-comparison.json) | 1597 → 1597; новых и исчезнувших имён нет; новых/изменённых ошибок нет; все 14 failures совпадают |

Сравнение использует неизменённый `compare-jvm-baseline.py`. Новый снимок создан
`capture-migration-results.py`; его self-test проверяет перенос владельцев,
дубликаты и исключение архивных test folders. Предыдущие 373 XML в
`/private/tmp/magicpaper-migration/final-verification-xml` не перезаписывались:
их SHA-256 повторно проверены и совпадают. Вторая сверка воспроизводится так:

```sh
python3 docs/compare-jvm-baseline.py /private/tmp/magicpaper-migration/final-verification-xml /private/tmp/magicpaper-shared-removal/final-verification-xml --output /private/tmp/magicpaper-shared-removal/previous-comparison-repeat.json
```

### Свежая проверка в браузере и ограничения

Обе distributions после переноса в `:app` проверены в Chromium. SHA-256 из JSON
совпадают с текущими `webApp.js` соответствующих distributions.

| Цель / доказательство | UTC 2026-09-13 | Ресурсы | Результат |
| --- | --- | ---: | --- |
| [JS smoke](/private/tmp/magicpaper-shared-removal/browser/js-smoke.json) | 11:33:09.627–11:33:17.640 | 99 | PASS, `errors=[]` |
| [Wasm smoke](/private/tmp/magicpaper-shared-removal/browser/wasmJs-smoke.json) | 11:33:40.682–11:33:46.415 | 105 | PASS, `errors=[]` |

Проверены холодные `/settings/models` и `/docs/request-pins`, точные visit IDs
A→B→A, browser/UI Back/Forward, reload, закрытие документа и восстановление
исходного Forward visit в новом документе, дублирование вкладки при занятом lock,
независимые persisted journals, сохранение sentinel при IndexedDB v1→v2 и
разрешение всех шести immutable presentation references без inline presentation.

Установка/запуск DMG и MSI/DEB, холодный/повторный/одновременный URI launch,
активация свёрнутого установленного приложения и protocol update/unregister:
**NOT_RUN**. MSI/DEB требуют соответствующих ОС. Android device/instrumentation,
живые Pi/Codex-сессии на пользовательских проектах и FPS/длительное профилирование
прокрутки/потока токенов также **NOT_RUN**. Сборка, render и browser smoke не
подменяют эти проверки.

## Зафиксированные результаты до удаления `:shared`

Ниже сохранены исходные пути, команды, имена модулей и числа предыдущего снимка.
Исторические ссылки на `shared` описывают проверенную тогда ревизию и не являются
командами для текущего checkout. Сам снимок XML и его SHA-256 не изменяются.

Итоговый снимок содержит **1605 выполнений JVM-тестов,
1597 уникальных имён, 14 ошибок исходного baseline и 2 пропуска**.
Точное сравнение с исходным commit: новых ошибок 0, изменённых ошибок 0.
Полный JVM-набор сохраняет известные ошибки и не объявляется зелёным.
JS/Wasm browser tests, Android host tests, актуальные сборки платформ и реальные
браузерные сценарии JS/Wasm проходят.

## Итоговые запуски и снимок

Все проверки выполнялись последовательно. Полный JVM-повтор завершился
`BUILD FAILED` за 2 минуты 19 секунд только из-за 14 ошибок coding baseline.
Последний повтор затронутых shared/desktop и платформ завершился
`BUILD SUCCESSFUL` за 1 минуту 6 секунд после исправления синхронизации browser
Back/Forward; он заново собрал обе web distributions, APK и DMG.

| Запуск, лог в `/private/tmp/magicpaper-migration` | Результат |
| --- | --- |
| `migration-final-jvm-recheck.log`: `checkMigrationJvm` | Все JVM-владельцы проверены; coding 1021/14 baseline, остальные проходят |
| `navigation-final-recheck.log`: shared JVM/JS/Wasm/Android host, `compileMigrationTargets`, desktop tests и DMG | BUILD SUCCESSFUL; финальный повтор всех потребителей изменения навигации |
| `migration-final-verification.log`: `verifyMigration` вместе с shared browser tests и DMG | Платформенные задачи и static checks прошли; общий запуск завершился FAILED из-за coding tests, окончательная JVM-сверка относится к последующему полному повтору выше |
| `coding-forms-host.log` | Целевые coding Android host tests: 17, без ошибок |
| `separate-presentation-tests.log`, `forms-targeted-tests.log` | Отдельные storage JS и затем Wasm browser tasks: по 33, без ошибок; статус этих задач отделён от других задач в составных запусках |
| `final-browser-fixes.log` | Неизменённые logging browser tasks: по 14 JS/Wasm, без ошибок |

Команды финальной последовательности (Chromium 153 из изолированного каталога):

```sh
export CHROME_BIN='/private/tmp/magicpaper-browser/chromium-1243/chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing'

./gradlew -Pkotlin.daemon.jvmargs=-Xmx6144M --max-workers=2 verifyMigration :shared:jsBrowserTest :shared:wasmJsBrowserTest :desktopApp:packageDmg --continue --no-parallel

./gradlew -Pkotlin.daemon.jvmargs=-Xmx6144M --max-workers=2 checkMigrationJvm --continue --no-parallel

./gradlew -Pkotlin.daemon.jvmargs=-Xmx6144M --max-workers=2 :shared:jvmTest :shared:jsBrowserTest :shared:wasmJsBrowserTest :shared:testAndroidHostTest compileMigrationTargets :desktopApp:test :desktopApp:packageDmg --continue --no-parallel
```

Для связывания большого shared Wasm test executable использован Kotlin daemon
с heap 6 GiB. Проверки отдельных владельцев учитываются по их задачам/XML;
неуспешный aggregate не превращается в PASS из-за успешной соседней задачи.

После остановки Gradle и прохождения обоих живых browser smoke сохранены
**373 XML**, включая 292 JVM reports, с относительными путями модулей:
`/private/tmp/magicpaper-migration/final-verification-xml`.
Время фиксации: `2026-09-13T11:14:07.586054+00:00`.
`manifest.json` содержит SHA-256 отчётов и логов; `counts.json` — подсчёты по задачам,
`jvm-comparison.json` — точное сравнение с baseline.

```sh
python3 docs/capture-migration-results.py . /private/tmp/magicpaper-migration/final-verification-xml --baseline /tmp/magicpaper-head-baseline --run-log /private/tmp/magicpaper-migration/migration-final-jvm-recheck.log --run-log /private/tmp/magicpaper-migration/navigation-final-recheck.log
```

Повторная команда требует нового пустого каталога: существующий снимок не
перезаписывается. Фильтр учитывает только текущие Gradle test folders; прежние
архивы `skills-*`, `runtime-*` и подобные каталоги не попадают в результат.
Self-test скрипта проверяет это, перенос владельцев и отдельный учёт дубликатов.

## JVM по владельцам

| Владелец | Выполнения | Ошибки | Пропуски |
| --- | ---: | ---: | ---: |
| `designSystem` | 53 | 0 | 0 |
| `core:model` | 68 | 0 | 0 |
| `core:logging` | 15 | 0 | 0 |
| `core:platform` | 5 | 0 | 0 |
| `core:ai:impl` | 73 | 0 | 0 |
| `core:storage:api` | 2 | 0 | 0 |
| `core:storage:impl` | 36 | 0 | 0 |
| `feature:chat:impl` | 36 | 0 | 0 |
| `feature:coding:impl` | 1021 | 14 | 0 |
| `feature:settings:impl` | 35 | 0 | 0 |
| `feature:docs:impl` | 4 | 0 | 0 |
| `feature:plugins:impl` | 9 | 0 | 0 |
| `feature:skills:impl` | 147 | 0 | 2 |
| `shared` | 95 | 0 | 0 |
| `desktopApp` | 6 | 0 | 0 |
| Всего | 1605 | 14 | 2 |

Восемь имён выполняются отдельно в chat и coding: шесть методов
`RequestPinsBrowserTest` и два `ChatScrollToBottomTest`. Они проверяют разные
реализации экранов. Baseline-сравнение объединяет одинаковые имена и учитывает
ошибку любого владельца, поэтому уникальных имён 1597. Пропуски включены в общее
число XML test cases и не считаются проверкой поведения.

## Сравнение с исходным commit

Commit `2a45da7c04f2` экспортирован через `git archive` в отдельный каталог.
Скопирован только локальный путь Android SDK (`local.properties`); исходники
не менялись. Команда `./gradlew :shared:jvmTest :designSystem:jvmTest :desktopApp:test
--continue` завершилась за 3 минуты 3 секунды:

| Исходный модуль | Тесты | Ошибки | Пропуски |
| --- | ---: | ---: | ---: |
| `shared` | 1395 | 49 | 2 |
| `designSystem` | 39 | 2 | 0 |
| `desktopApp` | 4 | 0 | 0 |
| Всего | 1438 | 51 | 2 |

Исходные XML: `/tmp/magicpaper-head-baseline/{shared,designSystem,desktopApp}/build/test-results`;
лог: `/tmp/magicpaper-head-all-tests.log`. Воспроизводимое сравнение:

```sh
python3 docs/compare-jvm-baseline.py /tmp/magicpaper-head-baseline /private/tmp/magicpaper-migration/final-verification-xml --output /private/tmp/magicpaper-migration/final-verification-comparison-repeat.json
```

Скрипт завершился с кодом 0: новых ошибок 0, изменённых 0, совпадающих с baseline
14, ранее падавших и теперь проходящих с тем же именем 35. Добавлено **169 новых
имён**; 10 старых сопоставлены переименованным или заменённым проверкам ниже.
Две другие ошибки baseline принадлежат заменённым shell/tree тестам; их текущие
проверки проходят. Safety assertions UNKNOWN/admission/path-global leases
не исключались и не ослаблялись ради результата.

| Сохранившаяся группа baseline | Ошибки |
| --- | ---: |
| `SessionLegacyAdmissionTest` | 1 |
| `PlanningExecutionServiceTest` | 2 |
| `SessionAuxiliaryRuntimeTest` | 1 |
| `SessionCodingWorkspaceTest` | 5 |
| `SessionHistoryDeletionTest` | 2 |
| `SessionIntegrationTest` | 3 |
| Всего | 14 |

Совпадение проверено по полному `classname.method`, типу и сообщению ошибки с
нормализацией временных путей. Все имена и диагностические типы сохранены в
`jvm-comparison.json`; правила `compare-jvm-baseline.py` не менялись.

| Старое имя или группа | Текущая проверка и причина изменения |
| --- | --- |
| `ProjectSkillsTest.trustedTextConsentPersistsAndDisconnectNeverResumesOldContext` | `ProjectSkillInputTest`, тот же контракт в coding-владельце подготовки runtime input |
| Три метода `ModelSettingsViewModelTest` | Те же методы `ModelSettingsServiceTest`, отдельные chat/settings services |
| `ShellSettingsViewModelTest.explicitAgentLimitsPersistAndCanBeRemovedWithoutChangingOtherSettings` | Тот же метод `ShellSettingsComponentTest`, сохранение через settings service |
| `ShellSettingsViewModelTest.navigationSidebarSettingsAndWelcomeKeepTheirExistingStateContracts` | `ShellSettingsComponentTest.welcomeGatePreservesPendingDeepLinkAndSettingsWritesDoNotReplaceHistory` и `AppShellRenderTest.hidingSidebarRetainsTheFeatureAndSelectedArticlesRenderWithDurablePresentation`; реальный journal и семантические действия sidebar |
| `UsageUiTest.contextAndSystemMessagesUseAgentLayoutAndKeepDraftOnResize` | Тот же метод `CodingUsageUiTest`, разделённый владелец экрана |
| `CodingComposerRenderTest.planningQuestionIsSentWithoutResumingAndEmptyComposerCanStillContinue` | `planningQuestionRetainsDraftUntilOwnerAcknowledgesAndEmptyComposerCanContinue`, ввод до durable acceptance |
| `ProjectSessionTasksTest.collapseOfWorkTreeKeepsImmunityVisibleAndPreservesNestedDisclosure` | `nestedDisclosurePreservesHeaderOwnedRootAndImmunity`, root/immunity в заголовке, вложенные строки отдельно |
| `JsonLlmProfileRepositoryTest.corruptedDataFallsBackToEmpty` | `corruptedDataSurfacesErrorAndPreservesSource`, ошибочное чтение сохраняет исходные данные |

Непреднамеренно исчезнувших проверок среди этих десяти имён не обнаружено.

## Browser, Android и упаковка

| Проверка | Результат |
| --- | --- |
| `shared:jsBrowserTest` и `wasmJsBrowserTest` | По 84 теста в Chromium, 0 ошибок |
| `core:storage:impl:jsBrowserTest` и `wasmJsBrowserTest` | По 33 теста, 0 ошибок |
| `core:logging:jsBrowserTest` и `wasmJsBrowserTest` | По 14 тестов, 0 ошибок |
| `shared:testAndroidHostTest` | 84 теста, 0 ошибок |
| Целевые `feature:coding:impl:testAndroidHostTest` | Отдельный запуск: 9 coding forms + 8 durable input acceptance, всего 17, 0 ошибок |
| `androidApp:testDebugUnitTest` | NO-SOURCE, не считается проверкой поведения |
| `androidApp:assembleDebug` | PASS; `androidApp/build/outputs/apk/debug/androidApp-debug.apk`, 22 MiB |
| Desktop compilation и `desktopApp:test` | PASS, 6 тестов desktop |
| JS/Wasm development executable link | PASS с `-Xpartial-linkage-loglevel=ERROR` |
| JS/Wasm development webpack/distribution | PASS; обе distributions собраны после последнего изменения навигации |
| `desktopApp:packageDmg` | PASS; `desktopApp/build/compose/binaries/main/dmg/MagicPaper-1.0.0.dmg`, 95 MiB; `magicpaper` URL scheme проверена в bundle Info.plist |
| Установка и запуск установленного DMG | NOT_RUN |
| Сборка/установка MSI и DEB | NOT_RUN на текущем macOS host |
| Native URI: cold/repeat/minimized/simultaneous launches | NOT_RUN для установленного Desktop bundle; browser cold routes проверены отдельно |
| Обновление/удаление DMG/MSI/DEB, регистрация/снятие native URI protocol | NOT_RUN; запись scheme в Info.plist не заменяет проверку установленной системы |
| Android device/instrumentation tests | NOT_RUN; host tests не подменяют устройство |
| Live Pi/Codex integration с пользовательскими проектами | NOT_RUN, остаётся opt-in |
| FPS, длительный scrolling/token streaming и frame-time profiling | NOT_RUN; статические renders и browser smoke не измеряют плавность |

## Проверенные контракты

Koin сохраняет singleton ownership, идемпотентные start/close и изоляцию двух
runtime. Разрешаются конкретные типы всех шести component factories на JVM/JS/Wasm;
JVM `KoinOwnerResolutionTest` использует `koin-test:4.2.2` и свой `getKoin()` без
глобального контекста. Закрытие одного runtime не останавливает другой.

Decompose tests покрывают полный journal с Back/Forward, welcome/deep links,
malformed links, отказ фабрики/хранилища, retry, независимость browser journals,
диалоги и presentation state. Новые
`queuedPresentationCannotReverseBrowserBackBeforeRootAcknowledgesTheVisit` и
`rapidBrowserPopsIgnoreEarlierAcknowledgementAndKeepTheLatestVisit` проверяют
публикацию старого cursor во время очереди autosave, подтверждение перехода root
и игнорирование устаревшего acknowledgement при двух последовательных pop.
Восстановление не создаёт сущности и не запускает работу.

| Граница | Проверка и наблюдаемый контракт |
| --- | --- |
| Journal и отдельные состояния посещений | `ReferencedNavigationSnapshotStoreTest`, `NavigationPresentationPersistenceTest`, `FileDurableByteStoreTest`: journal содержит ссылки, состояние хранится отдельно; новый владелец восстанавливает его, inline legacy мигрирует после успешной записи, отсутствующая ссылка сохраняет исходный journal с ошибкой |
| Атомарность presentation records | Те же storage tests: ошибка staging/commit сохраняет последнюю версию; immutable reference нельзя заменить; fork сохраняет захваченное состояние после удаления исходной ссылки; GC учитывает все journals и не удаляет данные при неполном индексе; поздние записи после reset отвергаются |
| Coding формы и идентичность операции | `CodingFormDraftsTest`: raw invalid input и operation ID переживают restart; accepted cleanup повторяет только очистку; новое редактирование во время clear остаётся открытым; удаляются также неоткрытые формы; shutdown пытается сохранить всех владельцев при ошибке одного |
| Доставка и переименование | `OrchestrationInputAcceptanceTest`: durable receipt предшествует отображению сообщения; retry после restart не дублирует доставку, payload с прежним ID проверяется, адресат сохраняется после переименования worker, rename/delete сериализованы и baseline имени защищает более новое изменение |
| Выбор движка новой сессии | `SessionDraftDeletionTest` и root dialog tests: выбор сохраняется по project ID; clearing ожидает сохранения сессии, старый результат не закрывает заново открытый диалог; explicit cancel очищает, Back сохраняет ввод |
| Нативные формы навыков | `SkillsFormPersistenceTest`, `SkillFormActionTest`: импорт, review, backup, project selection и rollback восстанавливаются без принятия действия; согласия связаны с checksum/generation; команды переживают disposal панели, а reset/delete/shutdown прекращают старых владельцев и дожидаются принятой работы |
| Форма локального опыта | `ExperienceFormOwnerTest`: raw days, enum fields, выбранные IDs и display preview восстанавливаются; профиль сохраняется только как ID, send capability не восстанавливается; retry cleanup не записывает результат дважды; принятая generation переживает уход с экрана; maintenance failure видна до успешной проверки |
| Реальные Paper формы | `MessageSchedulerRenderTest`, `SkillsFormPersistenceTest.actualPackagePanelRestoresInvalidImportAndTextFieldsWithoutWork`, `LocalExperiencePanelTest`, `VisitPresentationRenderTest`: actual semantics и renders форм, восстановленного ввода и отдельных посещений; сами снимки не заменяют тесты установленного приложения |

Storage JVM-набор вырос до 36 тестов, JS/Wasm — до 33: отдельные presentation
records, reset epoch, staging/commit, GC и сохранение последней исправной версии
проверяются через нового владельца и реальные native files/IndexedDB. JVM CodingFormDraftsTest
проверяет 10 сценариев, orchestration input acceptance — 8, scheduler renders — 2.

Logging проверяет INFO по умолчанию, уровни, lazy TRACE, redaction, ограничения
хранения, ошибочный sink/metadata и инициализацию AppLog на JVM/JS/Wasm. Ошибка
диагностики не уничтожает draft writer; raw credentials и exception messages
не выводятся. Secret/draft/blob tests проверяют миграцию и повторное открытие,
сохранение источника при ошибке и поздние записи после удаления/reset.

API/impl граф без циклов, Paper API и surface map проходят проверки. Все
19 core/feature модулей используют Gradle conventions и общий version catalog.
Финальный surface map содержит 241 binding: 238 composables и 3 entry points.
Node-проверки `coding/*` проходят с сохранёнными classpath-путями ресурсов.

## Реальный интерфейс JS/Wasm после выделения presentation records

2026-09-13, Chromium 153, изолированные browser contexts, localhost SPA server,
viewport 1360×980. Проверены реальные
`webApp/build/dist/{js,wasmJs}/developmentExecutable` после последней синхронизации
browser history и root acknowledgement. Пользовательские данные, установленное
приложение и движки не запускались.

Оба сценария PASS: cold `/settings/models` и `/docs/request-pins`, корневые assets,
A→B→A с разными точными visit IDs, UI/browser Back и Forward, reload с прежними
journal/visit и длиной browser history. Документ закрывается после durable записи
B с более новой revision, чем у A2; новый документ открывается на `/`, а Forward
возвращает **исходный A2 visit ID**. Проверяется идентичность посещения, а не только URL.

Дублирование вкладки с копией history.state/sessionStorage при удерживаемом Web Lock
создаёт отдельный journal; изменения и повторная загрузка вкладок независимы.
Реальный IndexedDB v1 обновляется до v2 с сохранением исходной записи. В durable
journal нет inline `presentation`; `presentationRefs` согласованы с индексом,
все шесть проверенных immutable ссылок разрешаются в отдельные `view-states`.

| Target | UTC начало — завершение | Ответы ресурсов | Ошибки |
| --- | --- | ---: | --- |
| JS | 2026-09-13T11:09:42.018Z — 2026-09-13T11:09:50.501Z | 99 | `errors=[]` |
| Wasm | 2026-09-13T11:12:20.004Z — 2026-09-13T11:12:25.682Z | 105 | `errors=[]` |

SHA-256 проверенного `webApp.js` совпадает с актуальной distribution:

- JS: `991f0905a5fe375a23e41dfbd7c70656b90ebbacb6ef8c6aaf58f0045d4e7155`.
- Wasm: `79a1b42665ce5c79c1fb0260126f6f6b6d642cc75daaea950783a4fdbe29dfb6`.

Воспроизводящий script и JSON-результаты:
`/private/tmp/magicpaper-migration/browser/smoke.cjs`, `js-smoke.json`,
`wasmJs-smoke.json`; полные логи `js-smoke-run.log`, `wasmJs-smoke-run.log`.
Там же сохранены `{js,wasmJs}-{cold-models,cold-article,source-settings,duplicate-docs}.png`.
Проверка не устанавливает поведение установленного Desktop bundle или Android device.
