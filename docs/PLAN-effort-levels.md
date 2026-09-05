# План: уровни усилия как свойство модели (по образцу Hermes и Cherry Studio)

Цель: заменить шкалу 0–100 на **дискретные уровни, которые declares модель**, и разделить
«что модель умеет» ( capabilities) от «как это закодировать в запросе» (транспорт).

Источники исследования (код прочитан напрямую):
- `NousResearch/hermes-agent` → `agent/reasoning_effort.py`, `hermes_cli/models_reasoning_caps.py`,
  `hermes_cli/inventory.py`, `hermes_cli/main_provider_setup.py`, `agent/anthropic_adapter.py`,
  `agent/transports/chat_completions.py`, `apps/desktop/src/lib/reasoning-effort.ts`.
- `CherryHQ/cherry-studio` → `packages/provider-registry/docs/reasoning-control.md`,
  `src/schemas/enums.ts`, `src/utils/reasoningControls.ts`, `src/patterns/reasoning-heuristics.ts`,
  `src/creators/anthropic.ts`, `src/shared/ai/reasoning.ts`,
  `src/renderer/components/ModelSpeedControl.tsx`.
- бонус — `pi-coding-agent` (`docs/models.md`, `docs/custom-provider.md`): модель описывает
  `thinkingLevelMap`, уровни клампятся по возможностям модели.

---

## 1. Как это устроено в Hermes

**Лестница — строки, не числа.** Единый канон порядка:

```python
EFFORT_LADDER = ("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
```

**Возможность = объявленный набор уровней у конкретной модели/эндпоинта.** Не bool, а данные:

```python
OPENAI_COMPAT_WIRE_EFFORTS = ("none", "minimal", "low", "medium", "high", "xhigh", "max")
CODEX_GPT56_EFFORTS   = ("none", "low", "medium", "high", "xhigh", "max")   # max только у gpt-5.6
CODEX_LEGACY_EFFORTS  = ("none", "low", "medium", "high", "xhigh")
KIMI_K3_EFFORTS       = ("low", "high", "max")     # medium/xhigh вообще нет на вайре
KIMI_K3_OVERRIDES     = {"medium": "high", "xhigh": "max"}
GLM52_EFFORTS         = ("high", "max")            # «низких» уровней нет
OX_ALPHA_EFFORTS      = ("low", "high", "max")     # thinking нельзя выключить
```

Правило из докстринга: «*when a provider rejects a level — fix its declared set, never a
predicate*». Предикаты по имени модели считаются техдолгом.

**Клампинг — к ближайшему БОЛЕЕ СЛАБОМУ уровню** (`clamp_effort`): verbatim, если поддерживается →
вендорский `overrides` → иначе самый сильный из поддерживаемых слабее запроса → иначе самый слабый.
`none` никогда не является целью клампинга (нельзя молча выключать мышление). Монотонно: более
сильный запрос не может разрешиться в более слабый, чем более слабый запрос. Неизвестный набор и
кастомные имена проходят насквозь без изменений.

**Откуда берутся уровни модели.** Живой каталог провайдера (OpenRouter-схема `/v1/models`):
`supported_parameters` содержит `"reasoning"` + объект `reasoning.{mandatory, supported_efforts}`.
Контракт **трёхзначный**: `True` (роутер объявляет reasoning) / `False` (каталог знает модель и она
НЕ принимает органы управления) / `None` (неизвестно). Кэш в памяти на процесс + **диск-зеркало**
`~/.hermes/cache/reasoning_caps.json` (TTL 24 ч) с прогревом в фоновом потоке, чтобы горячий путь запроса никогда не ждал HTTP; ошибка
фета помечается на 60 с. Если каталог молчит — `reasoning = True` («скрыть ручку от способной
модели хуже, чем показать лишнюю»), а `supported_efforts` из models.dev намеренно **не**
используется — он недоотчитывает уровни.

**Хранение.** Глобальный `agent.reasoning_effort` + **пер-модельные overrides**:

```yaml
agent:
  reasoning_effort: medium
  reasoning_overrides:
    anthropic/claude-opus-4.5: xhigh
```

Плюс колонка `reasoning_effort` у cron-задачи (NULL = наследует профиль). Снимок уровня
фиксируется на время отправки, чтобы смена уровня не «протекла» в уже поставленное в очередь сообщение.

**UI.** CLI-меню показывает только объявленные моделью уровни в каноническом порядке +
«Disable reasoning» / «Skip», курсор — на текущем (или `medium`). Десктоп: `none` — это **не**
уровень шкалы, а отдельный тумблер Thinking; шкала — `minimal…ultra`. HTTP-API принимает всю
лестницу, клампинг — в транспорте.

**Кодирование в запрос.** Не линейная функция от «процента», а таблицы:

```python
THINKING_BUDGET = {"xhigh": 32000, "high": 16000, "medium": 8000, "low": 4000}   # legacy Claude
ADAPTIVE_EFFORT_MAP = {"ultra": "max", "max": "max", "xhigh": "xhigh", ..., "minimal": "low"}
```

Claude 4.6+ — `thinking.type=adaptive` + `output_config.effort` (строка, бюджета нет вообще);
до 4.5 — `budget_tokens`; `xhigh` шлётся только если `_supports_xhigh_effort(model)`. Gemini 2.5 —
`thinkingLevel: low|high`, с явным комментарием «не выдумывай бюджет из грубых уровней усилия».

---

## 2. Как это устроено в Cherry Studio

**Закрытый словарь + намерение пользователя.**

```ts
REASONING_EFFORT = { none, minimal, low, medium, high, xhigh, max, auto }
type ReasoningSelection = 'default' | 'none' | 'auto' | ReasoningEffort
REASONING_EFFORT_ORDER = ['none','minimal','low','medium','high','xhigh','max','auto'] // для nearest-match
```

`default` = «не слать ничего», `auto` = «разберись сам», остальное — нормализованный уровень.
Выбор — намерение, а не поле запроса.

**Модель описывает ЧТО умеет, эндпоинт — КАК это закодировать в запросе.** Источник истины — `reasoning.controls`:

```ts
type ReasoningControl =
  | { kind: 'effort'; values: ReasoningEffort[]; default?: ReasoningEffort }
  | { kind: 'budget';  min: number; max: number; default?: number }
  | { kind: 'toggle';  default?: boolean }
```

Из `controls` одним места выводятся легаси-поля (`supportedEfforts`, `thinkingTokenLimits`,
`defaultEffort`), а рантайм-модель отдаёт `selectableEfforts` — **единственный** список, который
читает рендерер. UI не смотрит ни в id модели, ни в id провайдера, ни в регулярки.
Отдельная ось — `wireDialect: 'effort' | 'budget'` (поколение одного и того же вендорского API:
Gemini 3 `thinkingLevel` vs Gemini 2.x `thinkingBudget`; Claude 4.6 `adaptive` vs `enabled+budget_tokens`).
Ключевая фраза документа: «способность к effort и диалект — независимые оси» (claude-opus-4-5
принимает `output_config.effort`, но мыслит всё ещё бюджетом).

**Объявленные наборы в данных креаторов** (`src/creators/anthropic.ts`):

```ts
{ pattern: '^(?:anthropic\\.)?claude-fable',                 effort: ['low','medium','high','max'], toggle: false }
{ pattern: '…claude-(opus|sonnet|haiku)-(4.6+|5.x|latest)',  effort: ['low','medium','high','max'], toggle: true }
{ pattern: '^(?:anthropic\\.)?claude',                        toggle: true, wireDialect: 'budget' }   // «шаблон»
{ pattern: '…claude-opus-4[.-]7…',                            budget: { min: 1024, max: 128000 } }
```

Регулярки разрешены **только** в генерации каталога и при обогащении незнакомой кастомной модели на
ингесте (`reasoning-families.gen.ts` — артефакт `pnpm generate`); резолвер запроса и энкодер не
импортируют таблицу правил.

**Сужение уровней провайдером.** `ProviderModelOverride.reasoningContracts[endpoint].support.controls`
заменяет встроенные controls для конкретного эндпоинта. Приоритет: override модели провайдера →
inline-формат эндпоинта → глобальный дефолт формата.

**Пайплайн запроса.** `request.reasoningEffort ?? assistant.settings.reasoning_effort ?? 'default'`
→ резолвер (controls модели + wire-профиль эндпоинта + `maxTokens`) → неизменяемый
`ResolvedReasoningInvocation` → закрытый набор операций эмиссии (`reasoningEffort`, `thinking.*`,
`thinkingConfig.*`, `enable_thinking`, `thinking_budget`, `disable_reasoning`,
`chat_template_kwargs.*`) → `providerOptions` адаптера. Никаких выражений и произвольных JSONPath.

**Бюджет из уровня — таблица коэффициентов, а не процент:**

```ts
EFFORT_RATIO = { minimal: 0.05, low: 0.05, medium: 0.5, high: 0.8, xhigh: 0.9, max: 1 }
budget = clamp(max(1024, min + (max-min)*ratio), ≤ maxTokens)
```

есть и обратная функция `nearestEffortForBudget`.

**UI (`ModelSpeedControl`).** Слайдер есть, но он **индексный по отфильтрованному списку**:

```ts
const reasoningOptions = SLIDER_EFFORT_ORDER.filter(e => new Set(deriveThinkingOptions(model)).has(e))
const supportsReasoning = reasoningOptions.length > 1
const showEffortSlider = sliderEfforts.filter(e => e !== 'none' && e !== 'auto').length > 1
// value = currentIndex, max = sliderEfforts.length - 1, step = 1  ← ступенек ровно столько, сколько уровней у модели
```

Рядом — кнопка `default` («Пусть провайдер»), подписи «faster / smarter», строка уровня в чипе.
При смене модели — `resolveReasoningEffortForModel` → `nearestThinkingOption` (ближайший по
порядковой лестнице, при равном расстоянии — к более сильному), а если у новой модели ручки нет —
`resolveSupportedReasoningEffort` возвращает `default`.
Хранение: `assistant.settings.reasoning_effort` (дефолт на будущее) + снимок в `payload.reasoningEffort`
на конкретную отправку (защита от гонки «переключил и сразу отправил»).

---

## 3. Пи (тоже релевантно, у нас этот агент уже подключён)

`off | minimal | low | medium | high | xhigh | max`, модель объявляет `reasoning: true` и
`thinkingLevelMap` (ключ — уровень пи, значение — строка для провайдера или `null` = уровня нет,
дырки разрешены, `xhigh`/`max` только opt-in). «Level is clamped to model capabilities
(non-reasoning models always use "off")». В `--models` можно закрепить уровень на шаблон:
`anthropic/*:high`.

---

## 4. Что у нас сейчас и почему это хуже

| Факт | Где | Проблема |
|---|---|---|
| `Effort.MIN=0 … MAX=100`, пресеты 0/20/50/80/100 | `domain/LlmProfiles.kt::Effort` | 101 значение, а реализуемых — 4–6: 95 % положений слайдера ни на что не влияют (ложная точность) |
| `openAiReasoningEffort`: 0→minimal, 1-33→low, 34-66→medium, else high | `data/llm/LlmPayloads.kt` | «50» для OpenAI = medium, а для Anthropic = 16 384 токена (50 % от 32 768) — одно число значит разное |
| `anthropicThinkingBudget = e * 32768 / 100` | там же | линейная интерполяция бюджета; у Claude 4.6+ бюджета нет — запрос `thinking.type=enabled` будет отклонён |
| `temperatureForEffort = 0.2 + 0.9*e/100` | там же | усилие подменяется температурой — побочный эффект на модели, которая усилия не имеет |
| `supportsEffort: Boolean` (+ эвристика по префиксам) | `domain/ModelDefaults.kt`, `ProviderCatalog.ModelInfo`, `data.llm.LlmPayloads` | нет **словаря** уровней: K3 (low/high/max), GLM-5.2 (high/max), Grok legacy (low/high) неразличимы |
| один `effort` на весь `LlmProfile` | `LlmProfile.effort` | уровень хранится на профиле, а не на модели: переключил модель — старое значение молча перенесено |
| `EffortControl` = `Slider(0f..100f)` + 5 чипов | `ui/components/EffortControl.kt` | UI не может отразить возможности модели; нет состояния «по умолчанию провайдера» |
| `piThinkingLevel(effort: Int)` — свои коридоры | `jvmMain::PiCodingRuntime` | дублирование лестницы; пи сам клампнул бы уровни, если отдавать `thinkingLevelMap` |

---

## 5. Целевая модель (Kotlin)

### 5.1 Словарь и лестница — `domain/LlmProfiles.kt`

```kotlin
/** Канонический уровень усилия. Порядок = смысл (used for nearest-clamp). */
enum class ReasoningEffort(val wire: String, val ruLabel: String) {
    NONE("none", "Выключено"),
    MINIMAL("minimal", "Минимальное"),
    LOW("low", "Низкое"),
    MEDIUM("medium", "Среднее"),
    HIGH("high", "Высокое"),
    XHIGH("xhigh", "Очень высокое"),
    MAX("max", "Максимум"),
}

/** Выбор пользователя: уровень либо «как скажет провайдер». */
sealed interface EffortSelection {
    data object Default : EffortSelection
    data class Level(val effort: ReasoningEffort) : EffortSelection
}
```

`EffortSerializer` принимает старое число (0→NONE, 1..33→LOW, 34..66→MEDIUM, 67..89→HIGH,
90..100→MAX) и старые строки-перечисления; пишет — строку уровня или `"default"`. Миграция
данных не нужна: tolerant-read, как уже сделано для легаси `"LOW"/"MEDIUM"`.

### 5.2 Возможности вместо bool

```kotlin
sealed interface ReasoningCapability {
    /** Модель не принимает органы управления мышлением. */
    data object None : ReasoningCapability
    /** Только вкл/выкл (Ollama think, старые Claude toggle). */
    data class Toggle(val default: ReasoningEffort = ReasoningEffort.MEDIUM) : ReasoningCapability
    /** Нативный словарь уровней + диалект кодирования. */
    data class Levels(
        val values: Set<ReasoningEffort>,      // что показывает UI
        val default: ReasoningEffort? = null,  // на чём стоять при «Default»
        val dialect: WireDialect = WireDialect.EFFORT,
        val mandatory: Boolean = false,        // выключить нельзя
        val overrides: Map<ReasoningEffort, ReasoningEffort> = emptyMap(), // medium→high у K3
    ) : ReasoningCapability
    /** Уровни + бюджет в токенах (Gemini 2.x, Claude ≤4.5). */
    data class Budgeted(val levels: Levels, val minTokens: Int, val maxTokens: Int) : ReasoningCapability
}

enum class WireDialect { EFFORT, BUDGET_TOKENS, THINKING_LEVEL }
```

`ModelInfo`, `DiscoveredModel` и `LlmProfile` вместо `supportsEffort: Boolean` несут
`reasoning: ReasoningCapability`. `supportsEffort` остаётся как производный `get() = reasoning !is None`
(обратная совместимость вызовов и тестов).

### 5.3 Единственная функция клампинга — `domain/ReasoningLadder.kt` (новый файл)

```kotlin
/**
 * Перевод выбора на словарь конкретной модели: verbatim → declared override →
 * ближайший БОЛЕЕ СЛАБЫЙ поддерживаемый → самый слабый. NONE никогда не цель
 * (нельзя молча выключать мышление). Default остаётся Default.
 */
fun EffortSelection.resolveFor(cap: ReasoningCapability): ResolvedEffort
```

Правила (собираем лучшее из двух агентов): ближайший более слабый — как в Hermes (не повышает
стоимость), `none` не является целью деградации — как в Hermes, при равном расстоянии — к сильному
(если решим делать nearest, а не weaker) — как в Cherry. Документируем выбранное правило один раз.

### 5.4 Транспорт: кодирование из резолва, без имён моделей

`LlmPayloads` получает на вход `ResolvedEffort` + `ReasoningCapability` и больше не читает `modelId`:

| Провайдер / диалект | Что уходит в запрос |
|---|---|
| OpenAI-compat, `EFFORT` | `reasoning_effort = effort.wire` (только если уровень ∈ `values`) |
| Anthropic `THINKING_LEVEL`/adaptive (4.6+) | `thinking.type=adaptive`, `output_config.effort = wire` |
| Anthropic `BUDGET_TOKENS` (≤4.5) | `thinking={type:enabled, budget_tokens: TABLE[effort]}`, `temperature=1` |
| Gemini 3 (`THINKING_LEVEL`) | `thinkingConfig.thinkingLevel = low\|high\|…` |
| Gemini 2.x (`BUDGET_TOKENS`) | `thinkingConfig.thinkingBudget = clamp(min + (max-min)*RATIO[effort])`, `auto = -1` |
| capability `None` | поле не отправляется; **температуру не подставляем** (см. п. 8) |

Таблицы — данные рядом с диалектом, а не производная от 0–100:

```kotlin
private val BUDGET_RATIO = mapOf(MINIMAL to 0.05, LOW to 0.05, MEDIUM to 0.5, HIGH to 0.8, XHIGH to 0.9, MAX to 1.0)
private val ANTHROPIC_BUDGET = mapOf(LOW to 4000, MEDIUM to 8000, HIGH to 16000, XHIGH to 32000)
```

### 5.5 Хранение: уровень на модель

```kotlin
data class LlmProfile(
    …,
    val effort: EffortSelection = EffortSelection.Default,           // дефолт профиля
    val effortOverrides: Map<String, EffortSelection> = emptyMap(),  // modelId → выбор (Hermes-style)
)
fun LlmProfile.effortFor(modelId: String) = effortOverrides[modelId] ?: effort
```

`setProfileEffort` → `setModelEffort(profileId, modelId, selection)`; снимок уровня фиксируется на
послание (Cherry) в чате и в кодинг-сессии, чтобы моментальная отправка не прочитала «ещё не
сохранённый» уровень.

### 5.6 UI: `EffortControl` по возможностям

- нет ручки (`None`) → блок не рендерится (сейчас рендерится с извиняющимся заголовком);
- `Toggle` → переключатель «Думать / Не думать»;
- `Levels` → сегменты/чипы ровно из `values` в каноническом порядке + кнопка «По умолчанию»;
  подписи берут `ruLabel`, при `mandatory` уровень `NONE` не показывается;
- `Budgeted` → чипы + вторичная строка «≈ 8 000 токенов» (диапазон из модели);
- если уровней много (≥5) — допустим слайдер, но **индексный по `values`** (stages = N, как в
  Cherry), а не 0..100;
- смена модели → `resolveFor(newCap)` + подпись «α → β (модель α не поддерживает)».

### 5.7 Каталог: обогащение и кэш

`ModelDirectory.models()` уже ходит в сеть за id — дополнительно парсить OpenRouter-схему
(`supported_parameters` ∋ `"reasoning"`, `reasoning.supported_efforts`, `reasoning.mandatory`) в
**трёхзначный** ответ: есть / точно нет / неизвестно. Для «неизвестно» — наша эвристика
(`ModelDefaults.heuristicEffort`), но возвращать она должна `ReasoningCapability`, а не bool, по
семействам (K3, GLM, Grok, Gemini 2.5 vs 3, Claude 4.5 vs 4.6). Кэш — файл в `KeyValueStore` /
platform fs с TTL 24 ч + прогрев в фоне, чтобы первый запрос не блокировался сетью.

### 5.8 Отказ от «усилие = температура»

Модель без нативного усилия не получает ни `reasoning_effort`, ни подставную `temperature` —
только явное значение из `AdvancedSettings`. Это убирает скрытый побочный канал «слайдер меняет
сэмплинг» и делает шкалу честной.

### 5.9 Мост в пи

В генерируемый `models.json` писать `thinkingLevelMap` (и `reasoning: capability !== None`), а
`--thinking` отдавать уровнем модели, а не бакетом числа. Никакого своего `piThinkingLevel(Int)` —
клампинг делает сам пи по карте.

---

## 6. Этапы внедрения

| # | Что | Файлы | Риск |
|---|---|---|---|
| 1 | enum + `EffortSelection` + сериализатор с tolerant-read; `Effort` оставить как `@Deprecated` мост | `domain/LlmProfiles.kt`, `commonTest/.../JsonLlmProfileRepositoryTest.kt` | низкий: JSON-контракт расширяется |
| 2 | `ReasoningCapability` + `ReasoningLadder.resolveFor` + таблицы бюджетов; каталог и эвристика отдают capability | `domain/ProviderCatalog.kt`, `domain/ModelDefaults.kt`, новые `domain/ReasoningLadder.kt` | средний: правки в `ProviderCatalogTest`, `ModelDefaultsTest` |
| 3 | Пейлоады принимают `ResolvedEffort`; убрать `temperatureForEffort` | `data/llm/LlmPayloads.kt`, `*Gateway.kt`, `LlmPayloadsTest.kt` | средний: зафиксировать тестами оба диалекта Anthropic |
| 4 | `effortOverrides` + API вью-модели, снимок на отправку | `ui/MagicPaperViewModel.kt`, `domain/Ports.kt` | низкий |
| 5 | `EffortControl` по возможностям + состояние «По умолчанию»; обновление `ModelSwitcher`, `CodingModelSwitcher`, `SettingsScreen` | `ui/components/*` | средний (UI), плюс i18n-строки вместо literals |
| 6 | capability из живого каталога + диск-кэш с TTL и фоновым прогревом | `data/llm/OpenAiCompatibleGateway.kt`/`ModelDirectory` impl | средний: сеть, таймауты |
| 7 | `thinkingLevelMap` в мосте пи | `jvmMain::PiCodingRuntime.kt` | низкий |

Порядок 1→5 даёт основной эффект (шкала = возможности модели) без сетевых работ; 6–7 — улучшения.

---

## 7. Правила-каноны (выписать в KDoc, чтобы не разъезжалось)

1. Уровень — символ из закрытого словаря, никогда не процент и не «число от 0 до 100».
2. Модель отвечает за **что** она умеет (capability), транспорт — за **как** (диалект). Регулярки по
   id модели живут только в каталоге/обогащении, не в сборщиках запросов.
3. Состояние «по умолчанию провайдера» — отдельное, а не «самый низкий уровень».
4. Клампинг монотонен, не повышает стоимость и не выключает мышление молча.
5. Невозможное (модель не принимает `none`) снимается с ручки, а не отправляется и не получает 400.
6. Бюджет токена — таблица по уровням + границы модели, клампится к `max_tokens`, не интерполяция числа.
7. «Неизвестно» ≠ «не умеет»: при пустом каталоге ручку показываем (permissive default).
8. Выбор фиксируется на отправку, а не читается из глобального состояния в момент исполнения.

---

## 8. Открытые вопросы к решению пользователя

- Нужен ли уровень `auto` (как в Cherry) — «сам реши, модель»? Нынешняя шкала его не выражает.
- Ближайший **более слабый** (Hermes, экономия) или ближайший по расстоянию (Cherry, точность)?
- Хранить ли `effortOverrides` по `modelId` глобально или в пределах профиля (у нас профиль = провайдер + ключ).
- Что делать со шкалой в кодинг-сессиях: показывать только уровни пи (`off…max`) или общий словарь?
