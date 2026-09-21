# Реализация целевой архитектуры

Рабочий журнал реализации [TARGET-ARCHITECTURE.md](TARGET-ARCHITECTURE.md).
Это последовательность проверяемых изменений, а не свидетельство завершённой миграции.
Существовавшие при старте пользовательские изменения сохраняются.

## Порядок работы

1. Зафиксировать исходные отказы; ввести проверки зависимостей четырёх групп и JVM-only
   границы. Перевести правила режимов в `InteractionModeProfile`, сохранив золотую матрицу.
2. Выделить существующие нативные протоколы и адаптеры за `backend-agents:api` и
   `backend-agents:factory`; описать способности вместо выбора по идентичности.
3. Перенести словарь инструментов в `magic-common`, владельца исполнения в `magic-agent`.
   Разделить чат и нативные API, настройки, оболочку и платформенное связывание.
4. Удалить изменяемый `ToolHost`: конструкторные порты по владельцам, явные зависимости,
   неизменные проверки полномочий, квитанций, неизвестного исхода и отмены.
5. Выделить общие инструменты, опросники, исследование и provider tool loop для чата.
   Нативное обогащение запроса остаётся в `magic-agent`.
6. Перевести владельцев состояния на API-машины с Intent/Fact, чистыми переходами,
   журналом входов, восстановлением и явным неизвестным исходом. Проверять каждый
   законченный перенос отдельно; не вводить неиспользуемые машины.
7. Обновить карту владельцев, выполнить JVM-проверки всех модулей, проверки границ
   и `compileMigrationTargets`; сопоставить отказы с исходным прогоном и разделом долгов.

## Установленные факты

- Исходный `./gradlew jvmTest --continue` выполнен 20 сентября 2026 года.
  Отказали ровно три сценария, перечисленные в `STUDIO-ARCHITECTURE.md`:
  `SessionAutoArchiveServiceTest.chatsUseTwoDaysSkipPendingWorkAndPersistRestoreWithoutSending`,
  `RequestPinViewModelTest.chatAnswerUsesOverrideButPinsUseOperationalDefaultAndOldChatsAreLazy`,
  `PaperTreeGroupHeaderTest.narrowHeaderRetainsDisclosureStatusAndFullTitleAtEveryTextScale`.
  Число тестов из инкрементального прогона не заменяет число полного прогона в документе долгов.
- В исходниках существуют два движка: `PI` и `CODEX`. Упомянутые в целевом документе
  пять движковых модулей не сопровождаются именами трёх дополнительных адаптеров.
  Выделяются действующие реализации; пустые движки ради числа модулей не создаются.
- HTTP-чат наследовал `CodingRuntime` через `NoopCodingRuntime`, а desktop-чат получал
  инструменты через нативный runtime. Одного переименования каталогов недостаточно:
  необходимо сохранить функции чата, выделив общий provider tool loop.

## Завершённый первый срез

- Правила режимов — таблица `InteractionModeProfile`; прежняя золотая матрица
  сохранена, дополнительно проверено полное произведение требований инструмента и контекста.
- Реальные проекты `:magic-common:tools:api/impl`, `:magic-common:research`,
  `:magic-chat:impl`, `:magic-agent:runtime:impl`, `:backend-agents:api/factory/pi/codex`.
  Пути каталогов соответствуют проектам; Kotlin-пакеты и `coding/*` classpath-ресурсы сохранены.
- `ToolHost` удалён. Сборка получает неизменяемые порты владельцев через конструкторы;
  отдельный тест запускает настоящий граф и проверяет отсутствие работы при восстановлении.
- `GatewaySessionRuntime` реализует `ChatBackend` непосредственно. В обычном чате
  убран выбор нативного движка; композер агента сохранил его. HTTP routing на desktop пока
  не включён: перед переключением нужен общий цикл инструментов.
- Нативные протоколы конфигурации/событий Pi и политики/результатов Codex вместе с
  запуском процессов вынесены за backend API/factory. Остальной native lifecycle и
  обогащение запроса пока разделяются в следующем срезе.
- Верификатор проверяет направления групп, платформенные границы, единственного
  потребителя backend, отсутствие Koin вне app, повторного объявления ToolHost и
  функциональных полей эффектов, в том числе в соседнем файле той же иерархии.

Проверки: 11 тестов общего исследования, 9 тестов инструментов, 48 тестов Pi,
8 тестов Codex, 137 целевых тестов удаления ToolHost; сборка research/tools для JS и Wasm.
Paper verifier и карта 322 UI-объявлений проходят. Осмотрены renders композера
при ширине 720/390 и масштабе текста 1/2.

Полный JVM checkpoint прошёл компиляцию всех владельцев. Из 1255 тестов runtime
упал только сценарий галереи, искавший удалённое пустое меню; исправление проверено
шестью focused render tests. Сохранились три исходных отказа. Ещё раз проявилась
ранее записанная нестабильность `PaperSemanticsTest` (ожидание кадра); отдельный
повтор класса прошёл. Требуется новый полный прогон после следующего среза:
focused проверки не подменяют полный отчёт.

## Завершённые части второго среза

- Нативные страницы настроек через immutable contributions из JVM root; общий
  `ModelDossierRepository` вместо зависимости SettingsService на PlanningRepository.
- Общий сервис опросников: одна API-машина Intent/Fact, журнал с очищенными ответами,
  импорт старых snapshots, неизвестный исход записи/доставки и запрет повторной отправки
  после сбоя. Реальные секреты остаются только в памяти текущего исполнения.
- `AgentRunResources` владеет bridge/browser/research/computer/skill ресурсами;
  backend получает подготовленные значения и владеет процессом/протоколом.
- `:magic-chat:api`, JVM-only `:magic-agent:runtime:api`, общие media/request-pins API/impl
  и transcript физически разделены. `RuntimeStatus` и исполняемые контракты недоступны
  общим платформам, `UnavailableCoding` удалён, test-only runtime находится в testSupport.
- Общая оболочка получает immutable routes/dialogs/sidebar/settings contributions от
  JVM root. App lifecycle работает с владельцами расширений, не называя coding-сервис.
  Layout workflow подключается JVM-портом с захватом проекта до запуска coroutine.
- Codex RPC/процесс/протокол и Pi installation перенесены в backend-группу. Приложение
  вызывает одну runtime-фабрику; dispatch выбирает зарегистрированную стратегию по
  сохранённому ключу, поведение задаётся capabilities. Полная автоматическая регистрация
  нового адаптера без правок runtime ещё не завершена.
- Общий provider loop с API-машиной и журналом использует реальный tools executor.
  Три HTTP-протокола поддерживают последовательные и параллельные model tool calls,
  provider continuation, подписанные/thinking блоки и учёт каждого хода.
  Опросники чата имеют общий UI и отправляются только явным действием пользователя.
- Восстановление чата не запускает старый запрос. Явное прекращение незавершённого
  запроса сохраняет историю и частичный ответ, не снимая журнал неизвестных эффектов.
- Закрепления запросов переведены на `RequestPinMachine` и журнал входов. Проверены
  точное подтверждение потерянного append, неизвестный исход, поздний ответ после
  редактирования/удаления и очистка при незавершённой записи. Данные подключения
  не попадают в журнал, ошибки доступны UI, файловая работа вынесена с UI-dispatcher.
- Планировщик больше не принимает чужие потоки общего журнала за планы. Проверка
  восстанавливает план без checkpoint, сохраняет чужие записи при очистке и по-прежнему
  отказывает на повреждённой записи собственного формата.
- Готовый ответ provider сохраняется в неизменяемый artifact до успешного journal fact.
  Проверены потерянное подтверждение, повреждённый artifact и чужая попытка. Восстановление
  самого provider loop не вызывает модель повторно; read-only передача ответа в историю
  после перезапуска приложения входит в следующий срез ChatMachine.
- Подписочный транспорт выполняет один model tool turn через закреплённую pi-ai 0.84.4,
  без agent runtime и исполнения инструментов. Проверены реальные экспорты этой версии,
  provider continuation с synthetic SSE, отмена процесса и отсутствие секретов в диагностике.
  Desktop-чат переключён на общий `GatewaySessionRuntime`, native feature больше не
  подменяет `ChatBackend`. 36 app-тестов и 5 media recovery integration tests проходят.
- `:magic-common:prompts` собирает общие инструкции, навыки и контекст; native adapter
  добавляет инструкции backend, browser, computer и worktree. Исходный golden сохранён
  до переноса: совпадают все 36 комбинаций engine/mode/speed/browser.
- Медиа использует API-машину и журнал Intent/Fact. Повторно проверяется существующее
  provider job, неизвестная отправка не повторяется; inline bytes и prompt не попадают
  в журнал. Проверены принадлежность stream/operation, повреждённая последовательность,
  потерянное подтверждение append, отмена, legacy import и восстановление.

Проверки этого среза: coreAI 105 тестов и Android/JS compile; common provider machine6
и impl21; focused runtime executor146; app focused42; shared media15; integration runtime19;
app lifecycle5; questionnaire API4/impl21. Счёт отражает отдельные прогоны, не суммируется
в полный suite. Рендеры нативных настроек и общей формы опросника осмотрены на 390px/2x.

Дополнительные проверки: provider API6/impl26/chat13, pins23, prompts7/native11,
media API7/impl25 и Android/JS/Wasm compile; subscription backend7/runtime3.
Это отдельные прогоны; общий checkpoint ниже не заменяется их суммой.

## Checkpoint второго среза

`compileMigrationTargets` проходит: Desktop, Android, JS и Wasm собраны. Android/web
hosts получили прямую зависимость Foundation, которой пользовались через удалённое
транзитивное ребро. Полный JVM-прогон: 2570 тестов, 13 пропущено, два исходных отказа
RequestPin/TreeHeader и ранее известная нестабильность PaperSemantics. Исходный
SessionAutoArchive теперь проходит. Отдельно проходят Android host-tests, desktop host
и nodeProtocolTest; live provider/OS-интеграции не включались.

Обнаруженная при полном прогоне регрессия Pi flow устранена через channelFlow/send на
границе native callback. Проверки точного skill payload сохранены и дополнены проверкой
реального ответа и завершения. Dossier corruption теперь явно проверяется как ошибка
с сохранением исходных байтов. Зависание SidebarRestorationTest диагностировано дампом
потоков; lifecycle headless сцен перенесён на AWT, assertions не ослаблены.

## Checkpoint третьего среза

Общий JVM-прогон после ChatMachine, computer/browser и native catalog: 2674 теста,
13 пропущено. Browser6/19, computer11/74, chat16/170, runtime1183 и skills147 проходят.
`compileMigrationTargets` (Desktop/Android/JS/Wasm), Android/desktop host-tests, Pi Node
и computer screenshot Node проходят. Помимо исходных RequestPin/TreeHeader выявлено
ожидание асинхронной записи в KoinOwnerResolutionTest; focused исправленная проверка
проходит с точным session ID и сохранёнными assertions изоляции. Полный повтор app
после неё содержит 213 тестов и только исходный отказ RequestPin; KoinOwnerResolution проходит. Общие результаты сохранены в
`/tmp/magicpaper-target-third-slice-results/summary.json`; исходные XML архивированы.
Live provider/OS-проверки не включались, отдельные browser tests JS/Wasm не запускались.

## Текущая работа

- Полноценная ChatMachine: атомарное владение notebook/questions, принятие запросов,
  очередь, история, journal/recovery и immutable output handoff без повторного provider call.
- Unified backend contribution и автоматическое обнаружение адаптеров; затем API-машина
  native lifecycle и журнал исходов процесса.
- Владельцы computer и browser выделены в JVM-only API/impl. В браузере принятая
  операция проходит `BrowserMachine` и `BrowserInputJournal`; OS-доступ компьютера —
  `ComputerMachine`/`ComputerAuthority`. Проверены отсутствие повторных действий после
  восстановления и lost ACK, неизвестный исход после выполнения без подтверждения,
  преждевременный/неподтверждённый close и fencing старых handles после reset.
  Browser API6/impl19 (3 native opt-in skip) проходят; computer API11/impl73
  (6 native opt-in skip) прошли дополнительный review ошибок очистки и журнала.
  Оба модуля и их consumers прошли общий checkpoint третьего среза.
- Верификатор теперь учитывает автоматические factory edges при поиске циклов,
  требует service registrations у backend-модулей и запрещает выбор поведения/перечня
  движков по `CodingEngine` вне его владельца. Catalog migration убрала оставшиеся
  enum enumeration; проверки модулей, Paper API и карта 339 UI bindings проходят.
- После новых API/модульных переносов повторить полный JVM и `compileMigrationTargets`.

## ChatMachine: приёмка текущего среза

`ChatMachine` в `:magic-chat:api` владеет notebook/questions, историей, очередью,
остановкой, настройкой модели и источниками. `ChatJournalStore` применяет сериализованные
Intent/Fact; `ChatRepository` доступен потребителям только для чтения. Старые файлы
`chat:<id>` и индекс `chats` сохранены как импортируемый, восстанавливаемый кэш.
Настройки вызывают `ChatHistoryCommands`, обхода через `updateChat`/`save` нет.
Содержимое ввода и ответа находится в `StoredChatPayloads`; журнал хранит ссылку,
тип, digest и поколение reset. Потерянное подтверждение принимается только для
точного append после неизменившейся истории. Повреждение или чужая принадлежность
не заменяются пустой историей.

Незавершённый старый checkpoint, включая `STOP`, восстанавливается как неизвестный
исход. Никакие модель, поиск, инструменты и анкеты при восстановлении не запускаются.
Явная проверка сохранённого ответа использует `ProviderToolLoop.inspect` и исходный
run ID. Машина принимает результат только для текущего точного RunRef. Сохранённый
`ResponseContextPrepared` сохраняет проверку источников: одинаковая чистая сборка
ответа применяется в live/recovery, включая недоступные ссылки, заметку, источники,
вложения и media blocks. Без старого доказательства проверки источников восстановление
не выдаёт сырой текст за подтверждённый ответ. Уточнение сохраняется до остановки;
при UNKNOWN остаётся в очереди, подтверждённая остановка допускает новый запуск.

Проверки: `ChatMachineTest` — 16 тестов, включая таблицу 9 состояний × 8 входов;
app focused — 51 тест (`ChatInputQueueTest`, `ChatServiceLifecycleTest`, `ChatResearchTest`,
`ConversationHistoryTest`, `ChatLayoutWorkflowTest`, `SessionAutoArchiveServiceTest`,
`ModelSettingsServiceTest`); settings — 9 (`MediaSettingsPersistenceTest`,
`ComputerSettingsPersistenceTest`). Лог: `/tmp/magicpaper-chat-final-owner.log`.
`ChatSavedResponseIntegrationTest` — 2 сценария с настоящим provider loop/output store:
потерянная запись ответа чата, перезапуск, смена модели и восстановление без повторного
вызова; второй сценарий доказывает, что недоступная ссылка остаётся отклонённой.
`ChatJournalStoreTest` отдельно проверяет exact lost ACK, префикс журнала, чужой stream,
sequence/reset epoch, payload identity/digest, reset/cancel и глобальные ID владельцев.
`ChatStorageFailureTest` сохраняет повреждённые байты и проверяет embedded ID/legacy orphan.

Интерфейс обычного чата передаёт `ResearchResumeAction.CHECK_SAVED_RESPONSE` в общий
transcript/composer. Кнопка называется «Проверить ответ», при введённом уточнении —
«Уточнить»; native consumers сохраняют `CONTINUE`. Layout не менялся.
Preview: `ChatRecoveryComposerPreview`, группа `Chat recovery`, состояния
720×440/1x, узкое 390×560/1x и 720×700/2x. Actual consumer test:
`ChatComposerProviderRenderTest.interruptedChatShowsSavedAnswerCheckWithinNarrowAndLargeTextBounds`.
Проверены видимая подпись, accessibility action и границы кнопки; рендеры осмотрены:

- `magic-chat/impl/build/reports/chat-provider-composer/recovery-390-1.0.png`;
- `magic-chat/impl/build/reports/chat-provider-composer/recovery-720-2.0.png`.

Воспроизведение: открыть обычный чат с незавершённым запросом после перезапуска,
оставить composer пустым, проверить действие в composer и панели исследования;
ввести уточнение и убедиться, что действие изменилось на «Уточнить». Проверки рендера
и восстановления: `:magic-chat:impl:jvmTest --tests '*ChatComposerProviderRenderTest'
--tests '*ChatSavedResponseIntegrationTest'`; лог `/tmp/magicpaper-chat-final-render.log`.
Paper API self-test и surface-map self-test проходят. Это headless JVM-проверки;
установленные Android/web/macOS/Windows этим срезом отдельно не запускались.
Общий JVM/платформенный checkpoint после финального identity review ещё обязателен.

## Четвёртый срез — в работе

Владельцы нативного lifecycle, организма сессий и рабочих копий выделены в отдельные
API/impl. CodingMachine владеет проектом, сессиями и историей; дочерние журналы отдают
вверх точные версии проекций. Это ещё не принятый срез: интеграционные fixtures и
поведение восстановления переводятся на семантические команды, общий checkpoint впереди.

Workspace API: 8 тестов переходов, impl: 16 fault/recovery/reset-тестов проходят.
Матрицы покрывают все 15 типов Intent в 11 состояниях и 6 операций × 9 вариантов
terminal Fact, включая запрет чужого operation ID и неподтверждённого результата.
Лог последнего owner-run — `/tmp/magicpaper-workspace-reset-proof.log`;
совместная production-сборка runtime
прошла в `/tmp/magicpaper-workspace-runtime-integration.log`. Один journaled Git intent
исполняется после подтверждения записи. Приватный immutable outcome записывается после
возврата операции и её cleanup; потерянный terminal fact восстанавливается по нему без
Git-вызова. Без такого доказательства postcondition Git не снимает неизвестный исход:
процесс мог пережить приложение. Старые незавершённые Git-фазы импортируются как UNKNOWN.
Подтверждённый отказ завершившейся проверки отдельно допускает явное исправление
или `RetryVerification`, после которого снова обязательны проверка и принятие;
неизвестный исход не превращается в такой отказ. Проверено сохранение исходной отмены,
если одновременно отказывают запись и readback. Runtime передаёт ребёнку реальные
workspace/task ID отдельно от fresh native request ID. Отмена после подтверждённого
intent, но до вызова порта записывает доказательство «не запущено»; отмена самого
pause не позволяет rollback забыть ещё работающего владельца.

`GitTaskWorkspaceTest`: 28 из 29 прошли в `/tmp/magicpaper-git-workspace-fourth.log`;
единственная регрессия теряла причину BLOCKED handoff. Исправлен `Reject.message`,
повтор этого сценария прошёл в runtime owner-run. `CodingWorktreeTest`: все 19 прошли
в `/tmp/magicpaper-worktree-app-recovery.log`; управляемый fake runtime явно выдаёт
доказательства завершения и остановки. Legacy незавершённые снимки не дают ложного
COMPLETE и не запускают Git/модель. Failed release после COMPLETE повторяет только
освобождение удержания. После final native proof guard `CodingWorktreeTest` прошёл
все 20 сценариев (`/tmp/magicpaper-worktree-app-native-proof.log`): новый сценарий
восстанавливает потерянный workspace terminal fact до первого native launch и
различает durable «не запускалось» от отсутствующего доказательства. Reset отдельно
обнаруживает неоткрытые после restart журналы; неизвестный внешний исход запрещает
стирание данных. Legacy worktrees импортируются перед разрешением сброса.
`RuntimeLifecycleTest`: все 9 прошли в app focused-run;
ошибка отката одного владельца не оставляет остальных на паузе. Новая проверка
native outcome перед финальным ответом требует следующего общего checkpoint.

Приёмка CodingMachine / project journal (до общего checkpoint):

- `CodingProjectRepository` — read-only порт; проект, сессии, настройки сессии,
  история, очередь и orchestration inputs изменяются закрытыми `Intent`/`Fact` в
  runtime API. `CodingJournalStore` фиксирует вход до возврата эффектов; старые
  файлы проекта/истории служат снимками совместимости с прежними путями.
- В журнале хранится envelope приватного immutable payload. Проверяются owner/input
  identity, digest, stream, epoch, sequence, точный prefix и timestamp подтверждения.
  Потерянный ACK принимается лишь при доказательстве одного точного append; отмена
  неопределённой записи блокирует дальнейшие эффекты. Replay не исполняет эффекты;
  tombstones и глобальное резервирование IDs препятствуют воскрешению истории.
- Parent/native certainty и неизвестный исход workspace независимы. Завершение
  потока само по себе не подтверждает внешний исход. Новая проверенная child revision
  снимает только child unknown; отдельный durable `knownStopped` позволяет продолжить
  запрос, остановленный до native dispatch. Restore активного запроса такой proof
  не создаёт. Явное продолжение неизвестной native попытки сохраняет точный
  predecessor/decision/ack и требует свежий request ID.
- Уточнение сохраняется до отмены; UNKNOWN оставляет его в очереди без запуска.
  `EditRequest` атомарно заменяет точную предыдущую историю и допускает новый запрос;
  отказ не теряет прежнюю native identity. Legacy STOP также восстанавливается UNKNOWN.
- `CodingMachineTest`: 17/17; app focused: 35/35 (`CodingResumeTest`,
  `ConversationHistoryTest`, `SessionInputQueueTest`, result/archive/deletion/status,
  `AgentUiThreadTest`). Лог `/tmp/magicpaper-coding-machine-app-focused2.log`, XML
  `/tmp/magicpaper-coding-machine-app-results2`. `CodingJournalStoreTest`: 11/11,
  включая lost ACK/cancellation/prefix/reset/corrupt payload/identity/tombstones/wipe.
- Финальный runtime focused: 147/147 — `PlanningChatServiceTest` 105,
  `OrchestrationToolsTest` 32 и 10 Compose render/interaction сценариев.
  Лог `/tmp/magicpaper-coding-machine-final-focused.log`, XML
  `/tmp/magicpaper-coding-machine-final-results`. Ранее owner suites истории,
  planning execution/checkpoint/retry и input acceptance также прошли.

UI consumer acceptance использует существующие настоящие composables; layout не
изменялся. `CodingSessionUi.runPhase` сохраняет доступность восстановления даже при
сохранённых байтах ответа с недоказанным native исходом. Воспроизведение: проект →
сессия → сохранённый незавершённый запрос; после открытия внешних вызовов нет,
явная форма восстановления сохраняет введённое уточнение. Код возврата history edit
и действия восстановления покрыты app integration выше.

| Render/test symbol | Матрица | Артефакты относительно runtime impl |
| --- | --- | --- |
| `QueuedMessageCancellationRenderTest.queuedBubbleCancelsOnClickAndUpdatesEvenBeforeHistoryRefreshes` | 1000/360 px, queued → explicit cancel; соседний запрос остаётся processing | `build/reports/queued-message-cancellation/{queued,cancelled}-{1000,360}.png` |
| `OrchestrationFailureRenderTest.failedInputUsesQuestionnaireWhileAWorkerContinues` | 1000/430 px, безопасная ошибка + доступная форма; worker продолжает | `build/reports/orchestration/input-failure-{1000,430}.png` |
| `OrchestrationFailureRenderTest.unavailableVerificationOffersAutomaticCheckAndExplicitSkip` | 1000/430 px, unavailable verification, явные действия | `build/reports/orchestration/missing-verification-{1000,430}.png` |
| `PlanningChatRenderTest`, `OrchestrationRenderTest`, `PlanningProposalRenderTest` | handoff, дерево/длинный route, вопросы/граф; 280–1280 px | `build/reports/planning-chat/`, `build/reports/orchestration/` |

PNG узкого отменённого сообщения, формы ошибки и чата вопросов просмотрены после
этого прогона. Semantics проверяют видимость/доступность действий и результат клика.
Это JVM Compose-render проверка, не installed macOS/Windows проверка. Изменений layout
или новых визуальных компонентов в текущем срезе нет; large-text отдельным новым
прогоном здесь не проверялся.

Остатки этого среза: общая интеграционная приёмка после final native outcome guard,
проверка обновления поколений и проекций, независимые доказательства родительского
и дочернего исхода, lifecycle cleanup. Отдельно ещё требуется
владелец исследовательских проверок вместо ResearchCheckRunner.shared, семантика
планирования/восстановления и аудит оставшихся state owners.

### Общий checkpoint четвёртого среза: первый прогон и исправления

Полный JVM/платформенный прогон завершился: 2786 JVM-тестов, 11 отказов,
13 пропущено; runtime 1166, chat 170 и новые владельцы проходят. Помимо двух
исходных RequestPin/TreeHeader обнаружены девять новых отказов app/skills и
ошибка commonMain settings на JS/Wasm. В skills fixture отсутствовал терминальный
`agent_end`; новый lifecycle правильно отказался считать полученный текст завершением.
В app найдены устаревшие записи через cache и ожидания автоматического восстановления,
а также реальная ошибка «Оставить остановленной»: старый незавершённый вопрос без
machine run создавал новое пользовательское сообщение вместо сохранения решения.
Этот первый прогон не был принят; ниже зафиксирован повтор после исправлений.
Архив: `/tmp/magicpaper-target-fourth-slice-results/summary.json`.

После замены common accessor полный платформенный повтор прошёл:
`compileMigrationTargets` (Android, desktop, JS/Wasm linking и webpack), app Android
host-tests, desktop host и оба Node checks. Лог `/tmp/magicpaper-fourth-platform-repeat.log`.
`androidApp:testDebugUnitTest` остаётся NO-SOURCE, это не отдельное подтверждение тестов.
Повторный общий прогон завершён: **2790 JVM-тестов, 2 исходных отказа, 13 пропущено**.
Runtime 1167, skills 147, chat 170 и все новые владельцы проходят; app 222 теста,
только исходный RequestPin. Второй исходный отказ — TreeHeader. Новых падений нет.
Одновременно прошли compileMigrationTargets, app Android host-tests, desktop host,
Pi Node и computer screenshot Node. Архив: `/tmp/magicpaper-target-fourth-repeat-results/summary.json`;
лог `/tmp/magicpaper-target-fourth-repeat.log`. Финальные workspace API 8/8, impl 16/16,
app Worktree 21/21 и RuntimeLifecycle 9/9 подтверждены этим прогоном.

Пятый срез начат после этого checkpoint: typed native NoDispatch recovery,
planning owner и journal admission исследовательских/процессных проверок.
Завершение четвёртого среза не означает завершение всей целевой архитектуры.

## Приёмка

Цель остаётся незавершённой, пока runtime/API/UI агентов доступны общим платформам,
пока существует `ToolHost` с изменяемыми портами или пока восстановление владельцев
состояния не соответствует журналируемым Intent/Fact. Зелёный тест одного среза
не означает завершение всей архитектуры.

## Следующий владелец: защищённые исследовательские проверки

Read-only proposal после четвёртого среза; реализация ещё не начата.
`ResearchCheckRunner.shared` остаётся process-global владельцем. Вызовы идут из
`AgentRunResources`/`ResearchCheckBridge`, `DesktopCodingRuntime`, `GenericNativeRuntime`,
`CodexAppServerOpenAiSubscription` и `ResearchSessionIntegrationChecks`.

- Создать JVM `:magic-agent:research-checks:api/impl`. API: один `ResearchCheckMachine`,
  `ResearchChecks`, точные scope/request/call/attempt ID, результат/progress и endpoint.
  App создаёт один owner с реальным EventJournal/payload store и передаёт его через
  native factory. Runtime потребляет API; backend-agents ничего об этом owner не знает.
- Перенести восемь implementation-файлов: runner, bridge, tool schema, Pi extension,
  workspace policy, sandbox, Unix process и Windows sandbox. `ResearchSessionIntegrationChecks`
  оставить runtime-адаптером к существующему integration-check port. Общий чистый JVM
  `WindowsExecutables` требует отдельного существующего core/platform owner, чтобы не
  создать research impl → runtime impl dependency.
- Сохранить package и `~/.MagicPaper/research-checks`, `owned`, `artifacts-<projectHash>.json`,
  `run-<sessionHash>-*`, `MAGICPAPER_RESEARCH_*`, MCP `magicpaper_research` и `research_check`.
  Удалить singleton/default runner и global shutdown hook; pause/resume/shutdown принадлежат app.
- Journal admission должен предшествовать OS grant/start. Suspended process получает durable
  group/receipt до release. Отдельные факты: delivery, exit, process group stopped, ACL restored,
  artifact attested. Restore не запускает probe/command/retry. Progress и live handles являются
  проекцией/ресурсами. Bridge journal принимает exact call ID и payload digest; одинаковый ID
  с иными аргументами отклоняется. Pi extension должен сохранять native call ID внутри run scope.
- Unix уже создаёт process group/guardian, Windows — Job Object, но общий `Process` не отдаёт
  проверяемое cleanup proof. Unix игнорирует результат kill(-pgid); Windows старый ACL хранится
  в памяти. Parent PID absence не подтверждает остановку детей. Без group/ACL proof остаётся
  UNKNOWN, не выдаётся новая write authority и не продвигается artifact manifest. Corrupt artifact
  JSON больше не должен превращаться в empty map. Immutable completion/manifest payload записывается
  до terminal fact, readback проверяет identity/digest/reset epoch и точный prefix журнала.
- Перенести `ResearchWorkspacePolicyTest`, `ResearchCheckExecutableResolutionTest`,
  `ResearchSandboxNativeTest`; добавить machine table, duplicate/changed payload, lost ACK,
  corrupted manifest, cancelled start-before-release, reset, group/ACL cleanup failure и восстановление
  immutable результата без повторного запуска. OS sandbox native tests остаются opt-in для каждой ОС.
  Policy Git introspection также требует bounded read/cleanup: сейчас чтение stdout предшествует timeout.

Первый технический шаг — API и единый app instance с полной инъекцией и строгим чтением
artifact state. Такой шаг сам по себе не завершает TARGET: suspended launch, фактический journal
interpreter и group/ACL proof должны попасть в последующую приёмку до объявления owner готовым.

Оставшийся recovery-контракт четвёртого native среза: если `Begin` принят, но preflight
адаптера отказал до первого `LaunchRequested`, backend journal содержит run без попыток.
Текущий recovery snapshot отдаёт только attempts, поэтому parent видит отсутствующее
доказательство и сохраняет блокировку. Это безопасно, но не завершает recovery UX.
После checkpoint нужен typed `NoDispatch` admission proof из авторитетного backend run state;
нельзя выдумывать attempt ID или считать пустой snapshot подтверждённой остановкой.
Отдельный отказ workspace до native вызова доказывается собственным parent `RunStopped`,
а не отсутствующим native receipt.

В тот же следующий срез входит `TaskWorktreeIntegrationChecks` (runtime `data/planning`),
который передаётся в `defaultGitTaskWorkspace`. Его active-process map не является durable
владельцем: descendants перечисляются только при kill, успешный exit умершего parent обходит
cleanup детей, а reconcile лишь удаляет запись из map. Операционные ошибки превращаются
в `blockedReason`, после чего `GitTaskWorkspace.verify` может принять nullable exit/blocked
за известный `VerificationFailed` без доказательства завершения всей группы процессов.
Нужен общий app-scoped механизм process receipts, suspended admission и проверяемого
group termination для обоих владельцев. Их policy сохраняется раздельно: проверки управляемого
worktree намеренно запускаются без sandbox, исследовательские проверки защищают исходный
workspace. Отсутствие parent или записи в live map не даёт cleanup proof; unresolved cleanup
сохраняет UNKNOWN и не разрешает повторную проверку либо выдачу новой write authority.

### Следующий срез планирования: один владелец поверх существующего журнала

Read-only аудит после organism migration: `PlanningExecutionService.bootstrap` повторно
запускает сохранённые планы с `intent=RUN`; отсутствие открытого journal intent не является
новым разрешением на исполнение. `StageMachine.Inspect` выдаёт запуск для PREPARED,
EXECUTING и FAILED. `PlanningStore` уже атомарно сохраняет состояние и operational evidence,
но принимает произвольные `save/update` преобразования и имеет production default
`InMemoryEventJournal`. `PlanRunner` больше не имеет production callers (только шесть тестов).

Предлагается `:magic-agent:planning:api/impl` с одной `PlanningMachine` на `Plan`: его
attempts, coordination, proposals и execution admission остаются одним агрегатом.
Stage/Coordination/PlanOrchestration становятся внутренними чистыми правилами этой машины,
а не новыми независимо сохраняемыми владельцами. Сериализованные `Plan`/`StageAttempt`
и IDs остаются прежними. Root-session inbox `OrchestrationState` уже принадлежит
`CodingMachine`; повторно переносить его в planning не нужно. Широкий `Intent.Orchestrate`
должен последовательно уступить семантическим Intent/Fact под тем же runtime owner.

Runtime потребляет `PlanningOwner` API; app создаёт impl. Execution interpreter остаётся
в runtime и использует отдельные неизменяемые порты native, workspace, organism и coordinator.
Взаимодействие связывается с точными plan/run/admission/generation и
stage/attempt/turn identities; child projections поднимаются с journal revision proof.
Mutable `chatHooks`, `prepareAttempt`, `authorizeRetry`, `attemptCheckpoint` и
`stoppedCheckpoint` заменяются этими конструкторными портами.

Журнал сохраняет текущие `plan.id` streams, operation wires, ссылки на intent seq и читатель
`plan-commit/1`. Новые записи в том же stream содержат типизированный вход или ссылку на его
закрытый payload с digest; эффекты не записываются и при replay не исполняются. Legacy deltas
и operational evidence сначала валидируются, затем проигрываются новые inputs. CAS/readback
проверяет точный prefix, единственную добавленную запись, epoch, timestamp и identity.
Snapshot остаётся восстанавливаемой проекцией. Ошибка после dispatch внешнего действия
не доказывает REJECTED: отдельный UNKNOWN блокирует повтор даже при пустом pendingTool.

Последовательность небольших checkpoints:

1. Restore только читает, проецирует и показывает неопределённые исходы. Explicit start
   сохраняет новое разрешение; только таймеры текущего процесса могут пользоваться этим
   разрешением. Проверка restart: ноль native/Git/model вызовов.
2. Реальная PlanningMachine и owner поверх прежнего journal; миграция create/edit/start/
   pause/stop/recover, затем устранение произвольного production `update/save`.
3. Перенос stage/coordination facts и interpretation с точными run refs; удаление старых
   публичных reducers и изменяемых hooks. Dossiers остаются отдельным cache boundary.
4. Удаление неиспользуемого production PlanRunner после переноса полезных ordering/failure
   сценариев его тестов в настоящий scheduler.

Приёмка сохраняет PlanningJournalProjectionTest, PlanningStoreCacheTest, PlanJournalReaderTest,
PlanningJournalRecoveryTest и добавляет exact lost ACK/reset/corrupt payload cases. Табличные
Stage/Coordination/PlanOrchestration тесты переходят в planning API; scheduler integration
остаётся в runtime с настоящим инъецированным owner. Этот план пока не является реализацией.

Дополнительные app-регрессии четвёртого общего checkpoint закрыты отдельным прогоном:
`CodingMachineTest` 18/18 и app 12/12 (Navigation 3, Research 4, Questionnaire 4,
planning RequestPin 1), `/tmp/magicpaper-fourth-app-regressions.log`, XML
`/tmp/magicpaper-fourth-app-regressions-results`. `DeferRecovery` сохраняет выбор
«оставить остановленной» для старой истории без checkpoint, не добавляя USER-сообщение,
не запуская работу и не превращая UNKNOWN в доказанную остановку. Exact native attempt
может быть 0; отрицательный ordinal отклоняется, сохранённые номера не изменяются.
Research restore теперь проверяется через явное восстановление с typed proof и fresh
request ID; legacy STOP не разрешает менять режим. Tests delayed navigation и planning
pin classification используют настоящего journal owner вместо live cache writes.
Известный baseline RequestPin chat test в этом исправлении не менялся.

## Оставшиеся владельцы вне planning/research — read-only инвентаризация

Инвентаризация после четвёртого среза; новые API/машины ниже ещё не реализованы.
Приоритет у реальных сохраняемых решений и внешних операций, а не у DTO и query ports.

- **Настройки и источники:** `feature/settings/impl/.../ui/DefaultSettingsService.kt`
  владеет onboarding, AppSettings, профилями/default model, каталогом и dossier jobs,
  импортом и многошаговым удалением профиля. `setDefaultModel`, `updateModelLibrary`,
  `saveModelDescription` ещё запускают save без локального наблюдаемого error boundary;
  refresh результата каталога не привязан к revision профиля. Нужна одна SettingsMachine
  в settings API с exact profile/draft version, Import/Delete/Reset intent и typed completion;
  поздний ответ после edit/delete/reset не меняет новый профиль. Секреты остаются в
  SecretStore: журнал содержит только приватные ссылки. `JsonLlmProfileRepository` и
  `CredentialRecords` уже имеют commit/cleanup семантику, их не заменять отдельной
  бизнес-машиной. ModelDirectory и DossierResearcher остаются портами эффектов.
- **Общие навыки:** `SkillStore`, `JsonSkillRepository`, `SkillsRepositoryPlugin`,
  `SelfEducationPlugin` ещё допускают непосредственные save/delete из composable scope.
  `JsonSkillRepository.all()` превращает corrupt JSON в empty list, после чего save может
  перезаписать историю. Нужен application-lived Skills owner с API reducer и journal,
  exact version/install/delete/tombstones; существующие `skills` ключ и IDs сохраняются.
- **Нативные пакеты/опыт навыков:** `SkillReleaseStore` уже проверяет immutable checksum,
  generation-bound consent, dependency graph, permissions и rollback, но находится в impl,
  исполняет persist callback и восстанавливается из `LocalSkillRepository.snapshot.json`.
  `LocalSkillExperience` — ещё один настоящий authority: tickets/run generation,
  retention/deletion tombstones, оплачиваемая генерация и оценки кандидата. Это отдельные
  bounded owners для API/impl с журналом; при разделении каждый модуль имеет одну машину.
  Извлечение должно сохранить текущие проверки согласия и immutable coding-run audit.
  `DesktopSkills`, `LocalSkillsPlugin`, `ExperienceFormOwner` — текущие consumers/assembly;
  `beforeInstructions` ещё mutable callback. Не заводить машины для валидатора ZIP,
  embedded catalog, directory reader или pure skill selector.
- **Плагины:** `DefaultPluginService` уже serializes toggles и reset barrier, хранит unknown
  plugin IDs/private config, но authority — `SettingsRepository.pluginStates` snapshot.
  Этот decoder тоже делает corruption→empty. Минимальный выбор: PluginMachine для
  enable/config/lifecycle, либо передать сохранённое config-владение SettingsMachine и
  оставить PluginService проекцией/lifecycle. Не делать обе машины авторами одного ключа.
- **Расходы:** `core/ai/impl/.../domain/DefaultUsageLedger.kt` — реальный accounting owner:
  record ID dedup, cumulative cursor/fingerprint, контекст и начатый незавершённый расход.
  Сейчас `_state` публикуется до `usage:archive:v1` save, ошибка оставляет только notice,
  и журналом исходов это не является. Нужен узкий UsageMachine/owner (при необходимости
  отдельный module), exact RecordObserved/CumulativeObserved/Clear/Import + reset fencing.
  Нельзя заново вызывать провайдера при восстановлении счётчика. `RoutingLlmGateway`,
  HTTP gateways, ModelDirectories/DeclaredLimitsModelDirectory, SearchEngine,
  HttpMediaGenerationGateway и DefaultDossierResearcher сами состояние задач не хранят:
  машин им не нужно; операции/receipt принадлежат вызывающему owner. Auth lifecycle
  subscription остаётся у native provider, настройки показывают его projection.
- **Навигация и черновики** (выполнено в девятом срезе; пути ниже — состояние на момент
  инвентаризации, сегодня владелец навигации — `:magic-common:navigation:api`):
  `app/.../navigation/NavigationJournal.kt` и `RootComponent`
  уже единственный actor с visit IDs/back-forward/pending links; durable storage пока
  snapshot, приватные Command не разделены Intent/Fact. Строгое доведение решений 8–17
  требует нормализации одного navigation contract/journal, сохранив Decompose и
  BrowserNavigationSession как adapters. Не создавать конкурирующий routing owner.
  `core/storage/api/.../DraftSession.kt` уже application-lived one-writer actor с exact
  version, generation/owner/reset fences, clear-if-unchanged и readback. Если переносить
  его переходы в DraftMachine, сделать один protocol для draft aggregate, без машин на
  каждый input field; хранение payload/secret/blob остаётся портами. Raw byte/key-value,
  EventJournal и SecretStore не нуждаются в бизнес-машинах поверх себя.
- **Документация приложения:** EmbeddedDocRepository — immutable статьи и pure search,
  DefaultDocsComponent хранит лишь query/loading/selection проекцию визита. Новая машина
  не нужна. Аналогично DTO, formatting helpers и временные read caches не являются
  самостоятельным сохраняемым authority.

Минимальная очередь после planning/research: settings/profile lifecycle с corrupt-plugin
read guard; общий skills owner и нативные package/experience owners; usage accounting;
затем нормализация navigation/draft contracts. Проверки: late result after edit/delete/reset,
exact lost ACK, corrupt data retained, secret-free envelope, reopen without provider/model
calls, existing route/ID compatibility. Browser draft durability требует настоящих
IndexedDB tests; JVM и JS/Wasm compilation её не подтверждают.

### Уточнение независимых следующих срезов после пятого checkpoint

Read-only сверка текущих callers, без изменения реализаций:

1. **Settings/profile owner:** оставить одну машину в `feature/settings/api` для
   `AppSettings`, profiles/variants/default selection, dossier edits и допущенных
   catalog/dossier operations. `DefaultSettingsService` становится исполнителем;
   `JsonSettingsRepository`, `JsonLlmProfileRepository`, `JsonModelDossierRepository`
   сохраняют прежние keys и credential protocol как adapters. Все profile writers,
   включая startup migration, onboarding, media selection, import и profile deletion,
   должны проходить owner. Catalog result проверяет точную revision подключения, dossier
   result — revision профиля и редактируемого описания; удаление резервирует ID до reset.
   Составной Import/Delete хранит child receipts, а не повторяет выполненные шаги после
   падения. Сохранение настроек не выдаёт computer authority. ModelDirectory,
   DossierResearcher, SearchConnectionChecker и ProfileBridge остаются портами эффектов.
2. **Plugin owner — отдельный ограниченный пакет:** `DefaultPluginService` уже имеет
   очередь и reset barrier; её переходы enable/config/import/reset можно вынести в
   `feature/plugins/api`, оставив registry и rendering обычными портами/проекциями.
   Но одного переноса toggle недостаточно: `DefaultSettingsService.importProfile`
   сейчас вызывает `settingsRepo.savePluginStates(bundle.plugins)`, а `wipe` удаляет
   тот же `plugins` key. Нужны typed PluginCommands для импорта/reset с operation ID и
   acknowledgement; Settings owner вызывает их и не пишет key. Unknown plugin IDs и
   private config сохраняются. Срез settings и срез plugins независимы после фиксации
   этого API; общий `app/Dependencies.kt` меняет один назначенный интегратор.
3. **Общая библиотека навыков:** `feature/skills` сохраняет catalog/search/UI и получает
   одну SkillsMachine для install/edit/enable/delete/import. `SkillInstaller` должен
   стать чистой политикой перехода: сейчас проверка name/source, выбор прежнего ID и
   save разделены. `SkillStore` перестаёт предоставлять произвольный writer; плагины и
   settings import используют owner commands. Реальный app consumer —
   `SkillsRepositoryPlugin`; `SelfEducationPlugin` сейчас не зарегистрирован в app,
   поэтому его нельзя выдавать за действующий production flow или автоматически
   включать при миграции. `SkillLibrary`, EmbeddedSkillCatalog, SkillSelector и
   per-request SkillInstructionRuntime сами durable state не владеют.
4. **Нативные releases и опыт — два владельца:** предлагаемые JVM-only пары
   `magic-agent/skills/{api,impl}` и `magic-agent/skill-experience/{api,impl}`. Первый
   получает переходы `SkillReleaseStore` и запись `LocalSkillRepository`: immutable
   release checksum, quarantine/review, generation-bound activation/project consent,
   rollback и immutable coding-run audit. `LocalSkillsPlugin`/`ProjectSkillsPanel`
   остаются consumers; каталог, download/ZIP validation и UI не получают отдельные
   машины. Второй получает `LocalSkillExperience`: run tickets/generation, retention,
   deletion tombstones, generation/evaluation admission и точные результаты платных
   вызовов. Связь с releases выражается child commands/receipts через API, вместо
   зависимости на concrete LocalSkillRepository и `beforeNewInstall` callback.
   `DesktopSkills` должен связывать immutable constructor ports вместо setter
   `packages.beforeInstructions`. Сохраняются `skill-packages/snapshot.json`,
   `skill-packages/coding-runs`, content-addressed releases и
   `skill-experience/experience.json` как legacy inputs/projections; restores не
   активируют пакет, не восстанавливают consent из import и не продолжают оплачиваемую
   генерацию. Experience journal сохраняет текущую закрытую схему enum/numeric data,
   без текстов исходных диалогов и credentials.

Первые проверяемые риски следующего среза: corrupt `plugins`/`skills` JSON сейчас
превращается в empty list и может быть перезаписан; поздний catalog result использует
новый профиль с тем же ID; import может обойти plugin/skill owner. Для каждого пакета
нужны reducer tests, exact lost-ACK/reopen/reset tests и проверки сохранения старых IDs,
keys, consent и UI actions. Исходные suites: PluginServiceTest, JsonLlmProfileRepositoryTest,
SettingsDraftsTest, SkillInstallerTest/SkillStoreTest, SkillPackageContractTest,
LocalSkillRepositoryTest, ProjectSkillsTest, SkillAdapterWireIntegrationTest,
LocalSkillExperienceTest и ExperienceFormOwnerTest. Новых проверок в период общего
checkpoint не запускали.

## Пятый срез: native NoDispatch

Backend теперь журналирует отдельный `NoDispatchConfirmed` для существующего закрытого
запроса без допущенных попыток task-agent. Proof содержит точный run, собственный ID и
поколение журнала; пустой snapshot, отсутствие PID и отсутствие Attached доказательством
не являются. После полного replay создаётся proof только для последнего запроса сессии:
старые уже заменённые preflight-запросы не получают новую обязанность восстановления.
Подтверждение не утверждает завершение ресурсов других владельцев.

Явное родительское решение получает отдельный durable acknowledgement. Следующий новый
`Begin` принимает его ровно один раз; исходный request и номера попыток не меняются.
Recovery snapshot также возвращает точный consumer run для использованного решения,
чтобы parent не терял решение при ошибке до native admission и не передавал его повторно
после успешного admission. Generic/Desktop transport передаёт proof/ack/consumption без
потери engine, session, request или journal generation. Restore не запускает процесс,
не повторяет модельный запрос и не подтверждает решение за пользователя.

Owner gate: backend API 10, lifecycle 26, factory 6, Pi 80 (1 opt-in skip), Codex 35
тестов прошли; architectural verifier/self-test PASS. Проверены active/missing run,
reserved attempt без процесса, restart до первого launch, lost ACK/cancel после proof
и acknowledgement, неверная эпоха, unreadable readback и одноразовое admission.
Host consumer gate после переноса planning: GenericNativeRuntime 6/6,
NativeRuntimeDispatch 3/3, NativeLifecycleJournalAdapter 4/4 PASS, без skip;
`/tmp/magicpaper-fifth-native-generic-tests.log` и
`/tmp/magicpaper-fifth-native-bridge-results`. Проверен точный NoDispatch proof,
решение, binding и consumer run; recovery exception не превращается в Finished.


### Пятый срез: checks owner — промежуточная проверка

Вместо отдельного research-only владельца выделен один `magic-agent:checks:api/impl`
для жизненного цикла команд с двумя разными политиками: защищённый исходный проект
и управляемая рабочая копия. Это сохраняет общее доказательство остановки процессов,
не смешивая права записи. `CommandCheckMachine` принимает точные scope/request/call/attempt,
отдельно записывает подготовку процесса, разрешение старта, выход, остановку группы,
восстановление прав и attestation результата. Duplicate call с иным payload запрещён.
`TaskWorkspace.verify` получает journaled operationId, чтобы новая проверка после repair
не переиспользовала результат прежней попытки с тем же taskId.

`CheckInputJournal` хранит приватные payload и immutable completion, общие envelopes
не содержат argv/output/путей. Проверяются digest, epoch, полный prefix и точный timestamp
при потерянном ACK. Inputs замораживаются до первого suspend; completion не может
переписать уже известные exit/group/authority facts. Replay не интерпретирует эффекты.
Реальный OS probe также является journaled command в фиксированном принадлежащем
приложению workspace, а не незаметным process launch при restore. Corrupt artifact
manifest сохранён и блокирует новый grant вместо превращения в empty map.

Промежуточный focused gate: API 8/8, impl 30/30 (owner, journal, artifact manifest,
workspace policy, executable resolution), `/tmp/magicpaper-checks-owner-repeat.log`.
Native OS port и runtime/app wiring ещё проходят интеграцию. Нового общего checkpoint
после этих изменений ещё нет; это не подтверждение установленной macOS/Windows/Linux.
Минимальное process containment managed checks сохраняет права файлов/network, но на
macOS запрещает уход из группы (Java fork launcher), а на Linux требует bubblewrap
с PID namespace. При отсутствии containment запуск отклоняется, fallback обычным
ProcessBuilder не выдаёт ложное proof. Orphan без сохранённого cleanup receipt остаётся
UNKNOWN: завершение родительского PID не разрешает повтор.


### Пятый срез: native process containment — приёмка порта

`ResearchSandbox`, `UnixResearchProcess`, `WindowsResearchSandbox` и OS tests перенесены
в `magic-agent:checks:impl` с прежним package. `PreparedCheckProcess` отделяет подготовку
от разрешения выполнить команду. На Unix trusted gate уже находится внутри containment;
на Windows основной поток создан suspended и включён в Job Object. Приватная неизменяемая
запись процесса сохраняется до release; в ней нет argv, environment или output.
Windows сохраняет полный исходный ACL bundle (включая дочерние объекты, их file identity
и inheritance protection) через owner callback до первого grant. Восстановление использует
удерживаемые handles, проверяет исходные ACL и отсутствие временного SID на новых artifacts.

Успешное завершение требует подтверждённой остановки всей принадлежащей группы/namespace/job,
восстановления authority и сохранения отдельного cleanup receipt. macOS удерживает PID группы
через WNOWAIT до проверки libproc; EPERM для группы из одного zombie принимается только после
проверки отсутствия живых членов. Linux использует bubblewrap PID namespace и pidfd для его
init; Windows проверяет ActiveProcesses=0 у Job Object. Ошибки cleanup сохраняют primary cause
и остальные ошибки. Read-only `readNativeCheckCleanup` принимает только точный persisted proof;
повреждённая identity/cleanup запись не превращается в отсутствие данных.

macOS native gate: 17/17 PASS, без skip (`/tmp/magicpaper-native-check-process.log`):
prepare/release, отмена до release, writable managed workspace, защищённые source/Git,
links/artifact reuse, дочерние и detached процессы, отмена и deliberate JVM halt.
После ужесточения reader отдельный receipt gate 7/7 PASS
(`/tmp/magicpaper-native-check-receipts-final.log`). Windows/Linux native исполнение здесь
не выполнялось; только production compilation, policy и сериализация receipts. Managed
checks теперь требуют containment: Seatbelt на macOS, bubblewrap с PID namespace/pidfd
на Linux, Job Object на Windows. Файловые и сетевые права managed workspace сохраняются;
Java на macOS использует fork, поскольку смена process group/session и posix_spawn запрещены.
Отсутствующий containment не заменяется обычным ProcessBuilder. Ошибка уже начавшейся
native preparation без полного cleanup proof остаётся UNKNOWN.

Crash-backstop может остановить дочернюю команду после аварии приложения, но без сохранённого
полного cleanup receipt это не доказательство для повторного запуска или успешного результата.
Orphan recovery здесь не объявлен завершённым; process cleanup и artifact/result completion
остаются разными доказательствами. Общий JVM/platform checkpoint выполняет root после
интеграции всех параллельных владельцев.

### Пятый срез: parent NoDispatch и research bridge — focused acceptance

`CodingMachine` принимает отдельные `AbandonNotDispatched` / `NoDispatchAcknowledged`:
точные engine/session/request/proofId/journalGeneration и durable parent decision обязательны.
Пустой список native attempts не является proof; нумерация attempts, включая `0`, сохранена.
Pending acknowledgement остаётся до точного `RecoveryAcknowledgementConsumed` от native
owner, включая отказ новой preflight после приёма решения. `BeginRun` и подготовка worktree
сами его не расходуют. Уточнение сохраняется через `Clarify` до recovery effects. В ветке
`deliveryOnly → BeginRepair` передаётся тот же scoped acknowledgement, а факт native dispatch
относится именно к новому repair request; его preflight failure остаётся UNKNOWN.

- `CodingMachineTest`: 20/20 PASS (`/tmp/magicpaper-no-dispatch-parent-api.log`).
- App: `CodingNoDispatchRecoveryTest` 6, `CodingResumeTest` 9,
  `CodingResearchModeTest` 4, `QuestionnaireViewModelTest` 4,
  `SessionInputQueueTest` 4 и прежние 21 `CodingWorktreeTest` — PASS.
  Новый `deliveryOnlyRepairConsumesExactNoDispatchProofAndAnotherPreflightFailureStaysUnknown`
  тоже PASS после исправления fixture: повтор отправляется через видимую RECOVER_RUN форму,
  а не скрытое при открытой форме действие resume. Все exact proof/UNKNOWN assertions сохранены.
  Логи `/tmp/magicpaper-fifth-parent-app-tests.log`, `/tmp/magicpaper-fifth-repair-app-test.log`;
  XML обоих запусков и сводка 49 distinct cases: `/tmp/magicpaper-fifth-parent-app-results`.

Required `CommandChecks` проходит из JVM app instance через native host, generic/desktop/Pi
и subscription adapters к `AgentRunResources` и stateless `ResearchCheckBridge`.
Reset/resume/shutdown принадлежат desktop application lifecycle. Check ref содержит
project/session/request/generation и точную типизированную JSON-RPC identity (`id.toString()`);
числовой `1` отличается от строкового `"1"`. Pi повторно передаёт исходный callId без UUID.
Даже in-memory duplicate с иными method/params отклоняется; unknown и operational failures
не раскрывают exception payload, отмена не превращается в успешный результат.

`ResearchCheckBridgeTest` 5/5 PASS: authenticated round trip с exact scope/policy,
in-flight changed-argument rejection, безопасные ошибки/UNKNOWN, закрытие endpoint с
отменой и настоящий локальный Node replay Pi extension (без сети). Native transport
`GenericNativeRuntimeTest` 6, `NativeRuntimeDispatchTest` 3 и
`NativeLifecycleJournalAdapterTest` 4 — PASS; combined 18/18, без skip.
Логи `/tmp/magicpaper-fifth-native-bridge-tests.log`, `/tmp/magicpaper-fifth-native-generic-tests.log`;
XML `/tmp/magicpaper-fifth-native-bridge-results`. JUnit return-type fixture исправлен на
`runBlocking<Unit>` без изменения assertions. Новых визуальных surfaces нет; используется
существующая recovery questionnaire. Installed OS acceptance и общий JVM/platform checkpoint
публикуются отдельно их владельцами.

### Пятый срез: проверки root перед общим checkpoint

Промежуточный gate checks/workspace: 83 теста, 0 ошибок, 10 native opt-in пропусков
(API checks 8, impl 50; API workspace 8, impl 17). Лог
`/tmp/magicpaper-fifth-checks-workspace-final.log`, XML и сводка
`/tmp/magicpaper-fifth-checks-workspace-results/summary.json`. Отдельные 17 macOS
native acceptance cases выше выполнены явно и не заменяются этим gate.
Проверены cleanup после отказа записи Stop, отмена на передаче подготовленного
процесса, rollback reset до установки fence и после отмены с уже установленным fence.
Позднейшая независимая ревизия обнаружила обход persistenceUnknown в inspect, лишний
probe для cached call и гонку регистрации job с reset. Все три исправлены: lookup и
pure Submit preflight выполняются под workspace mutex до probe, actual Submit повторно
проверяется после него, job регистрируется атомарно с admission. Target mutex не удерживается
во время probe, поэтому одинаковый canonical workspace не создаёт вложенную блокировку.
Повторный checks impl gate: 54 теста, 0 ошибок, 10 native opt-in пропусков (44 executed PASS),
из них `DefaultCommandChecksTest` 14/14 PASS. Новые проверки покрывают persistenceUnknown
поверх finished result, duplicate/changed/unknown lookup без probe, probe workspace как target
и отмену принятого запроса при rollback reset без смены epoch. Лог
`/tmp/magicpaper-checks-review-fixes.log`, XML `/tmp/magicpaper-checks-review-results`.

Module architecture и Paper verifier/self-test проходят (`/tmp/magicpaper-fifth-module-verifier.log`,
`/tmp/magicpaper-fifth-design-verifier.log`). Полный JVM/platform checkpoint пятого
среза ещё не выполнен; долг RequestPin/TreeHeader не меняется на основании focused runs.

### Пятый срез: удаление старого PlanRunner

Линейный `PlanRunner` удалён: поиск production callers подтвердил, что его вызывали
только собственные шесть тестов. Полезные проверки выбранной модели перенесены
в два интеграционных `PlanningExecutionModelSelectionTest` настоящего владельца плана.
Ordering/report/failure/stop покрывают `parallelStagesStartTogetherAndJoinWaits`,
`sharedFolderKeepsNextWorkerOutUntilAcceptanceCompletes`,
`engineFailureCannotPassPositiveVerifier`, `stopAbortsSessionAndReleasesOwner` и
проверки ожидания cleanup в `PlanningExecutionServiceTest`; старый runtime без
journal admission больше не остаётся вторым способом запустить план. Новые два
model-selection cases прошли в `/tmp/magicpaper-planning-stage-tests.log`; исторические отчёты помечены отдельно.

Scheduler gate: 22/22 PASS, GitTaskWorkspace 30/30, SessionIntegration 9/9
(`/tmp/magicpaper-fifth-root-runtime-final.log`,
`/tmp/magicpaper-fifth-root-runtime-results`). TaskWorktreeIntegrationChecks 8/9:
fixture отменяла job до journal Submit, хотя проверяла результат запущенной команды;
теперь ждёт фактический marker команды перед abort и сохраняет исходные cleanup
assertions. Повтор всех 9 TaskWorktreeIntegrationChecks прошёл в
`/tmp/magicpaper-planning-stage-tests.log`. Workspace owner 17/17 PASS после
защиты от stale Entry, удержанного через reset/resume; лог
`/tmp/magicpaper-fifth-workspace-reset-final.log`. Общего checkpoint по-прежнему нет.

### Пятый срез: продолжение плана и видимые ошибки

`PlanningStore.admittedRuns` публикует разрешения текущего процесса после durable
commit и checkpoint. `PlanningPanelController` и `CodingPlanningState.runs` получают
проекцию `planningRunUi` из machine state, разрешений и ошибки сохранения: сохранённый
`Plan.intent = RUN` сам по себе больше не выключает кнопку «Продолжить». Изменены
существующие Paper-кнопки в `CodingPlanningPlugin.Panel` и `PlanningChatMessage`.

| Состояние | Панель / сообщение с графом | Проверка |
|---|---|---|
| Восстановленный RUN без разрешения | «Ожидает продолжения», Continue доступна, Pause недоступна | 1000 и 390 px, восстановление не вызывает runtime |
| Свежий подтверждённый запуск | Continue недоступна, Pause доступна | одно нажатие даёт ровно один runtime call |
| UNKNOWN после остановки | «Нужно внимание», Continue недоступна | 390 px, нового вызова runtime нет |
| Ошибка фонового планирования | безопасный текст в существующем notice | видна без открытия панели; после dismiss одинаковая ошибка появляется вновь |

`OrchestrationService.publishError` — единственное место публикации error state.
Совместимый `error: StateFlow<String?>` сохранён; read-only `failureEvents` имеет новый
opaque ID для каждой публикации без exception/provider payload. UI отображает
фиксированный безопасный текст и не очищает чужую ошибку при начале параллельного
действия. Отмена в replay session command распространяется как cancellation.

Приёмка: `PlanningContinuationRenderTest` 1/1 (реальные компоненты и owner),
`PlanningRunProjectionTest` 3/3, `PlanningErrorNoticeTest` 1/1 PASS, без пропусков.
Логи: `/tmp/magicpaper-planning-stage-tests.log` (общий runtime gate 126/126) и
`/tmp/magicpaper-planning-error-notice-repeat.log`. XML и рендеры сохранены в
`/tmp/magicpaper-fifth-planning-ui-evidence/`; исходные PNG —
`magic-agent/runtime/impl/build/reports/planning-continuation/` (`restored-390`,
`restored-1000`, `unknown-390`, `chat-restored-390`, `chat-restored-1000`,
`chat-unknown-390`). Рендеры осмотрены: действия помещаются и читаются, новых
элементов или изменений геометрии нет. Paper и module verifier/self-test PASS.
Это Compose JVM render/semantics и тестовые native-порты на macOS; установленное
приложение, Windows/Linux и настоящий provider не проверялись этим UI gate.

Дополнительная проверка после полного checkpoint нашла третью кнопку в
`OrchestrationStatus`. Она теперь использует ту же `CodingPlanningState.runs`,
а раскрытый блок «Подробнее» покрыт матрицей restored RUN / live RUN / UNKNOWN
на 390 и 1000 px. `PlanningContinuationRenderTest` 1/1 PASS в
`/tmp/magicpaper-fifth-integration-repair-results`; все шесть рендеров осмотрены.
Архив: `/tmp/magicpaper-fifth-planning-ui-evidence/status-continue/`, PNG
`status-{restored,live,unknown}-{390,1000}.png`. Восстановление само не запускает
работу, UNKNOWN оставляет Continue недоступной. Это исправление существующего
контрола; нативная установленная платформа этим прогоном не проверялась.

Оставшийся долг владельца planning workspace: `GitPlanningWorkspace.gitBytes`
запускает Git без сохраняемого подтверждения остановки группы процессов.
Самостоятельный `PREPARE_INTENT` может остаться UNKNOWN ещё до создания worker
session. Его явная сверка использует прежний `PlanRecoveryAuthority.USER` и точный
снимок журнала; это решение человека после проверки результата и оставшихся
процессов, а не доказательство завершения native-процесса. Проверка/подтверждение
не повторяет подготовку и не выдаёт разрешение запуска. Выделение workspace owner
и сохраняемого process proof остаётся необходимым для полного TARGET.

### Пятый срез: неизменяемые зависимости исполнения и полномочия этапов

`PlanningExecutionService` требует конструкторный `PlanningAttemptAuthority` из runtime API
для допуска/явного повторного запуска и публикации checkpoint соседнему владельцу сессий.
`CodingRuntimeGraph` связывает его с `SessionOrganismService`; отсутствие владельца даёт
явный отказ, а не успешный пустой callback. Scoped lazy provider для `PlanningExecutionHooks`
разрешает цикл создания execution/orchestration без поздних setters. `OrchestrationService.bootstrap`
больше не изменяет зависимости execution. Управляемые mutable callbacks остались только
в `testSupport/native/TestPlanningExecutionPorts.kt`.

Combined gate `/tmp/magicpaper-planning-stage-tests.log`: planning API 54/54, impl 16/16,
runtime 126/126 PASS. Включены `PlanningExecutionServiceTest` 82, `PlanStrategyClassifierTest` 10,
`PlanningCheckpointReloadTest` 1 и `CodingRuntimeGraphTest` 2. Классификатор получает explicit
Start admission; concurrent edits/stop проходят semantic intents, восстановление не возвращает
execution grant. Automatic backoff проверяется реальным переходом внедрённых часов, без seed
checkpoint. Ошибка транспорта после workspace prepare сохраняет exact unsettled intent и UNKNOWN:
restore/bootstrap и явные повторы не повторяют подготовку.

Новые `PlanningStageAuthorityTest` 4/4 проверяют native identity binding, late progress после
interruption, запрет новых worker effects при UNKNOWN/STOPPED/restored/paused и ABA старого
merge outcome после новой retry. `FinalAttemptRulesTest` 8/8 проверяют неизменность принятого
основного отчёта при delivery, чужую/неуспешную приёмку, UNKNOWN, недопустимые фазы и replay
сериализованных final-review facts. XML этих acceptance cases и execution fixtures сохранены в
`/tmp/magicpaper-planning-ports-authority-results`. Более широкий JVM/platform checkpoint
остаётся отдельной проверкой root.

### Пятый срез: владелец планирования и допуск исполнения

Добавлены JVM-модули `:magic-agent:planning:api` и `:magic-agent:planning:impl`.
`PlanningMachine` принимает значения Intent/Fact, отклоняет недопустимые переходы
и выпускает конкретные результаты переходов; `State` создаёт только владелец.
`DefaultPlanningStore` — единственный production writer плана. Runtime получает
API через `PlanningStoreFactory`, реализацию создаёт app. Публичного `update`
с произвольной lambda или `save(Plan)` в контракте владельца больше нет.

Сохранены `plan.id` как имя журнала, исходные snapshot-ключи и чтение `plan-commit/1`.
Новые записи `plan-commit/2` содержат вход машины и сохраняют прежние sequence/operation
для intent/outcome/reconciliation и классификатора стратегий. Snapshot импортируется
однократно. Append подтверждается точным envelope, epoch, неизменным префиксом,
одной новой записью и high-water; потерянное подтверждение проверяется чтением.
Повреждение, откат истории и неизвестный исход записи закрывают новые допуски.
Удаление сохраняет fence использованной идентичности и не может стереть открытый intent,
включая паузу; поздний известный результат ещё можно записать до удаления.

Восстановление RUN/STOP, READY-сообщений и checkpoint проверки не запускает runtime,
модель, workspace или доставку. Только новое явное действие создаёт process-local
допуск после durable commit. Продолжение ещё работающего приостановленного controller
сохраняет его RunRef; после перезапуска тот же ref не даёт разрешения. Отмена уже
вызванного исполнителя оставляет UNKNOWN до конкретной сверки, а подтверждённый отказ
до вызова отмечается REJECTED. Pause/Stop не подменяют факт окончания внешнего действия.

`StageMutation` и `FinalAttemptMutation` теперь применяются внутри `PlanningMachine`;
интерпретатор исполняет I/O и передаёт типизированные факты. Старый whole-attempt input
читается для совместимости журнала, но отвергается публичным writer. Ссылка попытки
проверяет session/generation/turn/phase, счётчики повторов и interruption; поздняя старая
попытка и старый результат merge после нового retry отклоняются. Телеметрия не меняет
phase, assignment, приёмку или счётчики; ей запрещены rebinding engine identity,
воскрешение остановленных карточек и очистка неизвестного внешнего исхода. Результат
итоговой проверки фиксируется отдельно от последующей проверки/переноса результата.

Mutable execution hooks заменены обязательными constructor ports
`PlanningAttemptAuthority` и lazy provider оркестратора; graph bootstrap не обращается
к ещё не созданному циклическому участнику. Задания остаются application-owned.
UI показывает продолжение по фактическому допуску, а не сохранённому `intent=RUN`;
UNKNOWN не открывает новый запуск. Ошибки фонового планирования доходят до существующего
уведомления, в том числе повтор той же ошибки после закрытия предыдущего уведомления.

Focused acceptance: planning API **54/54**, planning impl **16/16**, runtime **126/126**,
без пропусков. Runtime набор включает execution, реальные выбранные модели, journal
outcomes, восстановление, reload checkpoint, classifier, task checks, graph reset/bootstrap
и четыре проверки проекции/реального UI продолжения. Лог
`/tmp/magicpaper-planning-stage-tests.log`; XML и сводка
`/tmp/magicpaper-planning-fifth-owner-results/summary.json`. Production и все runtime test
sources компилируются (`/tmp/magicpaper-planning-stage-compile2.log`). Повтор app notification
case — PASS (`/tmp/magicpaper-planning-error-notice-repeat.log`). Визуальные renders и их
осмотр фиксирует владелец UI отдельно.

Это evidence владельцев, не общий checkpoint и не завершение TARGET-ARCHITECTURE.
Полные JVM/platform проверки пятого среза выполняет root. Во время freeze продолжается
read-only ревизия допустимых начальных Create и служебных phase facts; дополнительные
ограничения потребуют отдельного теста до заявления о завершённой ревизии всех входов.
Установленные нативные движки в этом наборе не запускались.

### Пятый общий прогон — новые отказы ещё закрываются

Первый полный JVM/platform прогон: `/tmp/magicpaper-target-fifth-checkpoint.log`,
архив `/tmp/magicpaper-target-fifth-checkpoint-results/summary.json`. Актуальные XML:
2765 тестов, 18 отказов, 21 skip; это **не принятый checkpoint**. `skills:impl`
не скомпилировал тесты из-за нового прямого доступа fixture к DefaultPlanningStore;
его старые 147 XML исключены из счёта. Добавлена только jvmTest-зависимость на
planning:impl. 16 новых отказов принадлежат runtime integration, два — известной базе.
Android host, desktop host, Node protocol/screenshot и `compileMigrationTargets`
прошли; androidApp unit task — NO-SOURCE. Kotlin incremental compilation fallback
в этом прогоне восстановился и не стал отдельным отказом сборки.

После исправления wiring hooks восстановленных fixtures, явного Continue в restart
сценариях, детерминированных IDs schedule и ожиданий UNKNOWN первый целевой повтор:
267 тестов, 6 отказов, 0 skip; архив `/tmp/magicpaper-fifth-integration-repair-results`,
лог `/tmp/magicpaper-fifth-integration-repair.log`. Оставшиеся случаи: два отказа выбора worktree (дальнейшая диагностика нашла production
regression: WorkspaceSelected(false) сразу перезаписывался stale initial true),
два selective interruption/acknowledgement сценария,
запрет Pause при UNKNOWN и cached orphan-parent scheduler update. Это текущая работа,
не расширение списка допустимых baseline-отказов.

Строгое чтение legacy planning checkpoints: `JsonPlanningRepositoryIdentityTest`
**8/8 PASS**, XML `/tmp/magicpaper-planning-json-identity-results`. V2 key связывается
с точными plan/project IDs; старый project checkpoint — с project key; повторяющиеся
flat plan IDs и пустая identity отклоняются до построения map. Ошибочное чтение не
перезаписывает bytes, valid matching backup по-прежнему восстанавливает corrupt
legacy snapshot. Новый delete tombstone хранит target.projectId; совместимость старого
`deletePlan(planId)` допускает его точный plan-ID alias только при null payload.

Дополнительные исправления детерминизма подготовлены к следующему общему gate:
durable timeout payload использует UTC; новые blocker tokens вычисляются из явных
полей issue, а разрешение skip проверяет полный `PlanningSkipProof`. Старый числовой
суффикс JVM enum hash восстановить нельзя. Только reader уже принятого journal input
связывает старые tokens один-к-одному по точному run/stage/attempt/generation/turn/retry
prefix и затем применяет обычные проверки текущих acceptance/run/criteria; live input
без полного proof запрещён. Исторические сообщения и IDs не переписываются. Аналогично
legacy peer cleanup узнаёт сохранённый числовой ID по source/stage/target и полному
payload, сохраняя все прежние запреты удаления начатого исполнения. Новые проверки:
`PlanningBlockerProofTest` (4), `MessageSchedulingReplayTest` (1, смена JVM timezone),
`LegacyPeerCommandReplayTest` (3), `PlanningSkipJournalTest` (4); их результат ещё
ожидается, repository acceptance выше от них независим.

### Следующая граница workspace — read-only inventory

`LocalPlanningWorkspace` пока остаётся реализацией с mutable path→project ID lease map
в runtime:api; durable `TaskWorktreeOwner` владеет исходами Git-операций конкретной
project/session, но не общей эксклюзивностью пути между планами. Следующий ограниченный
срез: вынести реальные ресурсы в workspace:impl, оставить в API обязательный порт leases
с точным request/owner ID и opaque handle каждого захвата. Освобождение должно принимать
этот handle: поздний release предыдущего запроса не может снять новый замок с тем же
project ID. Canonical path и OS file lock остаются владельцами физической эксклюзивности;
replay журнала не создаёт процессный lease и не разрешает запуск. Это план, не выполненная
миграция; отдельная вторая state machine для transient mutex не предлагается.

Ограничение Git PREPARE также ещё открыто: `GitPlanningWorkspace.gitBytes` запускает
`ProcessBuilder`, читает stdout и ожидает PID, но при исключении после start не имеет
terminate/join finally, registry или доказательства завершения process group. Обычный
возврат корутины или выход корневого git PID не доказывает завершение дочерних фильтров.
До `StageCreated` отсутствует native session, которую мог бы проверить существующий
session reconciliation. Будущий host port должен вернуть точное process/operation proof,
сохранить UNKNOWN и lease при недоказанной остановке, а cleanup обязан сохранять исходную
ошибку/cancellation. Новые процессы и повтор Git в этом аудите не запускались.

Повтор planning:impl после исправления scoped criterion IDs тестовой приёмки —
**30/30 PASS**, архив `/tmp/magicpaper-fifth-skip-proof-repeat-results`, лог
`/tmp/magicpaper-fifth-skip-proof-repeat.log`. Четыре `PlanningSkipJournalTest` подтвердили
чтение старых принятых numeric tokens без переписывания записей/нового допуска,
отклонение live-команд без полного proof и отказ для malformed/foreign/неоднозначной
identity либо чужих run/attempt/criteria. UI-контракт также несёт optional
`UserInteractionRequest.verificationProofs`: подтверждается proof показанного запроса,
а не заново прочитанная версия результата. Это поле старым сохранённым вопросам не
выдумывает разрешение; новый runtime regression для изменения snapshot при том же
blocker token ожидает общего целевого прогона.

### Остаток planning: граница команд и исполнителя

Пятый срез перенёс реальную машину, правила и единственную durable запись, но не весь
исполнитель planning. `PlanningExecutionService`, `PlanningJournalRecovery`,
`MessageScheduler` и classifier ещё находятся в runtime:impl. Публичный
`PlanningStore.dispatch(Input)` вместе с операциями допуска/результата по-прежнему даёт
соседям техническую возможность прислать `Fact`; проверки точной identity не заменяют
ограничение владельца по решениям 11/13. Поэтому текущая запись о владельце planning
не означает завершённую изоляцию всех производителей фактов.

Минимальный следующий срез: перевести внешнюю API-поверхность на Intent-команды,
проекции и lifecycle, переместить исполнители в planning:impl и оставить полный
Input-writer внутренним. Нужны реальные порты соседних ресурсов вместо конкретных
`TaskWorktreeService`/`SessionOrganismService`; существующие PlanningAttemptAuthority,
PlanningExecutionHooks и MilestoneVerifier могут принадлежать planning:api с прежними
значениями. Scheduler требует лишь неизменяемые delivery/receipt порты. Settings API
не зависит на runtime и цикла не создаёт. planning:impl → runtime:api технически
ациклично, но тянет широкий UI/runtime-контракт; planning:api → runtime:api недопустимо,
поскольку runtime:api уже зависит на planning:api. Эта запись — подтверждённый план
закрытия границы, не заявление о выполненном переносе и не baseline-отказ тестов.


### Пятый срез: результаты полных повторов и оставшаяся проверка lifecycle

После исправлений повторены все JVM-тесты и platform checks. Архив
`/tmp/magicpaper-target-fifth-repeat-results/summary.json`: **2952 теста, 23 skip**,
два исходных RequestPin/TreeHeader и новый отказ `SessionTreeRuntimeTest`.
Его диагностика доказала гонку виртуального timeout с `stateIn` реального
Dispatchers.Default; обе связанные cleanup-проверки переведены на реальные часы,
исходные assertions сохранены. Повтор всех JVM —
`/tmp/magicpaper-target-fifth-accepted-results/summary.json` (имя архива историческое,
это ещё не принятый checkpoint): те же 2952/23, runtime **1172/1172** проходит,
но `RuntimeLifecycleTest.koinOwnersAreSingletonsAndResetKeepsThemUsable` прочитал
repository до асинхронного commit нового чата. Fixture теперь ожидает публикацию
конкретного нового session ID через ChatService.state, не увеличивает таймаут и
не ослабляет assertion количества. Проверка этого изменения предстоит после
следующих source edits. Новый отказ не включён в разрешённую baseline.

Planning API **61/61**, impl **30/30**, skills **147** (2 opt-in skips) прошли.
Owner repeat до общего gate: 1380 тестов, два исправленных fixture отказа;
их отдельный повтор **4/4 PASS**, архив `/tmp/magicpaper-fifth-final-repair-results`.
`compileMigrationTargets`, Android host, desktop host и оба Node checks прошли
в полном повторе; androidApp unit — NO-SOURCE. Module/Paper self-tests PASS.

Следующий срез уже начат отдельно от этих classfiles: exact workspace lease handles,
атомарный refinement со specification-only DTO и реальное native ACK/NoDispatch
восстановление планирования. Последнее необходимо: CodingRuntime.reconcile по
реальному backend не только подтверждает остановку, но и запрещает UNKNOWN outcome;
прошедшие fake-port recovery tests не доказывают backend acknowledgment/consumption.
До завершения новой интеграционной проверки эта возможность не считается принятой.

#### Проверка восстановления standalone-плана и текущая native-граница

Финальный owner gate перед native-интеграцией: 28/28 целевых тестов без пропусков —
`PlanningStandaloneRecoveryTest` (8), `PlanningJournalRecoveryTest` (5),
`PlanningPanelControllerTest` (8), `PlanningContinuationRenderTest` (1),
`PlanningRunProjectionTest` (3), `PlanningRecoveryIssueTest` (3).
Свежие XML и PNG сохранены отдельно от предыдущего прогона:
`/tmp/magicpaper-fifth-planning-ui-evidence/standalone-recovery-final/`.
Осмотрен обновлённый `recovery-confirmed-390.png`: после явного подтверждения
владелец снимает только собственное предупреждение, показывает «Остановлено» и
доступное «Продолжить»; подтверждение само работу не запускает. Матрица третьего
контрола OrchestrationStatus: restored RUN / live RUN / UNKNOWN при ширине 390 и
1000; новый recovery-panel также проверен на обеих ширинах. Все мутации панели
адресуют показанный plan.id, включая редактирование и удаление при соседнем плане.

Эти UI-тесты используют контролируемый CodingRuntime. Они не доказывают весь
native recovery protocol: отдельная интеграция переводит PlanningJournalRecovery
на реальные durable native ACK, NoDispatch и exact consumption через фабрику
backend. Старое runtime.reconcile отклоняло даже остановленный UNKNOWN; это не
разрешалось скрытым повтором и остаётся отдельной проверяемой границей нового среза.
Compose JVM renders не заменяют проверку установленного приложения на Windows,
Linux, Android или в браузере. GitPlanningWorkspace по-прежнему не имеет полного
сохранённого доказательства завершения группы процессов; явное USER-подтверждение
workspace-операции не объявляется таким доказательством.

### Шестой срез: промежуточные owner-проверки и плагины

Планировщик после refinement: API 75/75 и impl 33/33 PASS
(`/tmp/magicpaper-sixth-planning-owner.log`, filtered archive
`/tmp/magicpaper-sixth-planning-owner-results/summary.json`). После native guards
API 78/78, impl 33/33 и app focused 31/31 PASS
(`/tmp/magicpaper-sixth-pre-native-results/summary.json`); это ещё не общий checkpoint.
Проверка runtime 283 теста выявила 9 отказов: две проверки identity исключения
(копирование coroutine stack recovery) и семь новых native recovery cases.
После исправления модели fixture один case дошёл до реальной ошибки: подтверждённый
NoDispatch оставлял requiresUser на точной попытке, и scheduler снова ставил план
на паузу. Исправление и новые proof guards ещё проходят приёмку; отказ не baseline.

PluginMachine добавлена в API плагинов; DefaultPluginService владеет журналом
вводов и приватными immutable payloads. Старый ключ plugins импортируется только
при virgin journal revision (seq=0, epoch=0). У SettingsRepository удалены методы
записи/чтения плагинов, settings import/export/reset используют PluginPreferences.
Прямая и потерянная квитанция append проверяются по точному сохранённому префиксу,
timestamp, revision и readback payload. UNKNOWN блокирует изменения до явного reload;
ошибка валидации не превращается в UNKNOWN. Проверки corruption/reset/import/cancel
подготовлены, но этот абзац пока не заявляет успешную приёмку нового владельца.

Owner gate плагинов/settings/checks прошёл:
`/tmp/magicpaper-sixth-plugin-checks-owner.log`, filtered archive
`/tmp/magicpaper-sixth-plugin-checks-owner-results/summary.json`:
plugin API 3/3, impl 23/23, settings 56/56, checks API 11/11,
checks impl 62 теста без отказов, 11 native opt-in пропущены.
Это 155 тестов без отказов (11 пропусков), а не общий JVM checkpoint.
После него добавлена регрессия no-op команды от устаревшего plugin owner;
она ещё требует следующего прогона.

Совместный focused gate после этих правок:
`/tmp/magicpaper-sixth-owner-closure.log`, archive
`/tmp/magicpaper-sixth-owner-closure-results/summary.json`: 472 теста, 1 отказ,
0 пропусков. Planning API 85/85, impl 36/36, plugins impl 24/24 и app focused
43/43 PASS. Runtime 284 теста: все lease/cleanup сценарии и 7 прежних native
recovery cases PASS; новый host-before-Begin case расходится в ожидании
IllegalArgumentException при инспекции. Это требует разбора контракта
inspect/confirm и не включено в baseline. Статические architecture/Paper
self-tests на этом срезе PASS. Полного шестого JVM/platform checkpoint ещё нет.

### Шестой полный checkpoint

Native-final: API 6/6, реальная integration 9/9 PASS
(`/tmp/magicpaper-sixth-native-final.log`, archive
`/tmp/magicpaper-sixth-native-final-results/summary.json`). Последний host-before-Begin
отказ оказался ожиданием исключения от публичного start: действие корректно
сохраняет UNKNOWN и отказывает без броска. Теперь тест проверяет отсутствие нового
admission/request/native launch, неизменные exact requests и неурегулированный intent.

Полный прогон `/tmp/magicpaper-target-sixth-checkpoint.log` завершён:
3033 JVM-теста, 24 skip, 2 исходных отказа (RequestPin/TreeHeader), новых отказов нет.
Архив `/tmp/magicpaper-target-sixth-results/summary.json` включает все JVM-модули,
кроме независимой tools/mission-visualization. Runtime 1189 без отказов (1 skip),
planning API 86/86 и impl 36/36, workspace impl 21/21, plugins API 3/3 и impl 24/24,
settings 56/56, checks API 11/11 и impl 62 без отказов (11 skip).
Android host-tests, desktop host, Pi Node protocol, screenshot context и
compileMigrationTargets PASS; androidApp unit task NO-SOURCE. Статические self-tests
architecture и Paper PASS. Общая Gradle-команда завершилась неуспешно только из-за
двух перечисленных baseline-тестов; это не полностью зелёный CI.

Найденные последующие работы пока не реализованы в этом checkpoint:
- сохранить native release authorization до INTENT_RECONCILED, чтобы падение перед
  записью локальной проекции не делало подтверждённый план невосстановимым;
- host reserve/activate handshake и строгая legacy correlation: отсутствие native
  записи по-прежнему не доказательство NoDispatch;
- owned Git metadata admission без raw ProcessBuilder до допуска и exact binary
  command/lease correlation для Git workspace операций;
- batch identity для inbox, дальнейшее закрытие live Revise и всего planning writer;
- SettingsMachine, UsageMachine с capture generation, оставшиеся skill/package,
  draft/navigation owners и долговечный application reset coordinator.

Проектные scratch-дизайны следующего среза лежат в /tmp/magicpaper-settings-owner-next,
/tmp/magicpaper-planning-inbox-draft, /tmp/magicpaper-native-release-authorization
и /tmp/magicpaper-workspace-operation-draft; они не доказательство реализации.

### Седьмой срез: владелец учёта расходов

`core:ai:api/UsageMachine` теперь единственный reducer расходов, контекста,
заменяемых pending IDs и накопительных native counters. `UsageRepository` стал
read-only legacy query; ключ `usage:archive:v1` и сохранённые usage IDs не менялись.
`UsageInputJournal` пишет конкретные Intent/Fact в private payloads, а в EventJournal
только opaque input ID/digest. JSON фиксирует defaults до записи. Обычная публикация
идёт после exact ACK/readback; потерянное ACK принимается лишь при неизменном полном
prefix, единственном ожидаемом append, точной epoch/identity и проверенном payload.
Повреждённые данные остаются на месте, пользователь видит безопасную failure,
export требует подтверждённый архив. Импорт legacy возможен только при virgin
seq=0/epoch=0; reset не возвращает старый архив. Production constructor требует
EventJournal и private KeyValueStore, in-memory fallback отсутствует.

`UsageObservation.Captured(generation)`/`Unavailable` захватывается до model/search,
media attempt и native flow; provider bridge получает тот же capture через
`RuntimeUsageContext`. Clear/import меняет поколение, поэтому поздний результат
старого вызова не пополняет новый архив. Capture никогда не даёт право на model
execution: внешний вызов принадлежит его прежнему владельцу и не повторяется
ledger при failure/replay. Полная история cumulative fingerprints хранится в
журнале; старый повтор не откатывает cursor и не списывает токены второй раз,
изменившиеся значения той же identity отклоняются. У legacy archive нет исторических
proofs: можно привязать только exact latest cursor+record, неоднозначный старый
counter остаётся отклонённым. App startup явно запускает ledger; Settings export
использует `exportArchive()`.

Acceptance: `/tmp/magicpaper-usage-owner-results/summary.json` и логи
`/tmp/magicpaper-usage-owner-tests.log`, `/tmp/magicpaper-usage-native-gate.log`,
`/tmp/magicpaper-usage-media-tests.log`, `/tmp/magicpaper-usage-consumer-tests.log`.
Core AI API 8/8 и impl 122/122 PASS (новые `UsageMachineTest` 8,
`UsageJournalTest` 17: state×input, late capture, mutable input, stale owner,
private payload, malformed revisions, reset race, exact lost ACK/cancellation,
reopen без model replay); media impl 25/25; Settings consumers 12/12;
app consumers 20/20 PASS. Native Usage consumers
`MeteredRuntimeUsageTest`, `CodingRuntimeGraphTest`, `PlanningRuntimeIntegrationTest`
PASS. Совместный runtime фильтр 39 тестов содержит один соседний новый
`PlanningNativeRecoveryIntegrationTest` fixture mismatch: expected empty
argumentFingerprint, actual canonical SHA256 при неизменном UNKNOWN receipt;
это передано native owner и не объявляется baseline или успешным тестом.
Planning native API 7/7 PASS. Изменений layout/рендера нет; platform/full JVM
проверки нового Usage среза ещё не выполнены.

Следующий объединённый owner gate подтверждён отдельно:
`/tmp/magicpaper-seventh-combined-owners-results/summary.json`, 522 теста,
0 отказов, 11 opt-in пропусков. Core AI API 8/8 и impl 123/123 PASS;
`UsageJournalTest` теперь 18 сценариев, включая
`submittedImportFreezesCallerCollectionsBeforeWaitingForAnotherWriter`:
replace фиксирует caller collections до ожидания другого writer. Settings API
8/8 и impl 82/82 PASS; все 6 `SettingsProviderOperationsTest` подтверждают
sanitized errors/cancellation, stale deleted-profile catalog, captured description
refs/judge variant и late login после logout. Последующие runtime/app проверки
в этом acceptance ещё не отражены.

### Седьмой срез: граница применения native settings

`DefaultCodingService` получает обязательный `SettingsCommands`: выбор движка
публикуется только после подтверждения владельца настроек. Runtime participant
разделён на `prepareSettings(previous,next)` (отзыв изменяемой automation policy
до commit) и `applySettings(settings)` (распространение limits, затем configure
только при успехе). Ни runtime, ни `SessionOrganismService.applySettingsLimits`
не записывают SettingsRepository. JVM contribution разрешает CodingService лениво,
чтобы construction settings owner не замыкался через runtime на его read facade.

Read-only `runtimePolicy()` отличает Confirmed от Unconfirmed. Startup при
Unconfirmed выставляет обе automation policy OFF. Новый BeginRun и явный Enable
проверяют актуальное подтверждение; отказ до BeginRun сохраняет queued input и
показывает notice, не создаёт фиктивный run/worktree outcome. Подтверждение настроек
не запускает сохранённый запрос автоматически. Новый consumer suite
`NativeSettingsApplicationTest` содержит 5 сценариев: ACK preference, failed
preference после создания session, unknown restore с explicit retry, изменение
readiness после startup, failure распространения limits без применения ON.
App часть `/tmp/magicpaper-seventh-runtime-app.log` завершена: новый suite 5/5,
`AutomationPolicyTest` 2/2 и `ModelSettingsServiceTest` 6/6 PASS; весь app 235
тестов с одним ранее известным RequestPin отказом. Результаты обновлённых
`SessionLimitPolicyRaceTest` и `SessionRuntimeLimitsTest` сохранены в
`/tmp/magicpaper-seventh-runtime-app-results`; их XML не содержит отказов.

Следующая узкая правка закрывает найденную гонку readonly check → manual Enable:
`ComputerPolicyRef(incarnation,revision)` захватывается до чтения Settings policy;
Enable требует этот ref и возвращает успех лишь для ещё действующего grant.
Prepare всегда синхронно инвалидирует policy и отзывает grant/permission; Configure
теперь отзывает и pending permission при одинаковых OFF/OFF. OS await не держит
settings lock. Отмена до ACK получает exact projected lease из внутреннего
admission receipt и отзывает только его. Старые Configure/Enable остаются
replay-only Intent variants: live reducer их отклоняет; старые IDs/lease generation
читаются без запуска эффектов. Повторный gate после review guards (usable capture,
required configure, Intent/Fact hierarchy) PASS:
`/tmp/magicpaper-seventh-recovery-computer-retry.log`. Computer API 16/16;
impl 80 тестов, 0 отказов, 6 opt-in пропусков; выбранные runtime 246/246;
app 8/8 (`NativeSettingsApplicationTest` 6, `AutomationPolicyTest` 2).
Новые `ComputerPolicyTest` (5) и `ComputerPolicyJournalTest` (6) входят в этот
результат. Проверены exact capture before settings read, delayed ACK, отмена до
ACK, OFF/OFF при pending OS permission, legacy live rejection/replay и reset
incarnation. `GenericNativeRuntimeTest` 6 и `CodingClientComputerUseTest` 2 также
PASS. Layout не менялся; нативные opt-in OS проверки отдельно не запускались.

Точка линеаризации — синхронный prepare invalidation до config commit, не более
раннее принятие pending ChangeSettings. Уже допущенная permission может быть
in-flight, но invalidation отзывает её и поздний ответ не возвращает grant.
Native Begin после длительного preflight пока не принимает policy ref; его
эпhemeral host context согласован со следующим reserve/activate срезом backend.
Полная атомарность model dispatch относительно всех settings изменений здесь
не заявляется.

### Седьмой срез: настройки, Git authority и промежуточная приёмка

SettingsMachine и DefaultSettingsConfiguration владеют настройками, профилями и
описаниями моделей. Репозитории этих значений доступны другим модулям только для
чтения; planning больше не пишет и не очищает model-dossiers. Private input journal
проверяет точный префикс, epoch, immutable payload и ACK/readback. Секреты хранятся
отдельно, журнал содержит opaque references. Старые ключи остаются совместимым
кэшем и импортируются только в virgin journal. Restore не повторяет provider calls
или применение runtime policy. Подготовка политики предшествует configuration
commit; применение подтверждается отдельным фактом. Неизвестный исход сохраняет
точный незавершённый выбор для явного retry. Поздние ответы каталога и описания
привязаны к версиям профиля, настроек и описания; удалённый профиль не воскрешается.

WorkspaceOperation теперь несёт настоящий WorkspaceLease и operation ID.
GitPlanningWorkspace сериализует операции одного lease и регистрирует CheckRef
до передачи команды checks. Git metadata также исполняется через дочерние owned
checks. Журнал учитывает все затронутые ресурсы, включая ещё не созданную рабочую
копию и общий Git metadata каталог. UNKNOWN блокирует новый доступ через любой
из этих путей. Отказ проверки до Submit получает положительный PreparationRejected;
отсутствие записи или процесса доказательством отказа не считается. Сброс и close
ждут завершения точно зарегистрированных вызовов, включая отменённый вызов до
входа в dispatcher, прежде чем читать и очищать журнал.

Native release authorization сохраняется до родительского reconciliation.
Организм проверяет точную session generation, полный journal prefix и сохранённые
native ACK/releases, затем снимает только соответствующий quarantine. Tool receipt
UNKNOWN при этом остаётся UNKNOWN: явное пользовательское разрешение не превращается
в доказанное завершение инструмента. Реальная integration проверяет этот порядок.

Owner gate /tmp/magicpaper-seventh-combined-owners.log: 522 теста, 0 failures/errors,
11 native opt-in skip; архив /tmp/magicpaper-seventh-combined-owners-results/summary.json.
После дополнительных cancellation/reset и queued-input проверок settings/checks
повторены: /tmp/magicpaper-seventh-settings-checks-final.log, соответствующий
*-results/summary.json — 187 тестов, 0 failures/errors, 11 skip. Settings API 8/8,
impl 83/83; checks API 18/18, impl 78 (11 skip). Usage impl 123/123 включает заморозку
импортируемых коллекций до ожидания другого writer; plugins impl 25/25 включает
сохранение CancellationException при повреждённом lost-ACK readback.

Первый runtime/app gate /tmp/magicpaper-seventh-runtime-app.log: app 235 с единственным
исходным RequestPin failure; runtime 1199, 14 новых failures, 1 skip. Архив
/tmp/magicpaper-seventh-runtime-app-results/summary.json. Среди новых отказов 13
связаны с тестовыми адаптерами, не передававшими существующий lease или смешивавшими
isolated и direct-folder пути. Их исправления сохраняют проверки identity и исходные
assertions. Ещё один тест выявил реальный вызов движка после смены readiness во время
prepareAttempt: перед dispatch добавлена повторная проверка, пока локальный receipt
ещё доказывает отсутствие вызова. Все 14 failures устранены и проходят в общем
checkpoint ниже; они не baseline. В том же прогоне PlanningNativeRecoveryIntegrationTest 13/13,
PlanningJournalRecoveryTest 6/6 и GitWorkspaceProcessOwnershipTest 5/5 PASS.
Conditional Computer Enable принят отдельным owner gate выше; native
reserve/activate handshake остаётся незавершённой границей.

#### Markdown table: ожидание асинхронной композиции

Седьмой полный checkpoint впервые выявил
`PaperMarkdownTableTest.chatMarkdownTablesWrapCellsInsteadOfEllipsizing`: первая
ячейка ещё отсутствовала в semantics. Это путь `PaperMarkdown(String)` через
M3 Markdown 0.41.0: начальное состояние Loading, `parse()` на Dispatchers.Default,
затем публикация Success через StateFlow. Два кадра с искусственными timestamps
не гарантировали завершение разбора. `PaperMarkdownBody` получал уже разобранный
документ, поэтому соседний сценарий проходил.

Тест теперь ждёт фактическую CollectionInfo(3,4), максимум 15 секунд. Все проверки
единственного экземпляра каждой ячейки, точного текста, отсутствия visual overflow
и многострочного переноса сохранены. Timeout сохраняет наблюдаемые semantics и PNG;
изменений production UI нет. Focused gate
`/tmp/magicpaper-seventh-markdown-table.log`: 2/2 PASS. Оба фактических JVM-рендера
720×640 осмотрены: `designSystem/build/reports/markdown-tables/chat.png` и
`designSystem/build/reports/markdown-tables/document.png`; таблицы содержат полный
текст с переносом. Фон fixture прозрачный: это проверка layout, не приёмка контраста
или установленного desktop-приложения. Повтор общего JVM checkpoint также проходит
этот тест.

#### Седьмой общий checkpoint

Полный JVM/platform прогон `/tmp/magicpaper-target-seventh-checkpoint.log`:
3147 JVM-тестов, 3 failures, 0 errors, 24 skip. Третий отказ — гонка Markdown
fixture выше. После исправления общий `jvmTest --continue` оставил только два
исходных RequestPin/TreeHeader отказа: 3147 тестов, 2 failures, 0 errors, 24 skip.
Авторитетный архив `/tmp/magicpaper-target-seventh-repeat-results/summary.json`,
лог `/tmp/magicpaper-target-seventh-repeat.log`. Runtime 1199 (1 skip), app 236
(1 baseline failure), settings 8+83, core AI 8+123, checks 18+78 (11 skip),
computer 16+80 (6 skip), workspace 8+22, planning 87+36, plugins 3+25,
skills 147 (2 skip), storage impl 57. Новых отказов нет.

`compileMigrationTargets`, Android host, desktop host, Pi Node protocol и computer
screenshot context проходят в полном прогоне; androidApp unit task NO-SOURCE.
Architecture/Paper self-tests PASS (`/tmp/magicpaper-seventh-final-architecture.log`,
`/tmp/magicpaper-seventh-final-paper.log`). Установленные OS/native provider и
IndexedDB cross-tab сценарии не проверялись. Следующий срез начинается после этого
checkpoint: skills owner, durable storage reset fence, shared Task Git authority,
native reservation before host preflight. Его scratch-проекты в результат не входят.

### Восьмой срез: библиотека навыков, барьер сброса и Task Git

Common библиотека навыков переведена на единственный `SkillStore` и чистую
`SkillMachine` в API. Репозиторий теперь только читает; установка, изменение
состояния, удаление, импорт и очистка проходят через `SkillCommands`. Захваченные
`SkillRef`, basis имени и revision каталога исключают позднюю перезапись, повторное
использование удалённого ID и возвращение удалённых навыков из старого file picker.
Входные коллекции фиксируются до первого ожидания; выдаваемые значения отделены от
внутреннего состояния. Журнал `skill-library` хранит проверяемые input references,
private payload связан с owner, epoch, ID и SHA-256. Каждый authoritative read
повторно проверяет prefix и payload; повреждение не выдаётся за пустую библиотеку.
Старый ключ `skills` импортируется только в virgin journal, затем служит совместимым
кэшем. Ошибка этого кэша после commit остаётся видимой, но не отменяет подтверждённый
результат. Restore не повторяет установку или provider request.

Оба common plugins используют захваченные refs, показывают явную ошибку владельца
и действие «Обновить библиотеку». Self-education сохраняет draft после неудачи;
ошибка провайдера больше не подменяется успешной эвристической заготовкой.
`CommonSkillsPreviews` (группа `Common skills`) покрывает catalog/education:
обычный, пустой, loading, error, narrow и large text. Реальные Compose renders
осмотрены в `feature/skills/impl/build/reports/common-skills/`: обе поверхности
при ширине 360, 480 (scale 1.6) и 900, а также default/empty/loading. `CommonSkillsOwnerUiTest`
проверяет сохранение списка, запрет мутаций при unknown, явный reload и отсутствие
provider calls в начальных состояниях. Контраст с фактическим FFFDFA: основной текст
14.35:1, вторичный 6.30:1, action 6.46:1. Это JVM render/semantics; установленное
приложение, OS input и FPS не проверены. Package/experience и draft редактора пока
остаются следующими ветвями того же владельца, не объявлены мигрированными.

Durable storage reset теперь сначала записывает и точно подтверждает отдельный
receipt RESETTING. CONTROL store не очищается; READY появляется только после
подтверждения всех очисток. EventJournal, drafts и navigation используют тот же
lock и отклоняют операции, пока reset не завершён. Повторный запуск не продолжает
очистку автоматически; явный retry использует прежнюю admission identity. Отмена
остаётся первичной даже при подтверждённом lost ACK. IndexedDB schema 4 добавляет
CONTROL без удаления legacy stores; проверена миграция v2/v3. Это локальный барьер
PersistenceStores, не общая атомарная транзакция с KV, media и native owners; отсутствие
receipt в старом формате не доказывает исход исторического частичного сброса.

GitTaskWorkspace и GitPlanningWorkspace используют один `GitWorkspaceAuthority`,
одни process/check registrations и точные WorkspaceLease. Task operation строится
из durable Pending владельца. OPEN и DELIVER требуют source+execution leases;
заимствованный planning lease не освобождается Task-адаптером. Git metadata также
идёт через owned checks; raw ProcessBuilder удалён из Task Git. Под блокировкой
повторно проверяются root/branch/HEAD/clean source, включая вход через подкаталог.
UNKNOWN дочернего check блокирует переиспользование всех resource aliases.
Проверка разрешённых read queries допускает легальные имена вроде `release+fix`,
но отклоняет options и выражения refs. Положительное доказательство очистки после
UNKNOWN здесь не выдумывается: отдельный recovery protocol ещё нужен.

Owner evidence: `/tmp/magicpaper-eighth-owners-results/summary.json` — 313 тестов,
0 failures/errors, 2 opt-in skip (skills API 6, impl 173, settings impl 84,
checks API 20, workspace API 8/impl 22). Следующий focused skills gate
`/tmp/magicpaper-eighth-skills-final.log` проходит; полный skills suite уже содержит
174 теста, включая второй UI case. Storage JVM API 2/impl 71 PASS;
`/tmp/magicpaper-eighth-storage-results/summary.json` также содержит **реальные**
Chromium JS 68/68 и Wasm 68/68 browser tests. Первичная попытка Karma не нашла
Google Chrome; повтор с установленным Chromium прошёл. Task Git gate
`/tmp/magicpaper-eighth-git-results/summary.json`: runtime 178/178 и app worktree
22/22 PASS. Architecture/Paper self-tests PASS.

Первый общий checkpoint `/tmp/magicpaper-target-eighth-checkpoint.log`:
3204 JVM-теста, 3 failures, 0 errors, 24 skip. Архив
`/tmp/magicpaper-target-eighth-results/summary.json`. Все migration platform,
Android host, desktop host и Node targets проходят; androidApp — NO-SOURCE.
Два отказа исходные RequestPin/TreeHeader. Третий — **новая найденная ошибка**
`NativeRuntimeDispatchTest`: частичный Notice теряется при исключении
NativeRunRecoveryRequired из-за отмены промежуточной очереди. Исправление доставки
и проверка всех затронутых transport boundaries выполняются отдельно; этот отказ
не принят в baseline и этот checkpoint не означает готовность среза.

#### Упорядоченная доставка native output

Ненужные channelFlow заменены синхронной эмиссией в отдельном coroutineScope:
JournaledBackendAgent, GenericNativeRuntime, interaction observer, wake guard и
Codex contribution. Отдельный child job сохраняет адресную отмену native executor,
не отменяя coroutine вызывающего владельца. На необходимых dispatcher boundaries
runtime передаёт operational failure как внутренний terminal envelope и бросает
тот же typed error после предшествующих событий. Pi/Codex callback channels и
research progress завершаются через close(cause) после cleanup; обычный throw из
producer отменял очередь. CancellationException не ставится в очередь как результат
и продолжает немедленно отменять работу. UNKNOWN не превращается в Failed+Finished.

`/tmp/magicpaper-eighth-output-delivery-results/summary.json`: 165 тестов,
0 failures/errors, 1 opt-in skip. Runtime focused 17/17, lifecycle 28/28,
Pi 82 (1 skip), Codex 38/38. Проверены медленный collector, все три native режима,
точный recovery snapshot/cause, interruption, resource close и failure после
SessionStarted через контролируемый Codex RPC transport. Живые провайдеры/ключи
не использовались. Первый focused запуск выявил только Kotlin type inference в
новых fixtures; второй — stacktrace-copy обычного IllegalStateException. Последний
fixture использует настоящий NativeRunRecoveryRequired с exact evidence; assertions
порядка, identity и отмены сохранены. Это не новые baseline failures.


#### Восьмой общий checkpoint после исправления

Общий JVM/platform повтор `/tmp/magicpaper-target-eighth-repeat.log` завершён:
3214 JVM-тестов, 2 failures, 0 errors, 24 skip. Архив
`/tmp/magicpaper-target-eighth-repeat-results/summary.json`. Только исходные
RequestPin/TreeHeader; все новые failures промежуточных прогонов устранены.
Runtime 1209 (1 skip), native lifecycle 28/28, Pi 82 (1 skip), Codex 38/38,
skills API 6/6 и impl 174 (2 skip), settings impl 84/84, storage impl 71/71,
checks API 20/20. Полный общий запуск проверяет sibling compile closures.

Migration platform compilation, Android/desktop host targets, Pi Node protocol
и computer screenshot context PASS; androidApp unit task NO-SOURCE. Architecture
и Paper self-tests PASS: `/tmp/magicpaper-eighth-final-architecture.log`,
`/tmp/magicpaper-eighth-final-paper.log`. Отдельный реальный browser gate остаётся
JS 68/68 + Wasm 68/68. Installed native platforms/providers и FPS не проверялись.

Следующая работа отделена от этого checkpoint: native reserve/activate до host
setup и точная parent binding, package/experience ветви того же SkillMachine,
draft/navigation owners, global reset/import coordinator, закрытие planning writer
и UNKNOWN checks recovery. Подготовленные scratch contracts/API/tests ещё не
интегрированы и не объявлены прошедшими компиляцию. Для draft owner предварительно
проверены существующие ключи, secret/blob adapter, версии edit/clear и границы
жизни actor; никаких draft/navigation исходников в восьмом срезе не изменено.

### Девятый срез: черновики и навигация

`DraftSession` перестал быть и правилом, и исполнителем. Решения — какую версию получает
правка, какую ревизию тратит запись, применима ли ещё очистка, чем кончилась запись —
перешли в чистую `DraftMachine` в `:core:storage:api`. Исполнитель выполняет её эффекты
(`Schedule`, `Write`, `Delete`, `RetryCleanup`) и возвращает факты. Публичная поверхность
сохранена: `update`, `retry`, `awaitSaved`, `clearIfUnchanged`, `revoke` и поля
`value/version/loaded/saving/error` читаются потребителями по-прежнему; `DraftSessionState`
теперь проекция состояния машины с `internal constructor`. Каждый переход проходит один
compare-and-set: правка приходит с UI-диспетчера, пока цикл команд сообщает durable-факты,
и оба обязаны менять то состояние, которое прочитали.

Неизвестный исход отделён от уведомления: подтверждённая запись с неподтверждённой
очисткой живёт в собственном поле состояния, которое следующее нажатие клавиши не стирает.
Прежний код держал её в том же поле, что и ошибку, и очередная правка молча уносила и
повод для повторной очистки; теперь снять её может только подтверждённый повтор самой
очистки, а доказанная запись более новой версии за неё не считается.

Журнала входов у владельца черновиков нет, и это решение, а не пропуск: durable-запись уже
является проекцией своих входов, упорядоченной монотонной ревизией с tombstone, а автосейв
идёт в темпе набора текста — журнал рос бы без границы, переописывая то, что запись и так
утверждает. Неизвестный исход при этом есть отдельным состоянием: подтверждённая запись
с неподтверждённой очисткой остаётся `unknown`, `Intent.Cleanup` — единственный путь к
`RetryCleanup`, и никакой переход не выдаёт её за завершённую. Потерянное подтверждение
самой записи по-прежнему разрешается точной перечиткой, а не догадкой.

`DraftMachineTest` — 8 тестов, включая таблицу 11 состояний × 11 входов, где каждая
ячейка «0» дополнительно требует неизменного состояния. Отдельно проверены: чужое
generation и чужой reset epoch не тратят ревизию; правка до гидратации сохраняет свою
версию, а restore не понижает уже израсходованную ревизию; устаревшая запись пропускается,
а поздняя квитанция старой версии не отменяет доказанную новую; очистка применяется только
к своей версии, и текст, набранный во время удаления, переживает tombstone; отозванный
writer не правит, не чистит и не тратит ревизию. Прежние проверки сохранены целиком:
`:core:storage:api` 10 тестов, `:core:storage:impl` 71 тест, включая слабое место
«successfulSendDoesNotClearNewerInput» и «typingDuringSlowClearIsSavedAfterTombstone».
Одно отличие поведения принято намеренно: отказ восстановления теперь снимает индикатор
сохранения, а не оставляет его включённым навсегда.

Навигация получила собственный модуль `:magic-common:navigation:api`: маршруты, журнал
визитов и `NavigationMachine`. Группа выбрана ради проверки, а не ради каталога: правило
`magic-common` требует сохранения JVM, Android, JS и Wasm, и это единственное место, где
охват платформ навигации назван проверкой. В `:core:` такого правила нет, и потеря
web-цели обнаружилась бы только сборкой приложения целиком. Kotlin-пакет `io.aequicor.magicpaper.navigation` сохранён,
поэтому переезд — правка build-файлов, а не миграция сохранённых снимков. Оболочка `:app`
осталась единственным исполнителем: Decompose-роутинг, Compose-презентация и мост истории
браузера не переехали и остаются адаптерами. Конкурирующего routing owner не появилось.

Переход теперь предлагается прежде, чем взят: построение экрана может упасть, и оболочка,
успевшая продвинуться, показала бы то, что не смогла собрать. `State.pending` — этот шаг
в работе, и только `Fact.Projected` превращает кандидата в историю, достойную записи.
Неудача построения откатывает роутер и не пишет ничего. Неудача восстановления оставляет
durable-журнал неизвестным: машина продолжает работать в памяти, но отказывает в каждой
записи до явного `Intent.Reset`, а причина отказа перевешивает любое более позднее
уведомление. Идентичность визита, как и всякий новый id, приходит значением входа.

Журнала входов у навигации тоже нет, и по той же причине: презентация визита пишется
в темпе прокрутки, поэтому журнал каждого входа рос бы без границы, переописывая снимок,
который и так является проекцией. Неизвестный исход при этом назван отдельно. Нечитаемая
история — один такой исход: машина продолжает работать в памяти и отказывает в каждой
записи до явного `Reset`. Подтверждённый снимок, чьё прежнее состояние не было удалено, —
второй: уведомление о такой записи обычное и его может сменить следующее, а сам факт —
нет. Снять его может только подтверждённая запись, потому что только она собирает
осиротевшие payload'ы; ни закрытие уведомления, ни переход, ни reset доказательством
не считаются. Чужой journal id не даёт этому окну уведомления, но осиротевшее состояние
принадлежит хранилищу, а не окну, и учитывается всё равно.

`NavigationMachineTest` — 8 тестов, включая таблицу 8 состояний × 14 входов, в которой
буква ячейки — основной эффект (Project, Save, Open/Dismiss dialog, Reject, нет), так что
таблица фиксирует не только допустимость, но и происшедшее. Проверены отложенные ссылки
(порядок, собственный визит каждой, отказ при нехватке идентификаторов, отсутствие
повторной постановки), запрет дальнейших входов во время шага в работе, сравнение диалога
по ссылке — завершившаяся модаль не закрывает равный маршрут, переоткрытый преемником, —
и чужой journal id при неудачной записи. Отдельная проверка отделяет
не состоявшуюся запись от подтверждённой с неизвестной очисткой. Существующие проверки оболочки не ослаблены и
проходят без изменений: `RootComponentTest` 13, `RootDialogLifecycleTest` 4,
`BrowserHistoryBridgeTest` 7, `ReferencedNavigationSnapshotStoreTest` 4, `AppRootHostTest` 1,
`BrowserNavigationSessionTest` 3, `VisitPresentation*` 14.

#### Девятый общий checkpoint

Полный `./gradlew jvmTest --continue`: 3230 JVM-тестов, 2 failures, 0 errors, 24 skip.
Авторитетный лог `/tmp/magicpaper-ninth/checkpoint3.log`. Ему предшествуют два прогона
с тем же результатом: `checkpoint.log` (3228) до того, как неизвестный исход черновика
был отделён от уведомления, и `checkpoint2.log` (3229) до того, как неизвестный исход
получила запись навигации. Ни один из трёх не дал нового отказа. Оба отказа — исходные
`RequestPinViewModelTest.chatAnswerUsesOverrideButPinsUseOperationalDefaultAndOldChatsAreLazy`
и `PaperTreeGroupHeaderTest.narrowHeaderRetainsDisclosureStatusAndFullTitleAtEveryTextScale`
из раздела долгов; новых отказов нет. Прирост к восьмому checkpoint — ровно 14 новых
тестов двух таблиц и два регрессионных на неизвестный исход — по одному на владельца. Счёт снят по XML всех модулей; отдельная сборка
`tools/mission-visualization` в него не входит и в этом прогоне не участвовала.

`compileMigrationTargets` проходит: Desktop, Android, JS и Wasm, включая новый общий
модуль навигации. Architecture и Paper self-tests PASS. Карты обновлены: `MODULES.md`
и `agent-workflows/CODEMAP.md` указывают на нового владельца навигации.

Не проверялось: установленные платформы, живые провайдеры, OS-ввод и FPS; отдельные
browser-тесты JS/Wasm в этом прогоне не запускались; визуальной приёмки срез не требовал —
ни одна поверхность не менялась.

Следующая работа: native reserve/activate до host setup и точная parent binding,
package/experience ветви того же `SkillMachine`, общий координатор reset/import,
закрытие planning writer и recovery для UNKNOWN checks.

### Десятый срез: общий контракт машин

`:core:state-machine:api` закрывает открытый вопрос `TARGET-ARCHITECTURE.md` про супертип
`Machine<S, I, F>`. Обобщены значения, а не наследование: `MachineId`, `PhaseId`, `InputId`,
`EffectId`, `Branch`, `Step` и `StateSpace`. Модуль — лист без единой project-зависимости,
чтобы позже его смогли взять `:core:model` и `:backend-agents:api`, которым верификатор
разрешает зависеть только на модули такого рода.

Принятие стоит владельцу три строки: `override val id`, `override val space` и мост
`override fun step(state, input) = reduce(state, input).let { Step(it.state, it.effects) }`.
Вложенный `Transition`, сам `reduce` и все их вызовы не трогаются — поэтому срез не
превращается в правку модуля целиком и не задевает сериализованные формы. Имя `Step` выбрано
именно ради этого: `Transition` внутри каждой машины уже занято.

`StateSpace` объявляет только невычислимое: позиции, входы с ветвью Intent/Fact, эффекты,
матрицу принятия и `label(state)`, говорящую, какой позиции равнозначно состояние. Цели
переходов, испускаемые эффекты и рёбра диаграммы выводятся прогоном чистого `reduce` по
образцам. Объявленной колонки «целевая позиция» нет намеренно: она стала бы вторым
источником правды и разошлась бы с кодом.

Образцы состояний и входов лежат в `commonTest` владельца, а не в api. Требование «смотря
только на api восстанавливать всё множество состояний» выполняется именами позиций и
матрицей; фикстуры в поставляемый бинарник, включая web-сборку `magic-common`, не уезжают.

Харнесс `testSupport/statemachine/StateSpaceHarness.kt` проверяет то, чего рукописные
таблицы не могли: **замкнутость** — принятый переход обязан привести в позицию, которую api
называет, иначе печатается недостающее состояние. Плюс инъективность `label` (грубое
схлопывание падает сразу), достижимость каждой позиции, наличие позиции неизвестного исхода
и неизменность состояния при отказе.

Что конструкция не доказывает, и это записано в `StateSpace.label`: `label` — объявленная
абстракция, а не бисимуляция. У `DraftMachine` состояние несёт счётчик, а `Intent.Clear`
сравнивает `expectedVersion` с `state.version`, поэтому принятие не является функцией никакой
конечной абстракции. Матрица говорит об образцах; отказы по идентичности остаются в
`DraftMachineTest.anotherWriterOfTheSameKeyIsRefusedByGenerationAndByReset`.

Переведены два владельца. `DraftMachine` выбран как самый трудный случай — у него нет
enum-фазы, — и объявил девять позиций там, где его тест перечислял одиннадцать состояний:
`edited` и `writing` различала только потраченная ревизия, а второй `saved` — только версия.
Позиция не есть полезная нагрузка. `NavigationMachine` сохранил свою таблицу букв ведущего
эффекта нетронутой и получил матрицу рядом; в ней появились четыре входа, которых та таблица
никогда не перечисляла: `Report`, `ClearError`, `Restored` и `ProjectionFailed`.

`:core:state-machine:impl` разрешает объявление в `MachineProjection` со стёртыми типами —
`Machine<S, I, E>` инвариантен, и `List<Machine<*, *, *>>` шагнуть нельзя — и рендерит
Mermaid и страницу с матрицей. Журнальный адаптер сюда не кладётся: его потребители это
`impl` владельцев, которым запрещена зависимость на чужой `impl`, поэтому он должен встать
рядом с `EventJournal` в `:core:storage:api`. Это следующий срез.

`EFFECT_SUFFIX` верификатора расширен суффиксами `Intent` и `Fact`: журналируемый вход
обязан быть данными ровно по той же причине, что и эффект — запись с лямбдой не проигрывается.
На текущем дереве правило даёт ноль нарушений, случай добавлен в `--self-test`.

#### Десятый общий checkpoint

Полный `./gradlew jvmTest --continue --rerun-tasks`: **3237 JVM-тестов, 3 отказа, 0 ошибок,
24 пропущено**, 50 задач `jvmTest` выполнено и ни одной `UP-TO-DATE`. Первый прогон без
`--rerun-tasks` дал те же отказы, но 24 модуля пришли из кэша, поэтому в базу он не годится.
Авторитетный лог `/tmp/.../full2.log`, 4 м 25 с.

Два отказа — исходные `RequestPinViewModelTest.chatAnswerUsesOverrideButPinsUse...` и
`PaperTreeGroupHeaderTest.narrowHeaderRetainsDisclosureStatus...`. Третий —
`PaperSemanticsTest.contextIndicatorExposesProgressAndSupportsKeyboard`, уже описанный в
«Долгах» как чувствительный к нагрузке и в базе не числящийся; отдельный повтор класса
даёт 9 тестов без отказов. Assertions не ослаблялись.

Прирост к девятому checkpoint — пять новых тестов: `DraftSpaceTest`, `NavigationSpaceTest`
и три `RenderTest`. Разница с записанными ранее 3230 составляет семь, то есть два теста
пришли не из этого среза; счёт снят по XML всех модулей и приводится как есть.

Проверки владельцев: `:core:storage:api` 11, `:magic-common:navigation:api` 11,
`:core:state-machine:impl` 3, навигационные проверки оболочки `:app` 25 — без отказов.
Сборка `:core:state-machine:api/impl`, `:core:storage:api` и `:magic-common:navigation:api`
под JVM, Android, JS и Wasm выполнена с `--rerun-tasks`. Architecture self-test PASS.

Не проверялось: установленные платформы, живые провайдеры, отдельные browser-тесты JS/Wasm
и FPS. Ни одна поверхность не менялась, визуальной приёмки срез не требовал.
