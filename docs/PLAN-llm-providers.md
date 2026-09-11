# План: полноценное подключение AI-провайдеров, моделей, effort и advanced settings

Цель: пользователь может завести **несколько провайдеров** (профилей подключения), у каждого —
выбрать **модель**, **уровень усилия (effort)** и **тонкие настройки**; переключение активной
модели доступно **кнопкой прямо в чате** (для одного свитка или глобально).

Ограничения проекта: KMP (jvm/web/android), без DI-фреймворков, без материальных иконок
(текстовые глифы), хранение — `KeyValueStore`, стиль — пастельный минимализм.

---

## 1. Текущее состояние (точка старта)

| Что | Где | Как сейчас |
|---|---|---|
| Настройки модели | `domain/Models.kt :: AppSettings` | одна тройка `llmBaseUrl/llmApiKey/llmModel` + `llmConfigured` |
| Шлюз | `data/llm/OpenAiCompatibleGateway.kt` | один транспорт: `POST {base}/chat/completions`, `stream=false` |
| Порт | `domain/Ports.kt :: LlmGateway.complete(settings, messages)` | в сигнатуре — весь `AppSettings` |
| Потребители шлюза | `MagicAgent`, `SkillEducator`, `SelfEducationPlugin` | получают один и тот же `LlmGateway` из `Dependencies` |
| Пи-агент | `jvmMain :: PiCodingRuntime.writePiConfig` | мостит `settings.llm*` в `models.json` провайдера `magicpaper` |
| UI | `SettingsScreen`, `WelcomeScreen` (шаг 1), `ChatScreen.Composer` | поля Base URL / ключ / имя модели; в чате переключателя нет |
| Профиль | `ProfileBundle` (version = 1) | сериализует `AppSettings` целиком |

Вывод: нужен переход от «одна модель в настройках» к **каталогу профилей подключений**
и стратегии транспортов по типу провайдера, с обратной совместимостью старых данных.

---

## 2. Целевая модель домена (новый/изменённый код в `domain/`)

### 2.1 Новые типы — `domain/LlmProfiles.kt` (новый файл)

```kotlin
/** Тип провайдера: определяет транспорт и формат параметров. */
@Serializable
enum class ProviderType {
    /** Любой сервер с /chat/completions: OpenAI, Ollama, LM Studio, vLLM, OpenRouter… */
    OPENAI_COMPATIBLE,
    /** Anthropic Messages API (/v1/messages). */
    ANTHROPIC,
    /** Google AI (Generative Language API, generateContent). */
    GOOGLE,
}

/** Уровень усилия модели — единая шкала, транспорт сам мапит в свой формат. */
@Serializable
enum class EffortLevel { LOW, MEDIUM, HIGH }

/** Тонкие параметры подключения (все необязательные — «пусто = по умолчанию провайдера»). */
@Serializable
data class AdvancedSettings(
    val temperature: Double? = null,      // 0..2
    val maxTokens: Int? = null,           // потолок ответа
    val topP: Double? = null,
    val timeoutSeconds: Int = 60,
    val systemPromptOverride: String = "",// пусто = штатный промпт агента
    val contextMessages: Int = 8,         // сколько истории брать (сейчас HISTORY_LIMIT = 8)
)

/** Профиль подключения: провайдер + доступ + модель + режимы. Единица переключения. */
@Serializable
data class LlmProfile(
    val id: String,
    val name: String,                     // «OpenAI GPT-5», «Локальная Ollama» — видно в UI
    val provider: ProviderType = ProviderType.OPENAI_COMPATIBLE,
    val baseUrl: String = "",             // для известных провайдеров предзаполняется
    val apiKey: *** = "",
    val modelId: String = "",             // из каталога или своё имя
    val effort: EffortLevel = EffortLevel.MEDIUM,
    val advanced: AdvancedSettings = AdvancedSettings(),
    val createdAt: Long = 0,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && modelId.isNotBlank()
    /** Короткая подпись для кнопки в чате: «Ollama · llama3.2». */
    val shortLabel: String get() = "$name · $modelId"
}
```

Почему так (SOLID): профиль — единственный объект, который нужен транспорту для вызова
(**ISP**: шлюз больше не тащит весь `AppSettings`); новые провайдеры добавляются новым
значением `ProviderType` + транспортом без правки потребителей (**OCP**).

### 2.2 Изменения `AppSettings`

```kotlin
data class AppSettings(
    // …существующие поля поиска/онбординга без изменений…
    /** Активный по умолчанию профиль подключения. */
    val activeLlmProfileId: String = "",
    // Старые поля ОС... (для миграции), помечаются @Deprecated
    val llmBaseUrl: String = "",
    val llmApiKey: *** = "",
    val llmModel: String = "",
) {
    /** Совместимо: настроен либо профиль, либо легаси-тройка. */
    val llmConfigured: Boolean get() = activeLlmProfileId.isNotBlank() ||
        (llmBaseUrl.isNotBlank() && llmModel.isNotBlank())
}
```

### 2.3 Переопределение на сессию

```kotlin
data class ChatSession(
    …,
    /** Профиль только для этого свитка; null = глобальный активный. */
    val llmProfileId: String? = null,
)
```

### 2.4 Изменение порта `LlmGateway`

```kotlin
interface LlmGateway {
    suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String
}
```

`MagicAgent.answer(...)` и `SkillEducator.propose(...)` принимают список профилей/активный
профиль вместо сырых настроек — см. §6 про разрешение профиля.

---

## 3. Каталог провайдеров и моделей (`domain/ProviderCatalog.kt`, новый файл)

Чистый домен, без сети: описания известных провайдеров для быстрого добавления
(«выбери провайдера → вставь ключ»).

```kotlin
data class ModelInfo(val id: String, val name: String = id, val supportsEffort: Boolean = false)

data class ProviderSpec(
    val type: ProviderType,
    val displayName: String,              // «OpenAI», «Anthropic», «Google Gemini», «Ollama (локально)»
    val defaultBaseUrl: String,
    val keyHint: String,                  // «sk-…», «пусто для локальных серверов»
    val requiresKey: Boolean,
    val models: List<ModelInfo>,          // кураторский список + всегда «Своя модель…»
    val supportsEffort: Boolean,
)

object ProviderCatalog {
    val all: List<ProviderSpec> = listOf(
        ProviderSpec(OPENAI_COMPATIBLE, "Ollama (локально)", "http://localhost:11434/v1",
            keyHint = "ключ не нужен", requiresKey = false,
            models = listOf(ModelInfo("llama3.2"), ModelInfo("qwen3"), ModelInfo("mistral")),
            supportsEffort = false),
        ProviderSpec(OPENAI_COMPATIBLE, "OpenRouter", "https://openrouter.ai/api/v1", …),
        ProviderSpec(OPENAI_COMPATIBLE, "OpenAI-совместимый сервер", "", …), // ручной
        ProviderSpec(OPENAI, …, "https://api.openai.com/v1",
            models = listOf(ModelInfo("gpt-5-mini", supportsEffort = true), …)),
        ProviderSpec(ANTHROPIC, "Anthropic", "https://api.anthropic.com",
            models = listOf(ModelInfo("claude-sonnet-4-5", supportsEffort = true), …)),
        ProviderSpec(GOOGLE, "Google Gemini",
            "https://generativelanguage.googleapis.com/v1beta",
            models = listOf(ModelInfo("gemini-2.5-flash", supportsEffort = true), …)),
    )
    fun spec(type: ProviderType): ProviderSpec = …
}
```

Каталог — данные в коде (как `EmbeddedDocRepository`/`EmbeddedSkillCatalog`): офлайн,
без внешних зависимостей, расширяется коммитом.

---

## 4. Транспорты (`data/llm/`) — стратегия по типу провайдера

### 4.1 Структура

```
data/llm/
  OpenAiCompatibleGateway.kt   — рефакторинг: транспорт OPENAI_COMPATIBLE/OPENAI
  AnthropicGateway.kt          — новый: POST {base}/v1/messages
  GoogleGateway.kt             — новый: POST {base}/models/{model}:generateContent
  RoutingLlmGateway.kt         — новый: роутер, реализует LlmGateway
  LlmPayloads.kt               — чистые функции сборки тела запроса (тестируемо без сети)
```

`RoutingLlmGateway` (композит по типу):

```kotlin
class RoutingLlmGateway(private val transports: Map<ProviderType, LlmGateway>) : LlmGateway {
    override suspend fun complete(profile: LlmProfile, messages: List<LlmMessage>): String =
        (transports[profile.provider] ?: error("Нет транспорта для ${profile.provider}"))
            .complete(profile, messages)
}
```

Собирается в `Dependencies`: все три транспорта делят один `HttpClient` и `Json` —
новый транспорт = новая строка в карте (**OCP**).

### 4.2 Маппинг effort и advanced (содержимое `LlmPayloads.kt`)

Чистые функции `buildXxxPayload(profile, messages): JsonObject` — юнит-тестятся без моков сети.

| Параметр | OpenAI-совместимый | Anthropic | Google |
|---|---|---|---|
| effort LOW/MED/HIGH | `"reasoning_effort"` (для моделей с `supportsEffort`), иначе пресет температуры 0.2/0.7/1.2 | `thinking: {type:"enabled", budget_tokens: 1024/4096/16000}` | `generationConfig.thinkingConfig.thinkingBudget: 1024/4096/24576` |
| temperature | `temperature` | `temperature` | `generationConfig.temperature` |
| maxTokens | `max_tokens` | `max_tokens` (обязателен — дефолт 4096) | `generationConfig.maxOutputTokens` |
| topP | `top_p` | `top_p` | `generationConfig.topP` |
| timeout | `HttpRequestBuilder.timeout` | то же | то же |

Правило: если у модели `supportsEffort = false` — effort тихо деградирует до температурного
пресета (никаких 400 от сервера); подпись в чате показывает effort только когда он реально
применим.

### 4.3 Что НЕ делаем в этой итерации

- Стриминг (`stream=true`) — оставлен задел: поле в `AdvancedSettings` добавим, когда
  появится UI пословного вывода; транспорты пока `stream=false` (статус-кво).
- Вызовы инструментов, мультимодальность — вне рамок.

---

## 5. Хранение и миграция

### 5.1 Репозиторий профилей

`SettingsRepository` расширяется (или новый порт `LlmProfileRepository` — предпочтительнее,
**SRP**; реализуется тем же `JsonSettingsRepository`-стилем поверх `KeyValueStore`,
ключ `"llm_profiles"`):

```kotlin
interface LlmProfileRepository {
    suspend fun all(): List<LlmProfile>
    suspend fun save(profile: LlmProfile)
    suspend fun delete(id: String)
    suspend fun wipe()
}
```

### 5.2 Миграция легаси-настроек (одноразовая, при загрузке)

В `bootstrap()` ViewModel (или в `JsonSettingsRepository.load` — лучше в доменном
`ProfileMigrator`, чтобы покрыть тестом):

```
если профилей нет И легаси-тройка настроена:
    создать LlmProfile(id = "legacy", name = "Мой сервер",
        provider = OPENAI_COMPATIBLE, baseUrl/apiKey/model = из легаси)
    активировать его; легаси-поля оставить как есть (источник правды — профиль)
```

Тесты по образцу `OnboardingCompatTest`: старый JSON настроек → после миграции профиль
существует и активен; пустой старт → профилей ноль, агент показывает «источник не подключён».

### 5.3 Профиль приложения (экспорт/импорт)

- `ProfileBundle.version = 2`, новое поле `llmProfiles: List<LlmProfile> = emptyList()`.
- Импорт бандла версии 1: работает за счёт дек... `@Serializable` значений по умолчанию +
  миграции §5.2 (легаси-тройка из старых настроек превратится в профиль).
- Экспорт: ключи не маскируем (статус-кво для локального файла; упомянуть в доках).
- `wipeAll()` чистит и ключ `llm_profiles`.

---

## 6. Разрешение активного профиля (домен, `domain/ProfileResolver.kt`)

Единая точка ответа на вопрос «какой моделью отвечать» — чтобы чат, самообучение
и пи-агент не решали это каждый по-своему:

```kotlin
object ProfileResolver {
    /** Профиль для сессии: переопределение свитка > глобальный активный > первый настроенный. */
    fun resolve(session: ChatSession?, settings: AppSettings, profiles: List<LlmProfile>): LlmProfile? {
        val id = session?.llmProfileId ?: settings.activeLlmProfileId
        return profiles.firstOrNull { it.id == id && it.configured }
            ?: profiles.firstOrNull { it.configured }
    }
}
```

Изменения потребителей:
- `MagicAgent.answer(history, text, settings, session, profiles)` — внутри вызывает
  `ProfileResolver`; если профиля нет — нынешний `NOT_CONFIGURED_TEXT` (текст обновить:
  «откройте настройки → Магические источники»). `HISTORY_LIMIT` заменяется на
  `profile.advanced.contextMessages`; `SYSTEM_PROMPT` — на `systemPromptOverride`, если задан.
- `SkillEducator.propose(history, settings, profiles)` — та же логика разрешения.
- `PiCodingRuntime.writePiConfig(settings, profile)` — мостит **разрешённый профиль**:
  `baseUrl`, `apiKey`, `modelId`; `"supportsReasoningEffort"` = `spec.supportsEffort`;
  для провайдеров не-OpenAI (Anthropic/Google) пи-мост честно отключается сообщением
  «кодинг-агент работает с OpenAI-совместимым сервером» (пи понимает только этот формат).

---

## 7. UI

### 7.1 Экран настроек: раздел «Магические источники» (вместо нынешней секции модели)

`SettingsScreen`, между «Разделами» и «Поисковым движком»:

```
Магические источники
┌────────────────────────────────────────────────────┐
│ ✦ Ollama · llama3.2            активен ✓   [изм.] │
│ ✧ OpenAI GPT-5-mini · высокий          [изм.] [✕] │
│ ✧ Claude Sonnet · средний              [изм.] [✕] │
│          [+ Подключить провайдера]                 │
└────────────────────────────────────────────────────┘
```

- Список профилей (строки в стиле `NavEntry`): имя, провайдер·модель, бейдж effort,
  галка у активного, тап — сделать активным глобально, «✕» — удалить (подтверждение =
  повторный тап, без диалогов — в духе приложения).
- Экран/панель редактирования профиля (полноэкранная форма, черновик локально):
  1. Выбор провайдера из `ProviderCatalog` (кнопки-строки; «Свой сервер» — ручной ввод).
     При выборе предзаполняются `baseUrl` и список моделей.
  2. Base URL (редактируемый), API-ключ (с подсказкой `keyHint` из каталога).
  3. Модель: быстрые кнопки из `spec.models` + поле «Своя модель…» (всегда доступно).
     Опционально (этап 6): кнопка «Запросить список» → `GET {base}/models` для
     OpenAI-совместимых.
  4. **Уровень усилия**: три кнопки-сегмента «Низкий / Средний / Высокий»; если
     `supportsEffort = false` — подпись «эта модель поддерживает только температурный режим».
  5. **Дополнительные параметры** (свёрнутая секция «Тонкие настройки»): температура,
     max tokens, top-p, таймаут, размер истории, свой системный промпт. Пустое значение =
     «по умолчанию провайдера» (поля строковые, парсятся при сохранении с валидацией).
  6. Кнопка «Проверить подключение» (этап 6): тестовый запрос «Скажи: ✦» с показом
     результата/ошибки.
- Онбординг: шаг 1 (`WelcomeModel`) получает тот же выбор провайдера из каталога
  (упрощённый: карточки провайдеров → ключ → модель), вместо трёх голых полей.

### 7.2 Кнопка переключения в чате (ключевое требование)

В `Composer` (`ChatScreen`) слева от поля ввода появляется **чип текущей модели**:

```
[ ✦ Ollama · llama3.2 · ▾ ] [ Начертать заклинание…        ] [Отправить]
```

- Текст чипа = `shortLabel` разрешённого профиля (+ буква уровня «Н/С/В», когда применим).
- Если ни один профиль не настроен: чип «✦ Источник не подключён» → ведёт в настройки.
- Тап по чипу открывает **переключатель** (общий источник истины — новый комонент
  `ui/components/ModelSwitcher.kt`):
  - Десктоп: всплывающая карточка над чипом (`Popup`) шириной ~320dp.
  - Узкие экраны/андроид: тот же контент в `ModalBottomSheet` (Material3 есть в стеке).
  Реализация: один `ModelSwitcherContent`, обёртки разные — переиспользование без ветвления
  логики.
- Содержимое переключателя:
  ```
  Для этого свитка           Для всех свитков
  ◉ (радио по профилям)      [ ] сделать основным
  ──
  Усилие:  Низкий | Средний | Высокий   ← меняет профиль сразу
  ⚙ Тонкие настройки → (открывает экран редактирования этого профиля)
  ```
- Семантика выбора:
  - выбор профиля в переключателе = **переопределение текущей сессии**
    (`session.llmProfileId = id`, сохраняется в чат-репозиторий); рядом с чипом
    появляется метка «только этот свиток» (глиф «◌»), тап по ней снимает переопределение;
  - чекбокс «сделать основным» пишет `settings.activeLlmProfileId`;
  - смена усилия/параметров из переключателя применяется к выбранному профилю сразу
    (`profileRepo.save`) — это быстрая крутилка, тонкие настройки живут в настройках.
- После переключения: `notice` («Свиток отвечает через …»), чип обновляется.

### 7.3 ViewModel (`MagicPaperViewModel`)

Новое состояние: `UiState.llmProfiles: List<LlmProfile>`, `UiState.modelSwitcherOpen: Boolean`.
Новые методы:

```
selectChatProfile(profileId, global: Boolean)   // сессия и/или глобально
clearSessionProfile()                           // снять переопределение свитка
saveLlmProfile(profile) / deleteLlmProfile(id)  // CRUD из настроек
setProfileEffort(profileId, effort)             // из переключателя
toggleModelSwitcher(open: Boolean)
```

`send()` использует `ProfileResolver.resolve(session, settings, profiles)`.

### 7.4 Документация и ключевые слова

- Новая статья `EmbeddedDocRepository`: «Магические источники (провайдеры)» — как
  подключить, чем отличаются усилие/тонкие настройки, что попадает в файл профиля.
- `APP_KEYWORDS`: добавить «провайдер», «источник», «модель», «усилие», «переключить модель».

---

## 8. Тесты (`commonTest`, паттерны существующих)

| Тест | Проверяет |
|---|---|
| `LlmProfileRepositoryTest` | round-trip профилей, delete, wipe |
| `ProfileMigrationTest` | легаси-тройка → профиль `legacy` активен; пустой старт → пусто; идемпотентность |
| `ProfileResolverTest` | приоритет: сессия > глобальный > первый настроенный; ненастроенные пропускаются |
| `LlmPayloadsTest` | OpenAI: reasoning_effort только при supportsEffort; Anthropic: max_tokens всегда, thinking-бюджеты по effort; Google: thinkingBudget; temperature/maxTokens/topP пробрасываются; пустые advanced не попадают в JSON |
| `ProviderCatalogTest` | уникальность типов, дефолтные baseUrl непустые |
| `OnboardingCompatTest` (расширить) | старый бандл версии 1 импортируется и мигрирует |
| Роутер | `RoutingLlmGateway` выбирает транспорт по типу; неизвестный тип — ошибка с текстом |

Интеграция с пи-агентом: существующий `PiCodingRuntimeIntegrationTest` продолжает работать
через мигрированный профиль (проверить, что `writePiConfig` берёт разрешённый профиль).

---

## 9. Этапы поставки (порядок важен, каждый этап компилируется и тестируется)

| # | Этап | Состав | Оценка |
|---|---|---|---|
| 1 | Домен и хранение | `LlmProfiles.kt`, `ProviderCatalog.kt`, `ProfileResolver.kt`, порт `LlmProfileRepository` + JSON-реализация, миграция, изменения `AppSettings/ChatSession`, новые сигнатуры `LlmGateway.complete(profile, …)` + `MagicAgent`/`SkillEducator`; тесты §8 (1–3) | 0.5–1 день |
| 2 | Транспорты | рефакторинг `OpenAiCompatibleGateway` на общий код, `LlmPayloads.kt` + `AnthropicGateway`, `GoogleGateway`, `RoutingLlmGateway`, DI-сборка; тесты пейлоадов | 1 день |
| 3 | Настройки | раздел «Магические источники»: список, форма редактирования (провайдер→ключ→модель→усилие→тонкие), онбординг-шаг 1 | 1 день |
| 4 | Кнопка в чате | чип в `Composer`, `ModelSwitcher` (Popup/BottomSheet), per-session override, методы ViewModel | 1 день |
| 5 | Интеграции | пи-мост на разрешённый профиль, `ProfileBundle v2`, документация-статья, ключевые слова, `wipeAll`, тексты «не подключён» | 0.5 дня |
| 6 | Полировка (по желанию) | `GET /models`, кнопка «Проверить подключение», маскировка ключа в списке профилей, стриминг-задел | 1–2 дня |

Итого ядро (этапы 1–5): ~4 рабочих дня.

---

## 10. Риски и грабли

1. **Обратная совместимость** — все новые поля `AppSettings/ChatSession/ProfileBundle`
   только с дефолтами (`encodeDefaults = true` + `ignoreUnknownKeys = true` уже стоят);
   старые файлы читаются без миграции версии формата.
2. **Разные форматы API** — Anthropic требует `max_tokens` и заголовок `anthropic-version:
   2023-06-01` (+ `x-api-key` вместо Bearer); Google кладёт ключ в query-параметр `key=`
   (или `x-goog-api-key`). Это инкапсулировано в транспортах и покрыто тестами пейлоадов.
3. **Пи-агент понимает только OpenAI-формат** — для Anthropic/Google профилей кодинг-рантайм
   показывает честную ошибку, а не падает (иначе молча сломался бы раздел «Проекты и код»).
4. **CORS в вебе** — браузерные сборки упираются в CORS облачных API (Ollama локально обычно
   разрешает). Решение не в коде: документируем; кнопка «Проверить подключение» покажет
   причину (ошибка сети vs 401).
5. **Один `HttpClient`** — таймаут выставляется на запрос из `advanced.timeoutSeconds`
   через `HttpRequestBuilder.timeout` (`llmRequestTimeout` в `OpenAiCompatibleGateway.kt`).
   ВАЖНО: сам по себе `withTimeout` вокруг запроса недостаточен — движок CIO (jvm/android)
   режет ЛЮБОЙ запрос без `HttpTimeoutCapability` своими 15 секундами
   (`CIOEngineConfig.requestTimeout`), и медленные рассуждающие модели (Qwen/DashScope)
   падали с «Request timeout has expired … request_timeout=unknown ms». Capability на
   запросе отключает двигательный потолок; общий клиент собирается через `appHttpClient()`
   (`di/Dependencies.kt`) с установленным `HttpTimeout` (30 с на обычные вызовы,
   10 с на соединение).
6. **Секреты в файле профиля** — статус-кво (ключи в JSON); статья в доках предупреждает.
7. **Производительность переключателя** — список профилей мал (единицы), без ленивых списков.

---

## 11. Итоговая структура файлов

```
Новые:
  domain/LlmProfiles.kt          — ProviderType, EffortLevel, AdvancedSettings, LlmProfile
  domain/ProviderCatalog.kt      — ProviderSpec, ModelInfo, каталог
  domain/ProfileResolver.kt      — разрешение активного профиля
  domain/LlmProfileRepository.kt — порт хранилища профилей (в Ports.kt или отдельно)
  data/llm/LlmPayloads.kt        — чистые сборщики тел запросов
  data/llm/AnthropicGateway.kt
  data/llm/GoogleGateway.kt
  data/llm/RoutingLlmGateway.kt
  data/storage/…                 — JsonLlmProfileRepository (ключ "llm_profiles")
  ui/components/ModelSwitcher.kt — контент переключателя (общий для Popup/Sheet)
  commonTest: LlmPayloadsTest, ProfileResolverTest, LlmProfileRepositoryTest,
              ProfileMigrationTest, ProviderCatalogTest

Изменения:
  domain/Models.kt      — AppSettings (activeLlmProfileId, deprecated легаси),
                          ChatSession (llmProfileId), ProfileBundle (v2, llmProfiles)
  domain/Ports.kt       — сигнатура LlmGateway
  domain/MagicAgent.kt  — разрешение профиля, contextMessages, systemPromptOverride
  domain/SkillEducator.kt — активный профиль вместо легаси-тройки
  di/Dependencies.kt    — карта транспортов, профильный репозиторий
  ui/UiState.kt         — llmProfiles, modelSwitcherOpen
  ui/MagicPaperViewModel.kt — методы §7.3, миграция в bootstrap
  ui/screens/SettingsScreen.kt — раздел «Магические источники»
  ui/screens/ChatScreen.kt     — чип модели в Composer
  ui/screens/WelcomeScreen.kt  — шаг 1 по каталогу провайдеров
  data/docs/EmbeddedDocRepository.kt — статья про источники
  jvmMain PiCodingRuntime.kt   — writePiConfig(profile)
```
