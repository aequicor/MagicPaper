# План: нативный выбор моделей для кодинг-сессий

> Статус: черновик, код не менялся. Связанные документы:
> [ENGINES.md](ENGINES.md), [MODELS.md](MODELS.md), [PLAN-effort-levels.md](PLAN-effort-levels.md),
> [PLANNING.md](PLANNING.md).

Принцип: чат, самообучение и описания моделей остаются на нашем резолвере
(`ProfileResolver`) и эвристике `ModelDefaults`. Для кодинг-сессий приложение
отдаёт движку (Pi, Codex) только **подключения**, а модели, уровни thinking и
значение «по умолчанию» берёт у движка как есть. Каждая фаза выпускается
отдельно, старый путь работает до паритета.

## Зачем

Выявлено при разборе текущей логики (Pi 0.84.4):

1. **«Default» значит разное.** Pi при выборе «по умолчанию» получает константу
   `DEFAULT_CODING_EFFORT = MEDIUM` (`PiModelsConfig.kt`), Codex в этом случае
   не получает поля `effort` вообще. В UI подпись одна и та же.
2. **Расхождение карты уровней с нативным каталогом Pi.** Для `qwen3.8-max`
   (провайдер `qwen-token-plan`) Pi объявляет уровни `low`, `medium`, `xhigh`
   (`high`, `max`, `minimal` — `null`). Приложение в `models.json` сессии
   (`session-configs/*/models.json`) объявляет `low`, `medium`, `high`, а
   `xhigh` скрывает; `maxTokens` 16384 против 131072 у Pi. Причина — наша
   эвристика `ModelDefaults.capability` по имени модели.
3. **Разная строгость к effort.** В обычных сессиях неподдерживаемый уровень
   молча заменяется ближайшим, а в `StageAssignment.executionProfile`
   (`PlanningWorkspace.kt`) этап падает с ошибкой.
4. **Модель и движок выбираются независимо.** Пикер не знает, какие модели
   движок реально способен запустить.

Что умеет Pi (проверено): `pi --list-models [поиск]` работает офлайн
(`PI_OFFLINE=1`) и показывает встроенный каталог провайдеров с настроенной
авторизацией; RPC `get_available_models`, `set_model`, `set_thinking_level`;
SDK `ModelRuntime.refresh(...)`; кастомные серверы обнаруживаются через
async-фабрику расширения (`fetch` `/v1/models` и `pi.registerProvider`).
`models.json` с `baseUrl` и ключом без массива `models` ничего не даёт.
Codex уже отдаёт список через `model/list` (`CodexAppServerOpenAiSubscription.models()`).

## Фаза 0. Спайк и решения (до кода)

- **Источник уровней thinking у Pi.** `--list-models` показывает только
  `thinking yes/no`, в документированном `Model` из RPC нет `thinkingLevelMap`.
  Проверить, отдаёт ли его реальный `get_available_models` или SDK
  (`ModelRuntime`). Запасной вариант: одноразовый node-скрипт, печатающий
  JSON каталога.
- **Живая проверка на Qwen.** Запустить `--thinking high` и `--thinking xhigh`
  на `qwen3.8-max` против DashScope и сравнить с нативным каталогом.
  Ключ вводит владелец профиля сам.
- **Решения:**
  - Web и Android без движка: показывать кэшированный снимок каталога или
    сделать смену модели только для desktop?
  - Кастомные серверы Pi: async-расширение с `/v1/models` или текущий
    `models.json`?
  - Планировщик: подбирать исполнителей только из нативного каталога?

## Фаза 1. Домен и порт

- Типы `CodingModel` (id, имя, контекст, maxTokens, нативные уровни, уровень по
  умолчанию, поддержка картинок) и
  `CodingModelSelection(engine, provider, modelId, level: String?)`.
  Уровень хранится строкой в словаре движка, без `ReasoningEffort`.
- Порт `CodingModelCatalog` в `domain/Ports.kt`: `models`, `refresh`, состояние
  и время снимка. Дисковый кэш каталога. Для Web и Android заглушка, отдающая
  только кэш.
- Поля в `CodingSession` и `CodingProject` добавляются аддитивно и допускают
  null. Старые `modelSelection` и `llmProfileId` читаются.

## Фаза 2. Адаптеры движков (jvmMain)

- **Codex.** Обернуть существующий `models()` в порт каталога.
- **Pi, встроенные провайдеры.** Таблица `baseUrl → id провайдера Pi`
  (`qwen-token-plan`, `openai`, `anthropic`, `google`, `openrouter` и др.).
  Авторизация через `auth.json` с правами 0600 в домашнем каталоге сессии.
  Листинг через `--list-models` или RPC. Неоднозначность `qwen-token-plan` и
  `qwen-token-plan-individual` разрешается выбором в настройках подключения.
- **Pi, кастомные серверы.** Генерируемое async-расширение, которое запрашивает
  `/v1/models` и регистрирует провайдера (проверено на локальном моке).

## Фаза 3. Запуск

- `PiCodingRuntime`: `--provider <провайдер Pi> --model <id> --thinking <уровень>`
  вместо `--provider magicpaper`. Для нативных провайдеров исчезают
  `PiModelsConfig.root` и `model-options.mjs`, вместе с подменой запроса и
  расхождением «default».
- Codex: `effort` в `turn/start` из нативного уровня.
- Проверить на живом DashScope, что потеря
  `requiresAssistantAfterToolResult: true` (есть в нашем конфиге, нет в
  нативной записи Pi) не ломает tool-calls.

## Фаза 4. UI и ViewModel

- `CodingModelSwitcherDialog` читает каталог. `EffortControl` обобщается до
  списка строк движка, чип «default» показывает реальное значение
  («по умолчанию: medium»).
- Избранное для кодинга отдельное и по движкам: каталог Pi около 70 моделей,
  нужен поиск.
- `selectCodingModel` и `codingProfileOf` заменяются нативными аналогами,
  `ProfileResolver.coding` удаляется.
- У этапа с готовой попыткой чип показывает «применится со следующей попытки»
  вместо тихого отката к замороженному `attempt.assignment`.

## Фаза 5. Планировщик (самая рискованная)

- `DecisionPlanner.recommend` берёт ростер из каталога, досье моделей
  переключаются на ключ `(provider, modelId)`.
- `StageAssignment` получает нативные поля. Проверка в `executionProfile`
  идёт по снимку каталога при допуске, без падения на clamp.
  Замороженное `attempt.assignment` сохраняется.
- Миграция сохранённых планов: старое назначение маппится по `baseUrl`,
  немаппируемое помечается «требует переназначения».
  `OrchestrationService.recoverAssignments` учитывает пропавшие модели.

## Фаза 6. Уборка

Удалить `codingModelId`, эвристику `ModelDefaults` из кодинг-пути,
`PiModelOptions` для нативных провайдеров и старые поля выбора после миграции.

## Проверка и выкатка

- **Тесты:** таблица `baseUrl → провайдер`, парсинг листинга, миграция планов;
  офлайн-интеграция с `PI_OFFLINE=1` и фейковым ключом (инфраструктура
  `PiCodingRuntimeIntegrationTest` есть); render-тесты `CodingComposerRenderTest`
  и `WorkerModelRenderTest`.
- **Флаг** `nativeCodingModels` по движкам. Порядок: Codex, затем встроенные
  провайдеры Pi, затем кастомные, затем планировщик.

## Риски

- **Версия Pi.** `PI_VERSION` закреплена, каталог меняется при `pi update`.
  Нужна обработка «сохранённая модель пропала из каталога».
- **Устаревший кэш** и офлайн-режим.
- **Ключи на диске** (`auth.json`): очистка вместе с домашним каталогом сессии.
- **Web и Android** без движка редактируют планы по кэшу.
- **Не проверено:** реальные ответы DashScope на `high` и `xhigh`, обновление
  каталога Pi по сети (`glm-5.3` и `deepseek-v4.1-flash` из избранного не
  найдены в офлайн-каталоге), профиль ChatGPT-подписки (нужен вход).
