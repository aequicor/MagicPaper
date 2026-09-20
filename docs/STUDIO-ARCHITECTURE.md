# Целевая архитектура студии

Замысел: детерминированное ядро с недетерминированными органами. Скелет — обычный код,
принимающий решения по явным правилам; модель отвечает только там, где нужна догадка,
и всегда значением из закрытого множества, а не свободной командой.

Документ описывает, куда движется исполнение сессий. Текущие границы модулей —
в [MODULES.md](MODULES.md), владельцы и проверки — в
[CODEMAP.md](agent-workflows/CODEMAP.md) и [VERIFICATION.md](agent-workflows/VERIFICATION.md).

## Что уже стоит

- **Три режима с правами, навязанными кодом.** `CodingInteractionMode.CODE/RESEARCH/PLANNING`;
  `changeInteractionMode` запрещает смену на ходу, `ToolDefinition.allowed` решает, какой
  инструмент доступен в каком режиме и по чьим полномочиям. Это правило, а не просьба к модели.
- **Зигота и исполнители.** `CodingSessionRole.ORCHESTRATOR/WORKER`, `SessionTreeRuntime`,
  `OrchestrationService`; у каждой дочерней сессии свой контекст.
- **Судья со свежим контекстом.** `MilestoneVerifier.verify(milestone, goal, report, profile)`
  получает пакет улик, а не историю диалога, и решает с нуля на каждом вызове.
- **Изоляция исполнения.** `GitPlanningWorkspace`, worktree попыток, манифест переноса,
  сверка процессов по PID и владельцу.
- **Разделение платформ.** `:feature:coding:impl` объявляет только jvm-цель; Android и браузер
  его не компилируют. Хосты без агента связывают `UnavailableCodingFeature`.

## Чего не хватает

### Детерминированная стейт-машина

Решения размазаны: 31 enum фаз и статусов, около 124 мест, где фаза меняется через `copy(...)`.
`StageAttempt` ([DecisionTree.kt](../core/model/src/commonMain/kotlin/io/aequicor/magicpaper/domain/DecisionTree.kt))
— мешок флагов: `phase`, `mergePhase`, `awaitingPlanner`, `coordinationPending`, `waitingForUser`,
`waitingForEvent`, `interrupted` и три счётчика повторов. Легальность их сочетаний держится на
`require` и `if`, разбросанных по `PlanningExecutionService` и `OrchestrationService`.

Цель — чистая функция в `core:model`, рядом с доменом:

```
sealed interface StageEvent    // Prepared, Started, WorkerTurnEnded, PlannerDecided,
                               // VerificationPassed/Failed, MergeStarted/Succeeded/Failed,
                               // UserAnswered, EventFired, TransportFailed, Interrupted,
                               // RetryAuthorized, NativeEvidence, UserConfirmedOutcome
sealed interface StageEffect   // RunWorker, RunVerifier, RunMerge, AskUser, WaitForEvent,
                               // RecordJournal, Quarantine, Finish
fun reduce(state: StageState, event: StageEvent): StageTransition   // без Clock, Random, корутин
```

Первый срез — `StageAttempt`, не `OrchestrationService`: у него замкнутое множество писателей.
Сериализуемая форма в первом срезе не меняется; два адаптера держат формат на диске
байт-идентичным, чтобы не понадобилась миграция данных.

Отдельный модуль `:core:machine` не заводить: `reduce` — это около тридцати строк каркаса,
а State/Event/Effect суть доменные типы.

Работа идёт срезами: решение за решением уходит из службы в чистую функцию `core:model`,
каждый раз под полным набором тестов. Полный `reduce` появится, когда решений в службе
не останется. Уже вынесены:

| Что | Где | Что было |
| --- | --- | --- |
| Операции журнала плана | `PlanJournalOperation` | свободная строка, читаемая сравнением |
| Чего ждёт попытка | `StageWaiting` | четыре независимых поля, сочетания на `if` |
| Как возобновить попытку | `StageResumption` | порядок проверок, державшийся на порядке строк |
| Что значит событие движка | `StageEngineSignal`, `StageAttempt.after`, `StageRunResult` | четыре копии одного `when`, разошедшиеся в мелочах |
| Повторять ли сбой | `RetryDecision`, `PlanningRetryPolicy.decide` | четыре написания одного решения; часы и случайность внутри службы |

### Журнал событий

`PlanJournalEntry` уже существует, но `operation` — свободная строка, которую читают сравнением
(`PlanningRetryCheckpoint`, `PlanningBlocker`). Порядок работ:

1. Закрыть множество `operation` перечислением. Независимо полезно и проверяемо.
2. `EventJournal` в `:core:storage:api` и `JsonEventJournal` в `impl`, член в `PersistenceStores`.
   Append-only, монотонный seq, **запись намерения эффекта до исполнения**: `effect-requested`
   и `effect-settled(outcome)` двумя записями. Отдельный модуль `:core:journal` не нужен — он
   продублировал бы жизненный цикл сброса, которым владеет `PersistenceStores`.
3. Перевод `Plan` в проекцию журнала меняет CAS-контракт `PlanningStore`. Самый рискованный
   шаг; делать последним и не вместе с другой сменой инварианта.

### Восстановление — не чистый replay

Native-движок может исполнить `file.edit` или `shell.exec` мимо `ToolExecutor`, и приложение
может так и не узнать исход: для этого существуют `ToolHost.unknownOutcome`,
`SessionQuarantineRecovery` и `reconcileCodingQuarantine(sessionId, confirmed)`.

Поэтому replay журнала, заканчивающегося `effect-requested` без `effect-settled`, обязан давать
состояние `Unknown`, из которого есть ровно два выхода: `NativeEvidence(receipt)` от
`NativeReceiptRecovery` и `UserConfirmedOutcome` от человека. Никогда по таймауту, никогда
повтором. Неизвестный исход — это состояние машины, а не флаг.

### Реактивность

UI — проекция состояния. Компонент читает одно состояние и отправляет действия; исполнение,
черновики и фоновые задачи принадлежат службам, переживающим экран. Проекции — чистые функции,
которые тестируются подачей списка событий и сверкой снимка, без моков служб.

### Классификатор стратегий

Строится последним, когда в журнале накопится статистика: детерминированный детектор считает
метрики → модель возвращает метку причины из закрытого перечня → выбор из заранее одобренной
палитры → исход обратно в журнал. Свободное действие модель не возвращает никогда.

Для судьи и классификатора достаточно `core:ai:api LlmGateway`. Сторонний LLM-фреймворк имеет
смысл только когда понадобятся цепочки с вызовом функций на всех платформах — тогда же станет
возможен общий прямой путь для чата (см. KDoc `ChatBackend`). JVM-only библиотека сломает
`commonMain`, где живут js, wasmJs и android.

## Чего не делать

- Не разделять `:app`: это композиционный корень нужного размера.
- Не переносить `ToolHost` в модуль инструментов — его надо удалить в пользу конструкторной
  инъекции, а не переселить: тринадцать изменяемых портов и есть проблема.
- Не делать `OrchestrationService` первым срезом стейт-машины.
- Не менять сериализуемую форму `StageAttempt` в одном шаге с редьюсером.
