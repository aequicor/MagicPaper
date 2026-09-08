# Продолжение: барьеры применения Pi и Codex

> Исторический журнал блокировок. После явного согласования пользователем реализован opt-in режим доверенного текста: [актуальный контракт и проверки](trusted-text.md). Утверждения ниже о полном запрете описывают предшествующие снимки, а не текущий согласованный режим.

## Фактическое состояние

Входной `git status --short`: изменён только `CodingScreen.kt`. Его чужая правка переносит параметры в меню и заменяет видимый «+» информационной иконкой; пункт SKILLS сохранён. Это расхождение с буквальным критерием «+» не исправлялось без согласования. `MagicPaperViewModel.kt`, ProjectsPanel, ToolStepRow и прокрутка этим исполнителем не менялись.

`DesktopCodingRuntime.run` по-прежнему блокирует непустой снимок до preflight/транспорта. Добавлен отказ при `session.projectId != project.id` **до чтения привязок**, в том числе при resume. Это защита от ошибочной маршрутизации, не доказательство очистки истории.

## Pi: отдельный блокер

`PiCodingRuntime.runPiAttempt` отключает автозагрузку через `--no-skills`/`--no-extensions`, затем явно добавляет расширения приложения. Обычный `ProcessBuilder` запускает агент с cwd проекта; отдельный sessionHome и defaultProjectTrust не являются файловой/сетевой изоляцией. Shell и остальные инструменты не ограничены независимым OS-enforcement в этом пути. Передача инструкции обычным пользовательским текстом не устраняет возможность чтения внешнего файла или запуска команды по инструкции пакета.

Недостающая возможность: проверяемый исполнитель инструментов с независимым ограничением файлов/сети/дочерних процессов и сохранением разрешённых coding-инструментов. Нужна отдельная реализация/поддерживаемая OS-среда и атакующие проверки реального процесса. Prompt-фильтр и расширение, блокирующее только имя install.sh, не подходят.

## Codex: отдельный блокер

`runCoding` использует `thread/resume` либо `thread/start`, затем `turn/start`. `CodexCodingPermissions` задаёт workspaceWrite, networkAccess=false, writableRoots проекта и допустимого Gradle cache; approvals остаются on-request/auto_review. Это существующие разрешения coding-агента, а не выданные пакету разрешения.

Они сами по себе не обеспечивают отдельный запрет пакетного кода: выполнение shell внутри workspace остаётся рабочим инструментом, read allowlist для секретов не задаётся этим классом. Путь также не предъявляет доказательства отключения нативной загрузки навыков для start/resume. Новое соединение app-server возобновляет прежний thread, а не стирает историю.

Недостающая возможность: подтверждённый контракт конкретной версии app-server для отключения нативных навыков и нерасширяемая вне LLM политика доступа/исполнения с реальными атакующими проверками. Сам JSON sandboxPolicy не считается результатом такой проверки.

## Сессии и снимки

Этот путь ни разу не передаёт пакет в движок. Тестировать отсутствие **ранее переданного** текста при resume на нём нельзя. До включения передачи нужны постоянные метаданные engine-session → project + digest полного снимка. При отключении/обновлении/смене проекта или неизвестном digest нужен новый изолированный engine-session без replay прежних сообщений; простое обнуление ID без контроля replay/нативного контекста недостаточно. Это проект следующей реализации, не выполненный критерий.

Сейчас доказаны точные ID/version/checksum/text на выходе хранилища и ID/version/checksum в квитанции отказа приложения. Точная передача текста в реальные Pi/Codex, OS-enforcement и очистка контекста — **NOT_RUN**. Запрет не снят, D2/обучающий payload не менялся.

## Предыдущая проверка дерева

Команда:

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*CodingComposerRenderTest' --tests '*CodexCodingPermissionsTest' :desktopApp:compileKotlin
```

BUILD SUCCESSFUL; XML `shared/build/test-results/jvmTest`: 17 suites, 102 tests, 0 failures/errors/skipped. Лог: `shared/build/skills-continuation-check.log`. Новый тест `crossProjectStartAndResumeRejectBeforeSnapshotOrTransport` перебирает оба движка и start/resume: ноль чтений снимка, отсутствие SessionStarted/Notice и каталогов транспорта, видимый отказ. Остальные тесты повторно проверяют привязки, обновление, карантин, отмену, повреждение, отказ адаптеров и затронутый UI. `git diff --check` — PASS. Эти 102 теста **не являются доказательством живой интеграции**.

## Повторная проверка после входящего сообщения

При повторном входе обнаружены staged-изменения соседнего плана, в том числе MagicPaperViewModel и тайм-ауты/очистка CodexAppServerOpenAiSubscription. Они прочитаны и сохранены; во время проверки соседние изменения вошли в HEAD `232c1ef`. Собственные исходники в этом повторе не менялись, прежние guard и тест на месте.

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*CodingComposerRenderTest' --tests '*CodexCodingPermissionsTest' --tests '*CodexCodingRecoveryTest' --tests '*CodexApproval*Test' --tests '*PlanningResponseTimeoutTest' --tests '*ModelSettingsViewModelTest' :desktopApp:compileKotlin
./gradlew --no-daemon :shared:jvmTest --tests '*ProjectsPanel*Test' :desktopApp:compileKotlin
```

Первая команда: **BUILD FAILED**, 125 tests, 3 failed. Вторая: **BUILD FAILED**, 6 tests, те же 3 failed. Desktop compileKotlin в обоих случаях UP-TO-DATE, ошибок компиляции нет. Воспроизводятся:

- ProjectsPanelCollapseTest.childCollapseSurvivesUpdatesSelectionAndProjectRoundTrip:65 — `Child disclosure must hide the stage`.
- ProjectsPanelCollapseTest.selectedProjectHeaderCollapsesAndReopensItsSessionList:91 — `Repeated click on current project must collapse its session list`.
- ProjectsPanelStickyTest.projectRemainsAbovePinnedSessionAndBothHeadersStayClickable:93 — `Expected value to be true`.

Причина этих UI-отказов не установлена; они не приписываются соседним изменениям без диагностики. Логи: `shared/build/skills-continuation-recheck.log`, `shared/build/skills-panels-recheck.log`; XML первого отказа сохранены в `shared/build/skills-recheck-failures/`. Старый PASS выше не описывает текущую повторную проверку. `git diff --check` и `git diff --cached --check` — PASS. Живые адаптеры не запускались. Ответа об OS-enforcement в повторном сообщении нет; запрет применения сохранён.

## Решение пользователя и проверка после диагностики

Получен ответ через оркестратора: платформа Desktop, использовать изоляцию, предусмотренную coding-backend; информационная иконка согласована. Вопрос об иконке закрыт, её чужая реализация сохранена. Контейнер/VM не добавлялись. Выбор штатной защиты не считается доказательством её достаточности для пакетов.

Причина трёх UI-отказов установлена: тесты сохранили смещение 49 px от удалённого заголовка ProjectsPanel. В текущем компоненте LazyColumn начинается у верхней границы сцены. CollapseTest посылал клики ниже нужной строки; StickyTest проверял устаревшую границу. Исправлены только эти координаты/границы в двух тестах; все проверки кликов, смены проектов, сворачивания и sticky-поведения сохранены. Производственный UI не менялся.

Повторена первая команда из предыдущего раздела (125 тестов и desktop-компиляция): **BUILD SUCCESSFUL**, 125 tests, 0 failures/errors/skipped. Лог: `shared/build/skills-backend-decision-check.log`; XML: `shared/build/test-results/jvmTest`. `git diff --check` и `git diff --cached --check` — PASS. Исторические отказы выше сохранены, теперь диагностированы и устранены в тестах.

Итог по интеграции: **BLOCKED**, не готово к закрытию. В текущем Pi-пути штатные флаги отключают автозагрузку, но не обеспечивают независимую файловую/сетевую изоляцию инструментов. В текущем Codex-пути штатный workspaceWrite ограничивает запись/сеть, но не доказывает запрет пакетного исполнения внутри разрешённого workspace и нативного контекста навыков. Требуется подтверждённая возможность соответствующего backend обеспечить эти ограничения без отключения разрешённых coding-инструментов; существующие настройки не заменены prompt-ограничениями. Непустые снимки по-прежнему отвергаются с видимой причиной до транспорта. Передача в реальные движки и удаление ранее переданных инструкций — NOT_RUN. Новая изолированная engine-session остаётся необходимой частью будущего пути применения, а не реализованным свойством. D2 не менялся.

## Уточнение после разрешения ослабить изоляцию

Пользователь разрешил ослабить недостижимую изоляцию. Это снимает необходимость снова выбирать контейнер/VM, но оставляет неоднозначным отдельный критерий «не запускать скрипты пакетов». Для режима доверенных текстовых инструкций можно не загружать пакетные файлы как плагины и не запускать их установщики самим приложением. Однако агент с работающим shell может исполнить команды из SKILL.md; при общих инструментах отдельные заявленные разрешения пакета не являются enforceable ACL. Это надо явно согласовать, а не объявлять доказанным запретом исполнения. Предлагаемый вопрос: допустим ли opt-in режим доверенного текста с подтверждением риска для точного checksum, где приложение не запускает код пакета автоматически, но агент действует с обычными полномочиями backend и абсолютный запрет исполнения команд из инструкции не гарантируется? D2, карантин, точные привязки, квитанции и новая engine-session при изменении состава этим решением не отменяются. Такой режим пока не реализован; текущий запрет сохранён до уточнения.

Сверено актуальное дерево (HEAD на входе `27c1e78`): чужие изменения RequestPins, ChatScreen, CodingScreen и RequestPinsRenderTest сохранены. Новых изменений исходников этим исполнителем нет.

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*CodingComposerRenderTest' --tests '*RequestPinsRenderTest' --tests '*CodexCodingPermissionsTest' :desktopApp:compileKotlin
```

BUILD SUCCESSFUL; 109 tests, 0 failures/errors/skipped. Лог: `shared/build/skills-relaxation-check.log`. `git diff --check` и `git diff --cached --check` — PASS. Сообщённый `ZipFile invalid LOC header` этой командой не воспроизведён; исправление ZIP не заявляется, кеши не удалялись. Живые Pi/Codex и очистка ранее переданного контекста — NOT_RUN.

## Контролируемая wire-проверка адаптеров

Добавлен `SkillAdapterWireIntegrationTest`: он запускает настоящий путь
`DesktopCodingRuntime` до дочернего процесса Pi CLI и JSONL app-server Codex,
но подменяет сами backend-процессы локальными записывающими фикстурами. Сети,
учётных данных и внешнего backend нет. Это проверка границы транспорта, а не
заявление о приёме инструкций живой моделью.

Проверены start и resume для обоих адаптеров. При trusted pin фикстура получает
точные `id`, `version`, `checksum` и `text`; старый engine ID не доходит до Pi
(`--session-id` отсутствует) и Codex использует `thread/start`, то есть
fresh-session. Quarantined `2.0.0` и его текст отсутствуют в обоих wire payload.
При пустом составе Pi получает `--session-id` прежней сессии, Codex —
`thread/resume`, а `turn/start` содержит только исходную задачу без SKILLS.

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.SkillAdapterWireIntegrationTest' --console=plain
```

Результат: **BUILD SUCCESSFUL**, 2 tests, 0 failures/errors/skipped. Реальные
Pi CLI/Codex app-server и приём payload моделью не запускались: **NOT_RUN**.
Независимое OS-enforcement и доказуемая очистка ранее переданного backend
контекста по-прежнему **BLOCKED** контрактом backend.

## Повторная проверка после автоматических кандидатов

После завершения этапа automatic candidates перечитаны
`ExperienceSuggestions.kt`, `LocalSkillExperience.kt`,
`LocalExperiencePlugin.kt`, `SkillRunCompletion.kt`, актуальные adapter tests и
`automatic-candidates.md`. Несовместимых пересечений нет. Автокандидат возникает
только после трёх разных UUID с `PASSED`/`USER_CONFIRMED` для одинакового
scenario/features; `FAILURE`, `CANCELLATION`, `UNKNOWN`, `UNAVAILABLE`, replay
одного UUID и failed verification исключены. До `generate(..., true)` нет вызова
gateway или мутаций repository; после него пакет остаётся `QUARANTINED`, review
не равен activation/project bind, а restart/concurrent generation не используют
те же источники.

Основание `SUCCESS` уточнено: default `SkillRunVerification` возвращает
`UNAVAILABLE`, поэтому штатный coding-run фиксируется как `UNKNOWN` без
scenario/features. Положительный путь в тестах исходит исключительно от
внедрённого application `SkillRunVerifier` с verdict `PASSED`, а не от текста
модели. `SkillRunCompletionTest` дополнительно подаёт секретные session/chat,
prompt/output и file/tool строки и подтверждает, что experience JSON их не
содержит. В generation gateway идут только закрытые scenario/features и
синтетические catalog cases; UUID, проекты, рабочие файлы, prompt/output и
секреты туда не передаются. Пользовательская задача, необходимая самому coding
turn, по определению остаётся входом Pi/Codex и не является experience payload.

Повторная wire-проверка не выполнялась: `SkillAdapterWireIntegrationTest` и
adapter sources после предыдущего успешного прогона не менялись. Его текущий
код по-прежнему фиксирует exact `id/version/checksum/text` только trusted pin,
исключает unbound quarantined release и отправляет при resume пустой SKILLS
состав. Живые backend/model — **NOT_RUN**; OS-enforcement и очистка уже
существующего backend context — **BLOCKED**, так как Pi shell и Codex
workspace-write/native context не дают независимого доказуемого ограничения.

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.SkillImportContractTest' --tests 'io.aequicor.magicpaper.data.skills.LocalSkillsPanelTest' --tests 'io.aequicor.magicpaper.data.skills.ExperienceSuggestionsTest' --tests 'io.aequicor.magicpaper.data.skills.LocalSkillExperienceTest' --tests 'io.aequicor.magicpaper.data.skills.SkillRunCompletionTest' --tests 'io.aequicor.magicpaper.data.skills.ProjectSkillsTest' --console=plain
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.LocalExperiencePanelTest' --console=plain
git diff --check
git diff --cached --check
```

Обе Gradle-команды: **BUILD SUCCESSFUL**. Первая: **58 tests**, вторая:
**1 test**; failures/errors/skipped отсутствуют. Обе diff-check — PASS.
