# Единая лента чат- и кодинг-сессий

Объём: сообщения и поле ввода обычного чата приведены к текущему кодингу.
Общие элементы: `paperConversationMessage`, `CodingComposer` с
`PaperWorkspaceComposer` / `PaperPromptField`, `CodingModelChip`.
Лента проходит под плавающим вводом; её нижний отступ учитывает фактическую
высоту ввода. Индикатор работы занимает отдельную строку ленты.
Кнопка «+» в обоих видах сессий растёт вместе со шрифтом.

## Визуальная приёмка

Маршрут: обычный чат → существующая переписка или новый чат.
Изолированные превью: [SessionComposerPreviews.kt](../../feature/session/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/SessionComposerPreviews.kt),
группа `Session transcript`: `ChatTranscriptPreview`, `EmptyChatTranscriptPreview`,
`BusyChatTranscriptPreview`. Фикстуры не запускают сервисы, сеть или запись истории.

Осмотрены рендеры ImageComposeScene на macOS, плотность 1:

| Состояние | Размер / текст | Рендер |
| --- | --- | --- |
| Обычный чат | 1000×700 / 100% | [wide](../../feature/session/impl/build/reports/chat-transcript/wide.png) |
| Узкий чат | 390×700 / 100% | [narrow](../../feature/session/impl/build/reports/chat-transcript/narrow.png) |
| Крупный текст | 720×900 / 200% | [large-text](../../feature/session/impl/build/reports/chat-transcript/large-text.png) |
| Пустой чат | 390×700 / 100% | [empty](../../feature/session/impl/build/reports/chat-transcript/empty.png) |
| Работа / действия недоступны без ввода | 390×700 / 100% | [busy](../../feature/session/impl/build/reports/chat-transcript/busy.png) |
| Длинная лента, шесть строк ввода | 640×700 / 100% | [multiline](../../feature/session/impl/build/reports/chat-transcript/multiline.png) |

Проверены общие направляющие, запрос справа / ответ слева, цвета и отступы,
читаемость последнего сообщения, видимость отправки и кнопки возврата вниз.
Контраст непрозрачных поверхностей: текст/запрос 9,31:1, текст/ответ 10,39:1;
вторичный текст на градиенте ввода — не ниже 4,75:1; край индикатора/фон — 5,90:1.
Отправка, вложения и выбор модели по-прежнему доступны напрямую.

## Проверки

PASS: 23 JVM-теста — `ChatTranscriptDesignTest`, `ChatLongMessageRenderTest`,
`ChatComposerAttachmentRenderTest`, `ChatComposerLargeAttachmentRegressionTest`,
`CodingComposerRenderTest`, `ChatChatScrollToBottomTest`, `RequestPinsRenderTest`.
Проверены рост поля, возврат вниз, раскрытие длинного ответа, вложения,
закрепы, пауза, уточнение и очередь. Изображения выше создаёт `ChatTranscriptDesignTest`.
После разрешения конфликта сохранены параметры отступов для внешнего поля ввода
и тест `floatingComposerLeavesTheLastMessageReadable` из ветки назначения.
Заданные отступы и измеренная высота встроенного поля учитываются через максимум,
без двойного сложения.

PASS: `:feature:session:impl:compileKotlinJs`, `compileKotlinWasmJs`,
`compileAndroidMain`; `verify-design-system.py --self-test`,
`verify-map.py --self-test`, `git diff --check`.
Для Android использован установленный SDK через
`ANDROID_HOME=/Users/aequicor/Library/Android/sdk`.
Карта UI обновлена, включая ранее устаревшие позиции деклараций и отсутствовавшие
привязки существующих элементов восстановления; их реализация не менялась.

NOT_RUN: установленное приложение, нативный Windows, браузер/Android runtime,
screen reader и измерение FPS при живом streaming. Рендеры и проверки семантики
не устанавливают качество нативного оконного поведения или плавность анимации.
