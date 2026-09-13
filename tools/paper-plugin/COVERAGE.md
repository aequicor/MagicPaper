# Покрытие Paper

Источник каталога — `PaperFixtures.kt`. Каждая запись связывает стабильный ID,
типизированные свойства/варианты и вызов публичного Paper API. Те же записи
используют каталог, `PaperFixturePreview`, именованные Preview и JVM-рендеры.

| Группа | Самостоятельные примеры |
|---|---|
| Основы | `PaperText`, `PaperCoinIcon`, `PaperDivider` |
| Контролы | `PaperButton`, `PaperIconButton`, `PaperField`, `PaperSwitch`, `PaperCheck`, `PaperChoice` |
| Статусы | `PaperProgress`, `PaperIndeterminateProgress`, `PaperStatus`, `PaperActivityIndicator`, `PaperActivityIndicatorButton`, `PaperContextIndicator` |
| Списки | `PaperListRow`, `PaperTreeRow`, `PaperTreeGroupHeader`, `PaperPluginRow` |
| Навигация | `PaperTab`, `PaperLink`, `PaperToolbarButton` |
| Контейнеры | `PaperPanel`, `PaperSurface`, `PaperList`, `PaperScrollArea`, `PaperStatusPanel`, `PaperApprovalDock`, `PaperQuestionnaire`, `PaperWizard`, `PaperScheduleEditor` |
| Текст | `PaperMarkdown`, `PaperCodeBlock`, `PaperReader`, `PaperFadingText` |
| Сообщения | `PaperChatTranscript`, `PaperSystemMessage`, `PaperPinnedMessage`, `PaperAttachmentChip`, `PaperChatPlainText`, `PaperMessagePreview`, `PaperSessionContextMessage` |
| Рабочая область | `PaperComposer`, `PaperWorkspaceComposer`, `PaperPromptField`, `PaperWorkSurface`, `PaperWorkspaceHeading`, `PaperResizablePanels` |
| Граф | `PaperGraph`, `PaperGraphNode`, `PaperGraphTerminal` |
| Наложения | `PaperMenu`, `PaperDialog`, `PaperTooltip` |

Всего 54 примера. Все поддерживают профили macOS/Windows и масштаб текста
100/150/200%. `PaperButton` показывает весь `PaperControlState` и четыре вида кнопки;
поля — normal/disabled/error; переключатели, строки и icon buttons — доступность и
выбор по применимости. Точные доступные состояния заданы дескрипторами, не отдельным
списком. Пустой/длинный текст задаётся обычным свойством. Прогресс ограничивается 0…1.
Меню, диалог и tooltip на холсте представлены своим opener; раскрытие проверяется
в живом предпросмотре. Составные слоты наполнены изолированными примерами.

`PaperAction`, `PaperInput`, `PaperToggle`, `PaperComposerField`, `PaperMarkdownBody`
покрываются публичными оболочками/составными примерами. Перегрузки с content-слотом
не дублируют записи. `PaperChatMarkdown` — асинхронный chat host: в каталоге
его статический текстовый сценарий представлен `PaperMarkdown`, streaming/lazy
сценарии остаются в проверках владельца. Модели, токены, темы, density/policy, FocusRestorer,
FocusAnchor, lifecycle registration, hover/row-menu helpers, ContentEntrance и
scroll/streaming/inline-expansion hosts не имеют самостоятельной карточки: они
обслуживают композицию или жизненный цикл. `PaperBackground` — эффект окна с
native lifecycle/environment; его проверяют тесты :designSystem, вне каталога
изолированных контролов. Каталог не объявляет покрытие всех анимаций/комбинаций
слотов или платформенных window/menu hosts.

Именованные Preview (`PaperPreviews.kt`, группа `Paper plugin`):
`PaperButtonPreview`, `PaperFieldErrorPreview`, `PaperComposerNarrowPreview`,
`PaperEmptyFieldPreview`. Матрица `PaperPluginRenderTest` добавляет все 54 default,
button states (включая disabled/selected/busy), field states, empty/long/narrow и
200% Windows для поля, composer и текста. Результат — 71 PNG в
`build/reports/paper-plugin/renders`; изображение само по себе не доказывает
клавиатурное или native-поведение.

`PaperPreviewInteractionTest` проверяет pointer/Enter, ввод текста, disabled и
открытие/закрытие меню и диалога через видимые действия. Native popup key dispatch
проверяется отдельно от ImageComposeScene. `PluginCanvasGeometryTest` проверяет
zoom/pan, clipping и перекрытие в настоящем Canvas. `PluginRenderSessionTest`
проверяет reuse, вытеснение, retry, отмену и закрытие проекта во время рендера.
