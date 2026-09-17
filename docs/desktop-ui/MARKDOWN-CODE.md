# Блоки кода в ответах

Один fenced/indented code block отображается одной карточкой. Раньше разделение
Markdown для ленивого списка создавало новую карточку каждые 1536 символов или
32 строки. Теперь эти границы принадлежат внутренней прокрутке кода. Фрагменты
до 64 строк и 16 384 символов остаются развёрнутыми; более длинные имеют внутренний
ленивый viewport высотой 448 dp, общую горизонтальную прокрутку и одну шапку.
Границы обычных строк сохраняются; патологически длинные строки переносятся
ограниченными порциями без потери символов. Копирование всегда забирает весь код.

Шрифт кода: JetBrains Mono, 10 sp / 14 sp. Системное увеличение текста сохраняется.
Подсветка использует контрастные Paper colors; фон — непрозрачный raisedSurface.
Inline code в обычном тексте сохраняет прежний размер.

## Название файла

[CommonMark](https://spec.commonmark.org/0.31.2/#fenced-code-blocks) оставляет
интерпретацию info string рендереру. Поддержан формат
[Docusaurus code titles](https://docusaurus.io/docs/markdown-features/code-blocks#code-title):

````markdown
```kotlin title="src/main/kotlin/Example.kt"
val answer = 42
```
````

Также поддерживаются `filename=` и `file=`. Это только подпись: она не открывает
и не создаёт файл. Для существующих ответов отдельный абзац с именем/путём файла
непосредственно перед кодом переносится в шапку. Обычная проза, ссылки и подпись,
конфликтующая с явным title, остаются на месте. Исходный Markdown не изменяется.

## Визуальная и интерактивная приёмка

Маршрут: исследовательский чат → ответ с кодом, а также обычный Markdown в чате.
Открыть существующий ответ с `src/main/resources/application.yml` перед YAML
или пример на 48 строк. Ожидается одна карточка: путь слева, язык и копирование
справа, одна линия разделителя; код 10 sp. При узком окне/масштабе 2× путь
сокращается с tooltip, копирование остаётся доступным. Для 1000 строк прокрутить
в конец и скопировать: последние строки достижимы, в буфере весь код.

Named previews, группа **Markdown code**:
`PaperMarkdownCodePreview` — filename/default 640 dp, narrow 320 dp,
large text 480 dp / 2×; `PaperMarkdownLongCodePreview` — 1000 строк.
Файл: `designSystem/src/commonMain/kotlin/io/aequicor/magicpaper/designsystem/PaperMarkdownCodePreviews.kt`.

Compose/AWT renders и semantics tests (`PaperMarkdownCodeTest`):

- [Обычный](../../designSystem/build/reports/markdown-code/filename-640-1.0.png)
- [Узкий](../../designSystem/build/reports/markdown-code/filename-320-1.0.png)
- [Текст 2×](../../designSystem/build/reports/markdown-code/filename-480-2.0.png)
- [48 строк без разрывов](../../designSystem/build/reports/markdown-code/medium-one-card.png)
- [Конец 1000 строк](../../designSystem/build/reports/markdown-code/long-code-end.png)

Проверки: однозначность подписи и metadata, отсутствие изменения source,
единое копирование, размер текста, ограниченный объём layout и доступ к последней
строке, контраст >=4.5:1. `PaperMarkdownHighlightTest` отдельно проверяет
24 независимых блока и смену потоковых ревизий без смешивания диапазонов.
`MarkdownBlocksTest` сохраняет проверки текста/списков/таблиц, проверяет целостность
закрытых/незакрытых fenced и indented blocks. `ResearchScrollRegressionTest`
проверяет прокрутку готовой статьи и во время стриминга.

Compose render и headless wheel replay не доказывают native FPS.
macOS bundle собирается; отдельный запуск установленного приложения, Windows,
Android и браузерное взаимодействие остаются платформенной проверкой.
