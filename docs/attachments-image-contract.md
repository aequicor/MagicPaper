# Контракт изображений в кодинг-сессии

Статус: исследование состояния на 2026-09-10. Этот документ описывает только
отображение уже существующих входов и результатов; он не добавляет генерацию или
редактирование изображений.

## Подтверждённая цепочка входа

Это карта фактически доступных источников. Ссылка в последнем столбце — точка,
где утверждение проверяется в исходниках, а не обещание нового источника.

| Источник | Рантайм-событие / действие | Хранение | Что доступно UI сейчас | Доказательство |
|---|---|---|---|---|
| Выбор файла | `DesktopFilePicker.pickFiles` или `BrowserFilePicker.pickFiles` возвращает `PickedFile`; `MagicPaperViewModel.pickAttachments` создаёт `Attachment` | До отправки — состояние composer; в checkpoint — полный `Attachment` с base64 | `PendingAttachmentsRow` декодирует растровую миниатюру; после отправки `CodingAttachments` показывает только чип метаданных | [picker→Attachment](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/MagicPaperViewModel.kt#L531-L546), [desktop read](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/storage/DesktopFilePicker.kt#L33-L51), [web read](../shared/src/webMain/kotlin/io/aequicor/magicpaper/data/storage/BrowserFilePicker.kt#L28-L74), [composer thumbnail](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/components/AttachmentViews.kt#L198-L234) |
| Вставка файла или bitmap из desktop clipboard | `clipboardFileReader`; bitmap конвертируется в PNG, затем идёт тем же путём, что и выбор файла | То же; вставка файла читает ограниченное число байтов, bitmap сначала ограничен пикселями | То же, имя bitmap — `clipboard.png` | [paste→Attachment](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/MagicPaperViewModel.kt#L550-L569), [clipboard reader](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/storage/DesktopFilePicker.kt#L56-L95) |
| Запрос к кодинг-агенту | `sendCodingPromptTo` создаёт `CodingRunCheckpoint(messageId, prompt, attachments)`; `launchCodingRun` записывает USER `CodingMessage` | checkpoint содержит полные байты на время незавершённого прогона; история — `AttachmentMeta(name, mimeType, sizeBytes, kind, path)` | У USER-сообщения отображается имя/размер/вид; `path` при этой записи пуст | [new checkpoint](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/MagicPaperViewModel.kt#L1540-L1547), [append and runtime dispatch](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/MagicPaperViewModel.kt#L1617-L1654), [stored schema](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt#L621-L647), [chip-only history UI](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/components/AttachmentViews.kt#L290-L306) |
| Pi получает вход | `PiCodingRuntime.materializeAttachments` кладёт байты в `~/.MagicPaper/coding/uploads/<sessionId>/`; абсолютные пути добавляются в промпт | Файл вне проекта, имя уникализируется; путь не возвращается в `AttachmentMeta` | Агент может прочитать входной файл; UI не связывает созданный файл с карточкой истории | [materialize call](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/coding/PiCodingRuntime.kt#L225-L232), [managed path and prompt](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/coding/PiCodingRuntime.kt#L1069-L1102) |
| Codex получает вход | `CodexAppServerOpenAiSubscription.buildCodingInput` добавляет `{type:"image", url:"data:<mime>;base64,..."}` | Только в RPC `turn/start`; отдельного файлового пути нет | Нет события, подтверждающего, что модель фактически обработала изображение | [input blocks](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/llm/CodexAppServerOpenAiSubscription.kt#L531-L550) |
| Computer Use screenshot | `DesktopComputerUse` создаёт PNG `Attachment` и отправляет image content в инструмент; `ComputerUseState.preview` хранит последний preview | Транзитное состояние компьютерного инструмента, не `CodingMessage`/`CodingStep` | Отдельная панель Computer Use может показать preview; лента кодинга не имеет изображения | [screenshot attachment](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/computer/DesktopComputerUse.kt#L139-L152), [no image field in event](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt#L487-L588) |

## Подтверждённая цепочка результата агента

`DesktopCodingRuntime` передаёт adapter events как `CodingEvent`; `CodingRunRecorder`
собирает их в `CodingStep`, а `launchCodingRun` сохраняет итог в AGENT
`CodingMessage.steps`. `CodingStepRow` отображает текст и инструментальные шаги.
Структуры не содержат image payload: у события есть только text/tool/callId/result
([CodingEvent](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt#L487-L588)),
у шага — `result: String` и `callId: String`
([CodingStep](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt#L595-L613)).

Для Codex `CodingAccumulator` сопоставляет provider item ID с `CodingStep.callId`:

| Provider item | Событие | Сохраняемые поля |
|---|---|---|
| `agentMessage`, `reasoning` | `MessageStarted`, `FinalText`, `FinalThinking` | текст, `callId=item.id` |
| `commandExecution`, `fileChange`, `webSearch`, `mcpToolCall` | `ToolStarted` / `ToolProgress` / `ToolFinished` | тип шага, title, `callId=item.id`, текстовый preview результата |
| `mcpToolCall.result.content[]` с `type:"image"` | Нет представления в `CodingEvent` | Не сохраняется: adapter выбирает только блоки `type:"text"` ([adapter](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/llm/CodexAppServerOpenAiSubscription.kt#L1001-L1010)) |

Следовательно, на момент исследования **нет подтверждённого источника изображения
результата в `CodingStep`**. Ни Pi JSON protocol, ни Codex adapter не несут bitmap,
data URL, MIME или ссылку на image-result. Упоминание в имени файла, tool title,
`resultPreview` или произвольном тексте не является результатом изображения и не
должно включать его миниатюру.

## Идентичность: минимальный типизированный контракт для реализации

Следующие этапы должны вводить payload только с этой семантикой (названия можно
сохранить или адаптировать, но поля и связи обязательны):

```kotlin
/** One submitted user turn; created before any adapter/provider item exists. */
@Serializable
data class CodingImageInvocation(
    val invocationId: String,         // CodingRunCheckpoint.runId; immutable across recovery
    val sessionId: String,
    val inputMessageId: String,       // CodingRunCheckpoint.messageId / USER CodingMessage.id
    val responseMessageId: String,    // CodingRunCheckpoint.responseId / AGENT CodingMessage.id
    val responseTimelineId: String? = null, // AGENT CodingMessage.timelineId after recorder persists it
)

@Serializable
data class CodingImageRef(
    val imageId: String,              // новый стабильный UUID, не имя и не hashCode
    val origin: CodingImageOrigin,    // USER_ATTACHMENT или TOOL_RESULT
    val sessionId: String,
    val invocationId: String,         // == CodingImageInvocation.invocationId
    val ownerMessageId: String,       // inputMessageId for USER, responseMessageId for TOOL_RESULT
    val timelineId: String? = null,   // == responseTimelineId only for TOOL_RESULT
    val callId: String? = null,       // обязателен только для TOOL_RESULT
    val mimeType: String,
    val byteSize: Long,
    val locator: ImageLocator,        // безопасный, не произвольный local path
)

@Serializable enum class CodingImageOrigin { USER_ATTACHMENT, TOOL_RESULT }
```

Инварианты:

1. При отправке создаётся один `CodingImageInvocation`: `invocationId` берётся из
   `CodingRunCheckpoint.runId`, `inputMessageId` — из `messageId`, а
   `responseMessageId` — из сгенерированного `responseId`. Это уже существующая
   связь checkpoint с USER/AGENT историей: [checkpoint fields](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt#L83-L95),
   [dispatch/persistence](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/MagicPaperViewModel.kt#L1617-L1654).
2. `USER_ATTACHMENT` обязан иметь тот же `(sessionId, invocationId)` и
   `ownerMessageId == inputMessageId`. У входа `callId == null`: он ещё не является
   результатом tool call. Поэтому конкретный запуск обозначается invocation, а не
   именем, порядком вложения или будущим timeline ID.
3. `TOOL_RESULT` принимается только из структурированного provider/MCP image блока,
   с тем же `sessionId`, `invocationId`, `timelineId` и `ownerMessageId`, что у
   породившего AGENT сообщения. `callId` обязан совпасть с `CodingStep.callId`;
   текст, filename, tool name и data-looking substring не создают ref.
4. `imageId` — стабильная сущность истории; provider item ID допускается только как
   `callId`, а ID шага (`CodingStep.id`) — presentation ID и не заменяет image ID.
5. Один вызов может иметь несколько refs; отсутствие ref не означает, что вызов не
   работал с изображением. Наличие USER_ATTACHMENT также не доказывает обработку
   моделью.

### Правила locator и чтения

`ImageLocator` — закрытый сериализуемый тип, а не строка:

```kotlin
@Serializable sealed interface ImageLocator {
    @Serializable data class ManagedBlob(val key: String) : ImageLocator
    @Serializable data class InlineBase64(val data: String) : ImageLocator
}
```

При чтении UI/data layer применяет правила в этом порядке:

1. Принимает только `ManagedBlob.key`, созданный приложением для этого
   `(sessionId, invocationId, imageId)`, или `InlineBase64` из уже принятого
   structured image block. `file:`, `http(s):`, absolute path, `..`, relative path
   и строка из model/tool text не парсятся как locator.
2. Для managed blob проверяет namespace ровно по трём ID выше, запрещает выход из
   managed root после canonicalization и отклоняет symlink. Для inline декодирует
   base64 только после ограничения decoded size.
3. До `decodeToImageBitmap` проверяет MIME+signature, 1..12 MiB и не более
   16,000,000 pixels. Только после этого создаёт bitmap off UI thread; cache держит
   максимум 8 bitmap. Никаких чтений project directory, Pi uploads path или
   `AttachmentMeta.path` для history preview нет.
4. Ошибка locator validation, read, base64, MIME/signature, size, pixels или decoder
   возвращает `ERROR` preview без повторной попытки с другим locator; ref и история
   остаются видимыми как error/chip. Отсутствующий blob старой истории — тот же
   controlled error, не migration failure.

## Форматы, доступ и лимиты

Подтверждённая классификация `AttachmentKind.IMAGE`: MIME `image/*` либо
расширение `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, `.bmp`, `.svg`. Это не равно
поддержке рендеринга. Миниатюра допускает лишь PNG, JPEG, GIF, WebP и BMP с
проверкой сигнатуры; SVG намеренно не декодируется. До decoder проверяются:

- размер исходных байтов 1..12 MiB;
- размер изображения не более 16,000,000 пикселей;
- LRU cache максимум 8 `ImageBitmap`.

Выбор ограничен 8 вложениями на сообщение, 20 MiB на файл desktop и 3 MiB web.
Desktop clipboard дополнительно отклоняет raster больше 40,000,000 пикселей до
создания PNG. Browser picker сейчас читает файл через `FileReader` без явной
проверки `maxFileBytes` — это известная дырка, а не разрешение читать больше.

Ошибки/деградация: неверная сигнатура, неподдерживаемый raster, лимиты bytes/pixels
и ошибка decoder дают `ERROR` thumbnail; сообщение и прочие данные не удаляются.
Отказ пикера/clipboard показывается как notice. Для старой истории без image refs
UI обязан сохранить существующие `CodingAttachments` chips и не пытаться угадать
картинку по `AttachmentMeta.path`, имени или MIME.

## Точные сценарии следующих этапов

| Проверка | Подготовка и действие | Обязательный результат |
|---|---|---|
| Raster composer | По одному валидному PNG/JPEG/GIF/WebP/BMP из picker и clipboard; отдельно SVG и PNG с неверной signature/MIME | Первые пять имеют `READY`; SVG и mismatch — `ERROR`; decoder не вызывается для error cases |
| Вход конкретного запуска | Отправить image-only prompt, сохранить checkpoint, перезапустить приложение и выполнить recovery | Ровно один invocation с теми же `sessionId`, `invocationId`, `inputMessageId`; USER ref принадлежит этому invocation, не новому |
| Коллизии имён | Две `same.png` в одной сессии и один повторный prompt | Все refs имеют разные `imageId`; разные запуски имеют разные `invocationId`, даже при одинаковых filename/payload |
| Результат вызова | Fixture structured MCP image block с `callId=A`, плюс step `A` и соседний step `B` | `TOOL_RESULT` появляется только у шага A и несёт A; text-only block, title `imagegen` и data URL в text не создают ref |
| Безопасность locator | Передать чужой namespace, absolute/relative traversal, symlink, invalid base64, >12 MiB, >16M pixels и bad signature | Ничего за managed root не читается; для каждого случая — controlled `ERROR`, без fallback на path/text |
| Легаси история | JSON `CodingMessage.attachments` без новых invocation/ref полей и `AttachmentMeta.path` с локальным путём | История загружается; отображается прежний chip-only UI; path не читается и thumbnail не угадывается |

Существующие близкие проверки: [AttachmentTest.kt](../shared/src/commonTest/kotlin/io/aequicor/magicpaper/domain/AttachmentTest.kt),
[ClipboardAttachmentsTest.kt](../shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/storage/ClipboardAttachmentsTest.kt),
[LlmPayloadsTest.kt](../shared/src/commonTest/kotlin/io/aequicor/magicpaper/data/llm/LlmPayloadsTest.kt),
[CodingResumeTest.kt](../shared/src/commonTest/kotlin/io/aequicor/magicpaper/ui/CodingResumeTest.kt).

## Неизвестное / не поддерживается

- App-server schema и доступность image content для Codex MCP/result blocks не
  подтверждены текущим adapter; сначала нужен зафиксированный fixture raw event.
- У runtime нет durable blob store для image results и нет схемы migration для refs.
- `AttachmentMeta.path` документирован, но в current `launchCodingRun` вызывается
  `asMeta()` без пути; Pi materialization не возвращает его вызывающему коду.
- Нет доказательства delivery/vision обработки модели: транспорт input подтверждает
  только отправку в adapter.
- План [PLAN-attachments.md](PLAN-attachments.md) описывает прежнюю цель, но не
  заменяет этот контракт: его предположение о пути в журнале не соответствует
  фактическому вызову `asMeta()`.
