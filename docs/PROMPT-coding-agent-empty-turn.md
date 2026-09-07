# Промпт: кодинг-агент завершает прогон пустым ответом (обрезка по max_tokens)

Работай в репозитории MagicPaper (Kotlin Multiplatform + Compose, pi-агент как кодинг-движок).
Правки — общие коды (`shared/src/commonMain`) и JVM-мост (`shared/src/jvmMain`), комментарии на русском, в стиле соседнего кода.

## Контекст бага

Прогон кодинг-агента иногда заканчивается так, что пользователь видит только
«Заклинание не сработало: Агент завершился без ответа.» и вынужден писать «продолжи».

Воспроизведено на профиле Alibaba, модель `qwen3.8-flash` (OpenAI-совместимый эндпоинт,
`https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`).

Запись `message` в JSONL сессии пи, после которой пришлось писать «продолжи»:

```
role=assistant  stopReason="length"
usage={input:1601, output:8192, reasoning:8192, cacheRead:50176, totalTokens:59969}
content=[thinking: 33 989 символов]      ← ни одного text-блока, ни одного tool call
```

То есть потолок вывода целиком сгорает в рассуждении, тело сообщения пустое, агенту
нечем выразить намерение — цикл прогона завершается молча.

## Диагноз по шагам (проверено, чинить надо здесь)

1. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/coding/PiCodingRuntime.kt`,
   функция `writePiConfig()` (~строки 703–724) пишет конфиг модели с захардкоженными
   значениями, игнорируя профиль подключения:

   ```json
   "models":[{"id":"…","reasoning":false,"contextWindow":128000,"maxTokens":8192}]
   ```

   В профиле (`~/.MagicPaper/llm_profiles.json`) стоит `advanced.maxTokens = 16384`,
   `advanced.contextLimit = 128000`; дефолт `maxTokens` у самого пи — 16384
   (`…/pi-coding-agent/docs/models.md`, таблица «Model Configuration»). Мост режет
   потолок вывода вдвое и никогда его не поднимает.

2. Тот же блок объявляет модель нерассуждающей (`reasoning:false`), хотя домен считает
   иначе: `ModelDefaults.openAiCapability()` для семейства `qwen` возвращает
   `ReasoningPresets.COMPAT_EFFORT`, и из этого же вызова в `compat` уходит
   `"supportsReasoningEffort": true`. Конфиг внутри себя противоречив.

3. Клиент пи (`…/pi-coding-agent/dist/bundle/chunks/openai-completions-*.js`,
   функция `buildParams`) отправляет любой переключатель мышления — `enable_thinking`
   (`compat.thinkingFormat:"qwen"`), `reasoning_effort`, `thinking_budget`,
   `chat_template_kwargs` — только когда `model.reasoning === true`. При
   `reasoning:false` запрос уходит без переключателей, а Qwen-совместимый сервер думает
   по умолчанию. Плюс уровень мышления из UI не доезжает до пи никогда: аргументы
   запуска (`PiCodingRuntime.run`, ~190–205) не содержат `--thinking`, поэтому
   `PI_REASONING_LEVEL=off` и `thinkingLevelMap` не задействованы.

4. Пустой ответ со `stopReason:"length"` — НЕ переполнение контекста (в примере
   59 969 токенов из 128 000), поэтому единственная ветка автопродолжения пи
   (`dist/core/agent-session.js:1852`, снятие `error|length`-сообщения) не срабатывает.
   Процесс выходит с кодом 0.

5. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/data/coding/PiEventParser.kt`,
   `parseMessageEnd()`: `stopReason` уже читается, но для `length` с пустым текстом
   возвращается `null` — причина теряется. Тогда `PiCodingRuntime.run()` не видит
   `CodingEvent.FinalText`, и по ветке `else` (~строка 258) эмитит
   `CodingEvent.Failed("Агент завершился без ответа.")`; `domain/Coding.kt:220`
   оборачивает это в «Заклинание не сработало».

Сопутствующий мёртвый код: `ReasoningPresets.PI_THINKING`
(`shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Reasoning.kt:419`,
комментарий: «Уровни мышления pi … нужны, чтобы задавать `thinkingLevelMap` для
coding-сессий») не используется нигде в проекте — он и есть заготовка для п. 3.

## Что сделать

### 1. Перестать хардкодить конфиг модели

Вынести сборку JSON в чистую функцию, доступную из общих тестов
(например `PiModelsConfig.json(profile: LlmProfile): String` в `commonMain`,
`PiCodingRuntime.writePiConfig` только пишет результат атомарно):

- `contextWindow` ← `profile.advanced.safeContextLimit`;
- `maxTokens` ← `profile.advanced.safeMaxTokens`, но с подстраховкой для рассуждающих моделей:
  если `ModelDefaults.capability(profile)` — `ReasoningCapability.Controls`,
  потолок не меньше 16384 (по аналогии с `ModelDefaults.recommendation`,
  `ModelDefaults.kt:31–35`, и с чат-путём `LlmPayloads.kt:102`, где
  `maxOf(baseMax, budget + 1024)` — там этот урок уже выучен);
- `reasoning` ← результат `ModelDefaults.supportsEffort(profile)` /
  `capability(profile)`, а не `false`;
- `compat.supportsReasoningEffort` и `reasoning` обязаны быть согласованы;
- при `reasoning:true` добавить `"thinkingLevelMap"` по вокабуляру
  `ReasoningPresets.PI_THINKING`, включая `"off": null` там, где эндпоинт не умеет
  выключать рассуждение (семантика уровней и `null` — в
  `…/pi-coding-agent/docs/models.md`, раздел «Thinking Level Map»), и
  `compat.thinkingFormat` по семейству модели (`"qwen"` для qwen-эндпоинтов —
  они принимают `enable_thinking`; для локальных qwen-серверов —
  `"qwen-chat-template"`). Не отправлять одновременно `reasoning_effort` и
  `thinking_budget`: пи-документация предупреждает, что DashScope их отвергает вместе.

### 2. Научить усилие доезжать до агента

Маппинг `profile.effort` / `profile.effortOverrides[modelId]` (`EffortSelection`) →
уровень pi и добавление `--thinking <level>` в аргументы запуска. Важно:
`EffortSelection.Default` должен давать детерминированное поведение, а не «как сервер
захочет»: для кодинг-сессий по умолчанию выбери ограниченный уровень (например
`medium`) и опиши решение комментарием.

### 3. Не терять причину: детектить обрезку

В `PiEventParser.parseMessageEnd()`:

- если `stopReason == "length"` (и если у ассистентского сообщения есть
  `usage.output`/`usage.reasoning` — использовать их) и при этом нет ни text-блоков,
  ни tool-вызовов, вернуть новый `CodingEvent`, например
  `CodingEvent.OutputTruncated(outputTokens: Int, reasoningTokens: Int)`;
- если `stopReason == "length"`, но текст есть — смарджить текст и добавить
  `CodingEvent.Notice("Ответ обрезан лимитом max_tokens")`, чтобы усечённый ответ не
  выглядел полным.

Добавить событие в `domain/Coding.kt` (`CodingEvent`) и обработчики.

### 4. Автопродолжение вместо «продолжи» руками

В `PiCodingRuntime.run()`:

- при `OutputTruncated` с пустым ответом — не показывать «Агент завершился без
  ответа», а автоматически перезапустить ход (продолжить ту же pi-сессию через
  `--session-id`, промпт вида «продолжи с начала этой точки, без повторного
  рассуждения») максимум 2 раза, с `CodingEvent.Notice` о попытке;
- если после попыток ответ всё ещё пустой — `CodingEvent.Failed` с настоящим текстом:
  «Модель израсходовала весь лимит вывода на рассуждение (8192 из 8192 токенов).
  Поднимите «максимум токенов» в профиле до ≥16384 или снизьте усилие»;
- ветка `!sawAnswer` не должна больше порождать «Агент завершился без ответа», когда
  в потоке был `OutputTruncated`;
- заодно стоит поднять потолок по умолчанию в `AdvancedLlmOptions`
  (`domain/LlmProfiles.kt:54`), если он участвует в новых профилях.

### 5. Тесты

В `shared/src/commonTest`:

- `PiModelsConfig` (или как назовёшь): для профиля `qwen3.8-flash` c
  `maxTokens=16384, contextLimit=128000` → JSON содержит
  `"maxTokens":16384`, `"contextWindow":128000`, `"reasoning":true`,
  `thinkingLevelMap`, согласованный `supportsReasoningEffort`; для нерассуждающей
  модели (`llama3.2`) → `reasoning:false` и никаких thinking-полей; для рассуждающей
  модели с `maxTokens=512` → потолок поднят (не меньше 16384);
- парсер: `message_end` с `stopReason:"length"` и `content:[thinking]` →
  `OutputTruncated`, а не `null`; с текстом → `FinalText` + `Notice`;
  `stopReason:"error"` → как сейчас (`Failed`);
- регресс на формулировку: пустой `length`-ход не даёт текст «без ответа».

## Как проверить руками

1. `export JAVA_HOME="/c/Users/kruz18/Programs/Jdks/temurin-21.0.8"` и
   `java -cp gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain
   :shared:jvmTest :shared:compileKotlinJvm :desktopApp:compileKotlin --offline`
   (в Git Bash `./gradlew` падает из-за отсутствия `cygpath`).
2. Запустить кодинг-сессию на профиле Alibaba / `qwen3.8-flash`, попросить что-то
   нетривиальное (правку с разбором кода) и убедиться, что прогон заканчивается
   внятным ответом и tool-вызовами, а не «Заклинание не сработало».
3. Проверить `~/.MagicPaper/coding/pihome/models.json` — значения должны прийти из
   профиля.
4. Проверить JSONL новой сессии в `~/.MagicPaper/coding/sessions/`: не должно быть
  `stopReason:"length"` с пустым content в середине успешного прогона.

## Рамки

- Не «чинить» простым увеличением 8192 → 16384: рассуждение съест и его (в примере
  33 989 символов thinking). Нужны и корректный флаг `reasoning`, и способ выключать
  либо ограничивать thinking, и разбор обрезки.
- Не молчать об обрезке: тихое автопродолжение допустимо, но пользователь должен
  видеть `Notice`, а повторные неудачи — явную ошибку с причиной и советом.
- Не ломать чужие незакоммиченные правки (`README.md`, `ui/UiState.kt`,
  `ui/screens/ChatScreen.kt`, `ui/screens/CodingScreen.kt`,
  `ui/components/ChatScroll.kt`) и не откатывать их.
- Не менять поведение чат-пути (`data/llm/LlmPayloads.kt`), если этого не требует
  согласованность: там усилие и бюджет уже обработаны.
