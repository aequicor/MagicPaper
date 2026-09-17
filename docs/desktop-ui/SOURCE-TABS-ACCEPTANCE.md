# Вкладки источников и действия редактора

Исследование → «Источники»: вкладки «Для чата» / «Для вопроса» заменяют
раскрывающиеся группы. Счётчики видны постоянно. Поиск фильтрует название, URL
и имя файла; общий чекбокс меняет всю текущую область, включая скрытые фильтром
источники. Выбор другой вкладки и скрытие панели не изменяют выбранные источники.
Создание и внешний поиск начинают работу в активной области.

В исследовательском и проектном редакторах «Прикрепить» открывает выбор файлов
сразу, «Параметры» раскрывает настройки под редактором. Они заменяют нижнюю
иконку раскрытия; угловой контрол изменения высоты редактора сохранён.
В исследовательском редакторе внешний отступ, включая низ, увеличен с 4 до 12 dp,
внутренний вертикальный — с 4 до 8 dp. При тесной ширине действия переносятся,
сохраняя подписи и доступность отправки.

| Область | Превью и проверка | Рендер |
|---|---|---|
| Источники | `ResearchSourcesPanePreviews.kt`, группа `Research sources`: шесть источников, частичный выбор, HTTP 403, пустая вкладка, сохранение; 340×500, 240×580, 400×850 с текстом 200% | [обычный](../../feature/session/impl/build/reports/source-tabs/sources.png), [узкий](../../feature/session/impl/build/reports/source-tabs/narrow.png), [крупный текст](../../feature/session/impl/build/reports/source-tabs/large-text.png), [пустой](../../feature/session/impl/build/reports/source-tabs/question-empty.png) |
| Редактор | `ResearchWorkspacePreview`, группа `Research`; `SessionComposerPreviews.kt`, группа `Session input`; `ComposerOptionsRenderTest`: чат/проект, доступный/неактивный движок, 390/720 px, текст 100/200% | [обычный](../../feature/session/impl/build/reports/composer-options/chat-720-1.0.png), [узкий](../../feature/session/impl/build/reports/composer-options/chat-390-1.0.png), [узкий с крупным текстом](../../feature/session/impl/build/reports/composer-options/chat-390-2.0.png) |
| Рабочая область | `ResearchWorkspaceRenderTest`: переключение областей, выбор всех при фильтрации, создание файлов в нужной области, удаление, возврат из поиска, сохранение черновика | [полное окно](../../feature/session/impl/build/reports/research-workspace/reading.png) |

Рендеры проверены через `ImageComposeScene` на macOS, density 1; это реальные
Compose-компоненты с изолированными данными, без сети и пользовательской истории.
Проверены общий hover строк, отдельное открытие HTTPS-домена, клавиатурный доступ
к меню, постоянное удаление недоступного источника, совпадение линий вкладок при
переносе подписи, наличие нижнего отступа и неизменность редактора/черновика.

Проверки: `PaperResearchSourceControlsTest`, `PaperComposerOptionsTest`,
`ResearchSourcesPaneRenderTest`, `ResearchWorkspaceRenderTest`,
`ComposerOptionsRenderTest`, `ChatWorkspacePresentationStoreTest`,
`CodingComposerRenderTest`, `ChatComposerAttachmentRenderTest`,
`ChatTranscriptDesignTest`; Paper API и карта поверхностей — `--self-test`.
Компиляция общих изменений: JVM, Android, JS, Wasm. Desktop distributable собран.

Нативный Windows, экранный диктор и замеры FPS этой версии не выполнялись.
Ленивый список, стабильные ключи и существующая анимация панели сохранены;
статические рендеры не доказывают плавность нативного окна.
