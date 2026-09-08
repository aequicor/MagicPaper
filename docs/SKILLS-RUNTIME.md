# Исполнение и управление пакетами навыков

2026-09-07, передача в `ms_acceptance`.

**Актуальный статус:** дефект сворачивания ProjectsPanel исправлен; итоговый полный
JVM-прогон — 442 теста без ошибок и успешная desktop-компиляция. Подробности и снимок
исходников приведены в заключительном разделе. Прежний красный прогон ниже
сохранён как история диагностики, а не текущий статус.

Дополнение по замечанию приёмки: после усиления доказательств выполнен новый
адресный прогон **61 тест без ошибок**. Конкретные входы, результаты и изображение
применения перечислены в разделе «Прямые доказательства критериев runtime» в конце.
Исторический полный прогон 442 тестов после изменения интеграционного теста
не повторялся; производственные исходники при усилении проверки не менялись.

## Реализованный путь

`LocalSkillRepository` реализует общий порт `SkillInstructionSource`. Под mutex
читаются метаданные и инструкция одной активной версии; проверяются статус
VERIFIED и байты пакета. Карантинные и просто установленные версии не выдаются.
DI передаёт этот источник из существующего `LocalSkillsPlugin` в `MagicAgent`.
Повторный экземпляр хранилища и обход его файловой блокировки не создаются.

Сохранены поиск, просмотр инструкций/ресурсов, review, предварительный просмотр
активации, подтверждение новых разрешений и откат панели предшественника.
В карточке активной версии показана команда `@skill:<id> текст задачи` для
явного выбора в чате. Без команды применяется существующий лексический подбор
до трёх активных навыков. Неактивный ID блокируется до отправки модели.
Ответ содержит имя, версию, префикс checksum, заявленные разрешения и фактический
режим доступа. Ошибка модели также сохраняет этот отчёт.

## Граница доступа

Новый runtime работает только с текстовыми API-профилями. Он не получает портов
чтения проекта, процессов, инструментов или произвольных сетевых запросов.
Пакет передаётся как USER-данные; это **не** самостоятельная граница безопасности.
Граница — отсутствие диспетчера инструментов и исполняющих портов вне LLM.
Декларации READ_PROJECT/WRITE_PROJECT/NETWORK/RUN_PROCESS не становятся грантами.
Запрос идёт выбранному пользователем профилю; текст пакета не меняет профиль/URL.
В этой ветке MagicAgent не вызывает поиск и документацию.

Pi, Codex subscription и coding не получают пакеты: подтверждений изоляции,
лимитов процессов/памяти, файловой системы и сети для пакетного кода нет.
Поэтому код, включая install.sh, остаётся отключённым независимо от review и
согласия на декларации разрешений. Включателя «доверять и выполнить» не добавлено.
Причина показана в панели; subscription отклоняется до вызова шлюза.

Вывод модели и метаданные обрамляются Markdown code fence, более длинным, чем
любая последовательность backtick внутри данных. Это сохраняет URL изображений
и HTML как текст при показе в существующем ChatMarkdown. Ответ не интерпретируется
как tool call. Продолжение истории с отчётом текстового режима остаётся на этом
маршруте, даже если подбор пуст или пакет деактивирован; перенос такой истории
на subscription блокируется. Подделка маркера может только ограничить доступ.

## Контекст и состояние

- Не более 6 предыдущих текстовых сообщений. При превышении предложен новый чат
  с выбранными фрагментами; молчаливого обрезания нет.
- Весь сформированный запрос (включая инструкции, system, историю и задачу)
  ограничен 24 000 символов. Вложения текущего запроса запрещены, исторические
  вложения не читаются и не отправляются. Файлы проекта/вывод инструментов
  автоматически не добавляются.
- До отправки блокируются известные признаки ключей, Bearer, private key,
  token/password/secret и точное вхождение API-ключа выбранного профиля.
  Это детектор возможных секретов, **не** гарантия распознавания всех секретов
  и не обнаружение вредоносных инструкций. Пользователь отвечает за выбранный текст.
- Тайм-аут запроса — 60 секунд; отмена coroutine распространяется вызывающему коду.
  Ответ свыше 24 000 символов отклоняется после получения. Это предел принятого
  ответа, а не лимит памяти HTTP-транспорта или расхода токенов провайдера.
- Runtime не имеет методов записи хранилища. Ошибка LLM, отказ, отмена и лимиты
  не меняют активную версию, review, generation или файлы пакета. Текст исключения
  провайдера пользователю не выводится, поскольку он может содержать секреты.

Эти ограничения относятся к новому маршруту пакетов. Прежние legacy-навыки,
генератор опыта и обычные диалоги без пакетов не переопределены этим этапом.
Предпросмотр контекста генератора опыта относится к отдельному этапу обучения.
Платформенное подключение выполнено для desktop, где реализовано хранилище.

## Проверки

`SkillInstructionRuntimeTest` проверяет версию/разрешения и USER-роль пакета,
явный выбор, 7 сообщений, превышение общего бюджета, секреты во всех трёх
источниках текста, вложения, subscription, отмену, тайм-аут, большой ответ,
ошибку провайдера, повреждённый источник, отсутствие совпадений, а также
продолжение после деактивации без перехода к инструментальному транспорту.

`SkillRuntimeIntegrationTest` устанавливает настоящий пакет с SKILL.md и
install.sh, проходит review/активацию и вызывает MagicAgent через общий порт.
Инструкция требует прочитать реальный временный private.env, отправить его на
attacker.example и запустить скрипт создания маркера. Проверены:

- шлюз получил инструкцию активной 2.0.0, но не содержимое private.env или скрипта;
- профиль назначения остался выбранным, число вызовов — один, tool JSON ответа
  не вызвал дополнительных операций; поиск/документация в этой ветке запрещены
  тестовыми портами;
- маркер отсутствует, секретный файл неизменен;
- snapshot полностью совпадает после ошибки модели и отмены;
- после повторного открытия активны та же 2.0.0 и те же байты инструкции.

LLM заменён тестовым шлюзом; живой внешний сервер и реальные Pi/Codex не запускались.
Это проверка границы диспетчеризации приложения, не испытание OS sandbox.

## Пересечения

Итоговая команда:

`./gradlew --no-daemon --init-script shared/build/skills-runtime.init.gradle :shared:jvmTest :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 37s**: 77 suites, 425 тестов, 0 failures/errors/skipped.
Из них 10 тестов нового runtime, 1 интеграционный, прежние проверки хранилища
и панели, оба `AgentMessageStatusTest` (раскрытие работающей команды до вывода
и сохранение раскрытия при обновлении рассуждения). Это проверка существующих
тестов сворачивания статуса, не автоматизация всего списка сессий проекта.
SHA-256 всех файлов shared/src до и после финального прогона совпадают.
`git diff --check` — PASS.

Отчёты: [HTML](../shared/build/reports/tests/skills-runtime/index.html),
[runtime XML](../shared/build/test-results/skills-runtime/TEST-io.aequicor.magicpaper.domain.SkillInstructionRuntimeTest.xml),
[интеграция XML](../shared/build/test-results/skills-runtime/TEST-io.aequicor.magicpaper.data.skills.SkillRuntimeIntegrationTest.xml).
Init-script — локальный build-артефакт: меняет только каталоги XML/HTML задачи Test
на `test-results/skills-runtime` и `reports/tests/skills-runtime`.
Без него воспроизводится та же проверка со стандартными путями отчётов.
Сборка использовала обычный кеш Gradle и штатное разрешение после отказа
песочницы на локальный сокет; `--offline` не использован. Условные внешние
интеграционные тесты без включённых флагов могут завершаться досрочно без
XML-статуса skipped; общее число не доказывает работу внешних сервисов.

После ответа «я не знаю» список соседнего плана так и не установлен. Его
CodingScreen.kt, AgentMessageStatusTest.kt, PlanningExecutionService.kt и тест
не редактировались. Во время работы наблюдались новые чужие изменения экрана.
В совместных Dependencies сохранены изменения предшественника; добавлены только
параметр источника, создание runtime и передача источника в desktop DI.
LocalSkillsPlugin и LocalSkillRepository расширены поверх реализации хранилища.
Статус завершения соседнего плана этим отчётом не утверждается.

## Файлы этого этапа

1. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/SkillInstructionRuntime.kt`
2. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/MagicAgent.kt`
3. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/di/Dependencies.kt`
4. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/di/Dependencies.jvm.kt`
5. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepository.kt`
6. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/plugins/builtin/LocalSkillsPlugin.kt`
7. `shared/src/commonTest/kotlin/io/aequicor/magicpaper/domain/SkillInstructionRuntimeTest.kt`
8. `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/SkillRuntimeIntegrationTest.kt`
9. `docs/SKILLS-RUNTIME.md`
10. `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/ui/screens/ProjectsPanelCollapseTest.kt`
11. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/CodingScreen.kt`

## Повторная проверка панели проектов — обнаружен незакрытый сценарий

Поручение `mtrdicjq-51edab74-turn-1-action-1`, 2026-09-07.
Этот результат не заменяется прежним успешным прогоном 425 тестов:
новая проверка сворачивания всего списка выявила сбой.

### Доступность контекста соседнего плана и пересечения

Список инструментов не содержит чтения контекста/файлов плана MagicPaper.
Доступные MCP-ресурсы и шаблоны не предоставили такого контекста. Поиск в
документах проекта, CHECKPOINT.md и локальных служебных каталогах не обнаружил
фактического списка `mtr6v7jh-7ffa5976`. Пользователь повторно не опрашивался;
за пределами рабочей папки плановые данные не читались.

**Файлы, участки и завершение именно соседнего плана — не проверено.**
Нельзя приписывать ему весь git diff. Наблюдаемые факты:

| Область | Фактическое состояние |
| --- | --- |
| CodingScreen.kt | Текущий незакоммиченный diff касается ToolStepRow: раскрытие до вывода, стрелка, подпись выполнения. ProjectsPanel в этом diff отсутствует. |
| Коммит 102a2d3 | Меняет CodingScreen.kt и AgentMessageStatusTest.kt для статуса/рассуждений. Связь коммита с ID соседнего плана не установлена. |
| Runtime против прежнего прогона | SHA-256 всех shared/src совпадают с сохранённым снимком предыдущего завершённого прогона, кроме нового ProjectsPanelCollapseTest.kt. Это проверка байтов, не утверждение о завершении соседнего плана. |
| Общие с хранилищем файлы | Оба Dependencies, LocalSkillRepository, LocalSkillsPlugin; текущие изменения runtime в них сохранены, в этом продолжении они не редактировались. |
| Состав результата | Предыдущий JSON и список выше содержат 9 файлов, а не 10. Новая проверка ProjectsPanelCollapseTest.kt становится десятым файлом совокупного результата. |

### Новый сценарий

Добавлен `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/ui/screens/ProjectsPanelCollapseTest.kt`.
Это настоящий Compose ProjectsPanel с pointer events и проверкой ключей видимых
строк LazyColumn; данные CodingUi и callbacks управляются фикстурой. Это не
полный click-through приложения с настоящим ViewModel/диском.

- **PASS** `childCollapseSurvivesUpdatesSelectionAndProjectRoundTrip`: дочерний
  этап скрывается, сворачивание не выбирает родительскую сессию, обновление
  running не раскрывает этап, обычная сессия выбирается, переход A → B скрывает
  сессии A и показывает B, возврат к A сохраняет сворачивание, повторное раскрытие
  показывает этап и позволяет его выбрать.
- **FAIL** `selectedProjectHeaderCollapsesAndReopensItsSessionList`: callback
  выбора проекта вызван ровно один раз, но session-parent остаётся видимым.
  Ошибка: `Repeated click on current project must collapse its session list`.
  Последующие утверждения о сохранении выбора и повторном раскрытии не достигнуты.

Причина в прочитанном коде: ProjectsPanel вычисляет `expanded` только как
`project.id == ui.current?.id`; ProjectRow вызывает onSelectProject. Настоящий
`MagicPaperViewModel.selectCodingProject` → `openCodingProject` снова устанавливает
тот же current, а независимого состояния сворачивания проекта нет. Фикстура
сохраняет именно этот контракт выбора. Это конкретный пробел сценария, а не
доказательство регрессии от навыков или от соседнего плана.

Снимки [до клика](../shared/build/reports/project-collapse/project-before-click.png)
и [после клика](../shared/build/reports/project-collapse/project-after-click.png)
просмотрены: список остаётся раскрытым. Координата клика проверена счётчиком
вызовов обработчика; причина прежнего AgentMessageStatusTest здесь не повторяется.

### Команда и результат

`./gradlew --no-daemon --init-script shared/build/skills-runtime-followup.init.gradle :shared:jvmTest --tests '*ProjectsPanelCollapseTest' --tests '*SkillInstructionRuntimeTest' --tests 'io.aequicor.magicpaper.data.skills.*' :desktopApp:compileKotlin`

**BUILD FAILED in 11s**: 61 тест, 60 успешных, 1 failure, 0 errors.
Все **59 проверок навыков** успешны: 48 прежних, 10 runtime, 1 интеграционная.
Desktop compileKotlin — UP-TO-DATE; компиляция нового теста успешна.
SHA-256 shared/src до и после прогона совпадают. `git diff --check` — PASS.
Первый запуск был заблокирован песочницей на локальном сокете Gradle;
повтор выполнен после штатного подтверждения, с обычным кешем.

[HTML](../shared/build/reports/tests/skills-runtime-followup/index.html),
[XML панели проектов](../shared/build/test-results/skills-runtime-followup/TEST-io.aequicor.magicpaper.ui.screens.ProjectsPanelCollapseTest.xml).
Init-script в build/ меняет только каталоги отчётов, чтобы сохранить прежние результаты.

**Полный сценарий сворачивания сессий проекта не принят.** Новая регрессионная
проверка намеренно остаётся красной до исправления/согласования поведения.
Планировщику: согласовать исправление ProjectsPanel с владельцем соседнего плана
и передать этот результат в ms_acceptance. Производственные UI/DI в этом
продолжении не менялись. Изменены только этот отчёт и новый тест.

## Исправление ProjectsPanel и заключительная проверка

Основание: `mtri7uzu-51618c94-answer-ms_runtime` («go ahead») и
`mtrdicjq-51edab74-turn-3-action-0`: этот исполнитель назначен единственным
исполнителем исправления здесь. Переданное координатором состояние соседнего
плана — STOP/IDLE/DRAFT, этапы не начаты, отчётов нет. Это не подтверждает
происхождение наблюдаемых чужих правок или отсутствие пересечений.

Перед изменением повторно прочитаны CodingScreen.kt и его diff: исправления
ProjectsPanel ещё не было, чужой diff относился к ToolStepRow. Добавлено отдельное
состояние `projectCollapsed`, сохраняемое при рекомпозиции и обновлении статуса.
Повторный клик по текущему проекту переключает его список без вызова
onSelectProject: проект не перезагружается, активная сессия не сбрасывается.
Выбранность проекта отделена от раскрытия, подсветка остаётся при сворачивании.
Выбор другого проекта открывает его список; раскрытие дочерних этапов продолжает
управляться прежней картой состояния. Состояние раскрытия живёт в композиции
панели; его сохранение после перезапуска приложения не добавлялось.

Красный тест сохранён и теперь проходит. Проверка числа onSelectProject изменена
с 1 на 0: это новый контракт сворачивания без перезагрузки. Проверка исчезновения
session-parent не ослаблена. Дополнительно проверены обновление running при
свёрнутом проекте, повторное раскрытие с прежней активной сессией и A → B → A
после сворачивания. Оба сценария дочерних этапов и проекта успешны.

### Фактические пересечения и сохранность

В исправлении изменены ровно три файла:

- CodingScreen.kt — только ProjectsPanel около строки 473;
- ProjectsPanelCollapseTest.kt — уточнён контракт отсутствия перезагрузки и
  расширены проверки статуса/повторного раскрытия/перехода между проектами;
- docs/SKILLS-RUNTIME.md — актуальный результат и доказательства.

Совокупный список runtime теперь содержит 11 файлов, перечисленных выше.
С 11 файлами этапа хранения пересекаются оба Dependencies, LocalSkillRepository
и LocalSkillsPlugin; в этом исправлении они не менялись. Остальные семь файлов
хранилища также не менялись. CodingScreen.kt не входит в список хранилища,
но содержит чужую правку ToolStepRow: сравнение байтов от начала ToolStepRow
до конца файла с копией до исправления — PASS. Вся новая производственная
правка ограничена ProjectsPanel, участки ToolStepRow сохранены.

Также наблюдаются чужие изменения PlanningChatService.kt,
PlanningExecutionService.kt и их тестов, AgentMessageStatusTest.kt; этот
исполнитель их не редактировал. Их авторство и принадлежность плану остаются
неустановленными. Статус координатора не используется как доказательство
отсутствия этих изменений.

### Проверка и снимок исходников

Команда:

`./gradlew --no-daemon --init-script shared/build/runtime-collapse-fix/reports.init.gradle :shared:jvmTest --tests '*ProjectsPanelCollapseTest' --tests '*AgentMessageStatusTest' --tests '*SkillInstructionRuntimeTest' --tests 'io.aequicor.magicpaper.data.skills.*' :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 29s**. XML: 11 suites, **63 tests, 0 failures/errors/skipped**.
Из них 59 проверок навыков, 2 ProjectsPanelCollapseTest, 2 AgentMessageStatusTest.
Desktop compileKotlin выполнена успешно. `git diff --check` и проверка ссылок
документа — PASS. Полный JVM-набор после исправления не запускался; исторические
425 тестов выше не выдаются за текущий полный прогон.

Первый запуск был заблокирован на локальном сокете Gradle; повтор выполнен
через штатное подтверждение. Использованы установленный toolchain и обычный
кеш Gradle, без `--offline`. Init-script меняет только пути отчётов.

[Снимок SHA-256 всех shared/src](../shared/build/runtime-collapse-fix/tested.json)
снят непосредственно перед успешной проверкой; после неё и после возобновления
прерванного turn все пути и хеши совпали. SHA-256 самого файла снимка:
`891a956f281f93d7d0312aef51ba1a74cf5b2f9e010d1e2d906b619b4fae924c`.
[Снимок до правки](../shared/build/runtime-collapse-fix/before.json) и
[копия CodingScreen до правки](../shared/build/runtime-collapse-fix/CodingScreen.before.kt)
сохранены как build-артефакты. Последующих изменений shared/src до завершения
проверки не обнаружено; после будущих чужих правок потребуется новая сверка.

[HTML](../shared/build/reports/tests/runtime-collapse-fix/index.html),
[XML ProjectsPanel](../shared/build/test-results/runtime-collapse-fix/TEST-io.aequicor.magicpaper.ui.screens.ProjectsPanelCollapseTest.xml).
Просмотрены новые снимки [свёрнутого проекта](../shared/build/reports/project-collapse/project-after-click.png)
и [повторного раскрытия](../shared/build/reports/project-collapse/project-reopened.png):
сессии скрыты после клика и восстановлены с выбранной Plan после раскрытия.
Изображения project-collapse перегенерированы успешным прогоном; прежние ссылки
диагностики в этом документе теперь ведут на актуальные изображения.

Проверка панели использует настоящий Compose и pointer events с управляемым
CodingUi. Полный UI-сценарий с реальным ViewModel, диском и внешним LLM не
автоматизирован. Ограничения текстового runtime и отключённого исполнения
пакетного кода сохранены. Результат и пересечения готовы для `ms_acceptance`.

### Повтор после последующих чужих изменений: окончательный результат

Во время подготовки отчёта проверка хешей обнаружила новые чужие изменения:
PlanningChatService.kt, CodexAppServerOpenAiSubscription.kt и их тесты,
новые PlanningReplyPreview.kt и PlanningResponseTimeoutTest.kt. Адресный набор
был повторён с `--tests '*PlanningChatServiceTest' --tests '*PlanningResponseTimeoutTest'`:
**93 теста без ошибок, BUILD SUCCESSFUL in 29s**. Во время этого повтора
дополнительно изменились CodexCodingPermissions.kt, его тест,
PlanningReplyPreviewTest.kt и ранее перечисленные тесты/адаптер. Исполнитель
не редактировал эти файлы; прежние снимки больше не описывают всё текущее дерево.

Поэтому выполнен полный прогон на новом снимке:

`./gradlew --no-daemon --init-script shared/build/runtime-collapse-fix/reports.init.gradle :shared:jvmTest :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 37s: 79 suites, 442 tests, 0 failures/errors/skipped.**
Включены все сценарии ProjectsPanel, статуса сообщений, управления навыками,
runtime, планирования и разрешений Codex. Desktop compileKotlin — UP-TO-DATE
после успешной компиляции предыдущего адресного прогона. Это не чистая пересборка.
Условные внешние интеграции без флагов могут возвращаться досрочно, поэтому
число тестов не доказывает работу живых провайдеров и процессов.

[Окончательный снимок shared/src](../shared/build/runtime-collapse-fix/tested-latest.json),
SHA-256 файла:
`acdbe7ec2641af426c794ff4cdcb81a17210fd397025c0043c0c0dff5909336d`.
После полного прогона пути и SHA-256 всех shared/src совпали со снимком.
Новые изменения ProjectsPanel и сохранность ToolStepRow подтверждены.
Отчёты runtime-collapse-fix выше теперь содержат именно полный прогон 442 тестов.
`git diff --check` и проверка ссылок — PASS.

В этом возобновлении производственный код дополнительно не менялся; завершено
документирование уже внесённого исправления и повторная проверка после чужих
правок. Фактический состав исправления остаётся из трёх файлов: CodingScreen.kt,
ProjectsPanelCollapseTest.kt, docs/SKILLS-RUNTIME.md. Совокупный состав этапа —
11 файлов. Происхождение чужих правок по-прежнему не приписывается соседнему плану.

## Прямые доказательства критериев runtime

Этот раздел отвечает на замечание о недостаточности общего числа успешных тестов.
Приведены конкретные входы и проверенные результаты последнего прогона.

| Критерий | Сценарий и наблюдаемый результат |
| --- | --- |
| Применение с видимыми версией и разрешениями | `SkillRuntimeIntegrationTest.activeBytesReachChatButFailuresCancellationAndAttacksLeaveRepositoryIntact`: настоящий LocalSkillRepository устанавливает пакет, оставляет его в карантине (active пуст), затем проходит review/активацию. MagicAgent получает задачу `@skill:local.summary найди секрет проекта`. Шлюз получает точный текст инструкции активной 2.0.0. Ответ содержит 2.0.0, READ_PROJECT, NETWORK и отсутствие предоставленного доступа. Ответ отрисован настоящим ChatMarkdown; исполнитель просмотрел снимок и подтвердил видимость этих строк. |
| Пакет не расширяет доступ | `injectionCannotSelectDestinationOrDispatchModelCommands`: инструкция требует читать ~/.ssh/id_rsa, запускать install.sh, отправить данные на attacker.example и предоставить RUN_PROCESS. Шлюз возвращает JSON с tool=exec. Проверены неизменность выбранного профиля и ровно один вызов complete; tool JSON остаётся текстом. В интеграционном тесте импортированный install.sh не попадает в LLM, абсолютный файл-маркер не появляется. |
| Принудительные ограничения вне LLM | `attachmentsAndUnverifiedTransportAreBlocked`: вложение .env и профиль OPENAI_SUBSCRIPTION дают конкретные причины блокировки; счётчик complete равен 0. `explicitSelectionRequiresAnActiveRelease`: неизвестный ID не вызывает модель. `followUpCannotMovePackageOutputToToolTransportEvenAfterDeactivation`: после деактивации продолжение с историей пакета на subscription заблокировано без нового вызова. Это проверки до шлюза, не отказ модели по system prompt. |
| Превышение лимитов | `limitsRejectBeforeGatewayWithoutTruncating`: 7 сообщений, инструкция из 24 000 символов плюс служебный контекст и задача из 24 000 символов отвергаются; complete=0. `appliesVersionAndPermissionsWithoutSystemPrivilege`: 6 коротких сообщений принимаются, complete=1. `providerFailureDoesNotLeakExceptionAndOutputIsBounded`: ответ длиной 24 001 отвергнут с причиной «Ответ превышает». |
| Отмена и время ожидания | `cancellationPropagatesAndTimeoutHasVisibleReason`: ожидающий шлюз отменён через cancelAndJoin, task.isCancelled=true; задержка 60 001 мс завершается сообщением о пределе 60 секунд. Время в этом тесте виртуальное. Интеграционный тест отдельно подтверждает task.isCancelled и неизменность всего snapshot и SHA-256 каждого файла репозитория после отмены. |
| Секреты в передаваемом тексте | `knownSecretsInEveryInputAreRejectedBeforeSend`: token=topsecret, Bearer abcdefgh, заголовок PRIVATE KEY и точное значение ключа профиля последовательно помещены в задачу, историю и инструкцию. Все 12 сочетаний заблокированы с сообщением о возможном секрете; complete=0. Это проверка известных шаблонов, не гарантия распознавания всех секретов. |
| Попытка чтения локального секрета | Интеграционный тест создаёт реальный private.env с PRIVATE_CONTENT_NOT_FOR_MODEL. Инструкция и tool-shaped ответ требуют его прочитать. В сообщениях LLM содержимого нет, файл неизменён, дополнительных операций по ответу не выполнено; поиск и документация заданы портами, которые завершили бы тест ошибкой при вызове. |
| Попытка отправки на посторонний адрес | Инструкция требует отправить файл на https://attacker.example/upload. Интеграционный шлюз проверяет profile.baseUrl=https://chosen.example/v1, а после атакующего ответа calls=1. Runtime не имеет HTTP-диспетчера/tool loop, адрес из текста не используется как назначение. Проверка выполнена с тестовым LLM-шлюзом: живой сетевой перехват не заявляется. |
| Отказ LLM не повреждает состояние | Шлюз возвращает обычный текст «Отказываюсь выполнять операцию.»: он показан в результате, весь snapshot и карта SHA-256 каждого файла репозитория равны исходным. Та же побайтовая проверка проходит при exception провайдера и отмене. После повторного открытия активна прежняя 2.0.0 с той же инструкцией. Отдельно проверено, что exception с ключом профиля не раскрывает этот ключ в сообщении пользователю. |

### Где проходит граница безопасности

SkillInstructionRuntime имеет только `SkillInstructionSource` (чтение активных
проверенных инструкций) и `LlmGateway.complete` (один текстовый запрос выбранному
профилю). В этом маршруте нет process/file/network-tool портов и интерпретатора
ответов. Проверки профиля, вложений, размеров и признаков секретов выполняет
код приложения **до complete**, что подтверждено нулевыми счётчиками выше.
Декларации разрешений из пакета не меняют этот набор возможностей.
Роль USER для пакета и текст system prompt не выдаются за механизм изоляции.
OS-изоляция и ресурсные ограничения исполняемых пакетов не подтверждены,
поэтому их код отключён целиком. Испытание работающего OS sandbox не заявляется.

### Артефакты и воспроизводимость

- [Снимок применения в ChatMarkdown](../shared/build/reports/skill-runtime-criteria/application.png): видны Summary, 2.0.0, NETWORK/READ_PROJECT и «доступ … не предоставлен».
- [Точный текст ответа](../shared/build/reports/skill-runtime-criteria/application.txt).
- [Файл результатов интеграционных утверждений](../shared/build/reports/skill-runtime-criteria/evidence.txt).
- [XML десяти проверок runtime](../shared/build/test-results/runtime-criteria/TEST-io.aequicor.magicpaper.domain.SkillInstructionRuntimeTest.xml).
- [XML интеграционного сценария](../shared/build/test-results/runtime-criteria/TEST-io.aequicor.magicpaper.data.skills.SkillRuntimeIntegrationTest.xml).
- [HTML всего адресного прогона](../shared/build/reports/tests/runtime-criteria/index.html).
- [Снимок исходников перед прогоном](../shared/build/runtime-criteria/snapshot.json).

Команда:

`./gradlew --no-daemon --init-script shared/build/runtime-criteria/reports.init.gradle :shared:jvmTest --tests '*SkillInstructionRuntimeTest' --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanelCollapseTest' :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 14s**: 10 suites, 61 тест, 0 failures/errors/skipped.
Среди них 10 проверок runtime, усиленный интеграционный тест, 48 проверок навыков
предшественников и 2 ProjectsPanelCollapseTest. Desktop compileKotlin — UP-TO-DATE.
SHA-256 всех shared/src до/после совпали. `git diff --check` — PASS.
Gradle использовал обычный кеш после штатного расширения разрешений на локальный
сокет. Сеть к живому LLM не использовалась, внешних публикаций нет.

Изменены в этом проходе только SkillRuntimeIntegrationTest.kt и этот документ.
Совокупный список этапа остаётся из 11 файлов. Производственный код и чужие
изменения не редактировались.
