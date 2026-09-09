# Material и Markdown: граница миграции

Статический baseline: Kotlin 2.4.10, Compose Multiplatform 1.11.1, Material3 1.11.0-alpha07, markdown renderer 0.41.0, JBR API 1.9.0. Источник — `gradle/libs.versions.toml`, `shared/build.gradle.kts`, `desktopApp/build.gradle.kts`, `settings.gradle.kts`. `:shared` сейчас имеет прямые commonMain `implementation(libs.compose.material3)`, `implementation(libs.markdownRenderer.m3)`, `implementation(libs.markdownRenderer.code)`; desktopApp зависит от shared и Compose Desktop runtime. Самостоятельного DS-модуля пока нет.

Полный список Material-импортов по файлам, symbol→Paper replacement и номера строк находятся в [inventory.json](inventory.json) / [MIGRATION.md](MIGRATION.md). Сканируется весь production Kotlin всех четырёх модулей, в том числе commonMain и jvmMain. Импорты с `*` встречаются в плагинах и настройках: просто подсчитать строки `import Material` недостаточно. Найдены 47 файлов с импортами и 1743 строк-ссылок на Material-символы (не 1743 уникальных вызовов). Записи помечены `call/reference` и `wildcard-candidate/import-reference`; это лексическая карта, не результат разрешения символов Kotlin compiler. Возможные одноимённые локальные функции требуют ручного review при миграции. Feature-цвета и интерактивные Foundation-примитивы также нужно переносить по контракту, даже если Material-импортов нет.

## Фактический JVM graph

`./gradlew :shared:dependencies --configuration jvmRuntimeClasspath --console=plain` успешно выполнился после штатного разрешения на запуск Gradle вне sandbox. Полный вывод — [gradle-jvm-dependencies-authorized.txt](gradle-jvm-dependencies-authorized.txt); исходный sandbox failure сохранён отдельно. В graph нет FAILED. Это dependency report, не компиляция и не UI-тест.

| Узел | Разрешённая зависимость | Место миграции |
|---|---|---|
| shared commonMain | material3 → material3-desktop 1.11.0-alpha07 | Только внутренняя реализация DS |
| renderer-m3 0.41.0 | renderer-m3-jvm 0.41.0 → renderer / renderer-jvm 0.41.0 | PaperMarkdown внутри DS |
| renderer / renderer-m3 | org.jetbrains:markdown → markdown-jvm 0.7.3 | AST/parser внутри Markdown adapter |
| renderer-code 0.41.0 | renderer-code-jvm → renderer-jvm + dev.snipme:highlights/highlights-jvm 1.1.0 | PaperCodeBlock внутри DS |
| renderer-jvm | kotlinx-collections-immutable (запрошено 0.4.0; разрешение смотреть в полном graph) | Внутренняя транзитивная зависимость |

Локальные POM (с координатами, cache-relative path, лицензиями и объявленными зависимостями) сохранены в [markdown-poms.json](markdown-poms.json). Это снимок доступного обычного Gradle cache: он включает также 0.45.0/0.7.9 от других сборок, **не версии текущего проекта**. POM не всегда перечисляет Compose-зависимости, публикуемые через Gradle module metadata; поэтому фактический JVM graph выше имеет приоритет. POM renderer 0.41.0 и highlights 1.1.0 указывают Apache-2.0; доступные POM JetBrains markdown 0.7.9 также Apache-2.0, но не доказывают лицензию конкретного 0.7.3. Перед распространением/копированием его кода проверить LICENSE соответствующего артефакта; ничего стороннего здесь не скопировано в production.

## Неочевидные точки Markdown

`ChatMarkdown.kt` импортирует `com.mikepenz.markdown.m3.Markdown`, `markdownTypography`, M3 `MarkdownCheckBox`, а также базовые MarkdownElement/components/lists и code/highlight extensions. Замена одного вызова Markdown без переноса typography/checkbox/code callbacks оставляет обход DS. Наружу нужен `PaperMarkdown` с текстом и нейтральными опциями/событиями, без M3 types.

`MarkdownDocument.kt` хранит parser/AST и document/preview состояние; `MarkdownBlocks.kt` режет AST на блоки, списки, таблицы и диапазоны кода. `MessagePreview.kt` открывает reader/диалог, `StreamingText.kt` отвечает за потоковый текст. Вызовы ChatMarkdown и MarkdownDocumentBody входят в chat/coding/planning/session-context/pins. Точные все 125 Markdown-упоминаний (включая comments, imports, parser и callers) — `markdownReferences` в inventory.json.

Стратегия: сначала DS-обёртка и перенос renderer callbacks, затем обновление всех callers. AST helpers допускается оставить невизуальными в отдельном neutral adapter, но renderer и Material typography должны жить внутри DS. При переносе не переписывать парсер и streaming segmentation без необходимости. Проверить заголовки, nested lists, task-checkbox, таблицы с пустыми ячейками, code fence/language/highlighting/copy, links, длинные сообщения, preview expansion, незавершённый markdown при streaming, selection и scroll anchor. Выделение текста/копирование кода важнее имитации HTML-вёрстки.

Android/JS/Wasm используют common API DS. На этом этапе JVM graph проверен, остальные платформенные графы и компиляция после будущего переноса ещё не проверены. Нельзя удалять M3 из shared раньше готовности совместимой DS-реализации и обновления всех targets.
