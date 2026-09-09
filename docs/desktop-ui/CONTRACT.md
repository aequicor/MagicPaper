# Контракт desktop-интерфейса MagicPaper

Актуальные брендовые решения: [BRANDBOOK.md](BRANDBOOK.md). Модуль `:designSystem` реализован; ниже сохранён исходный исследовательский контракт и исторические значения палитры. Текущее покрытие источников — [SURFACE-MAP.md](SURFACE-MAP.md).

Дата проверки: 2026-09-09. Этап 1, task `4aa76be6-198a-4448-8af5-69bd0c97e7f2`.

Принято пользователем: сохранить пергаментный бренд; адаптировать взаимодействие, плотность и навигацию к macOS/Windows; весь desktop UI, включая встроенные плагины, использует дизайн-систему. Android и web остаются работоспособными без самостоятельного редизайна. Здесь фиксируются требования для реализации, а не заявление, что текущий UI уже им соответствует.

## Проверяемые артефакты

| Критерий | Доказательство |
|---|---|
| ac-research-map | [SURFACE-MAP.md](SURFACE-MAP.md): **183 отдельные явные связи**, 180 composable-объявлений (174 функции, 4 getter, 2 локальные композиции) + 3 entry, каждое с конкретными Paper API и этапом. [surface-bindings.json](surface-bindings.json) — редактируемые назначения; [map-verification.json](map-verification.json) — проверка точного покрытия, словаря компонентов и постоянных ID этапов, включая отрицательные контроли. [MIGRATION.md](MIGRATION.md) / [inventory.json](inventory.json) дополняют карту всеми 85 UI-файлами и 415 функциями, Material и Markdown. `audit.py` запускает также `verify-map.py`. |
| ac-research-contract | Этот контракт: токены, платформенная матрица, сценарии приёмки. [SOURCES.md](SOURCES.md): первичные источники, лицензии, выводы и неизвестное раздельно. [DEPENDENCIES.md](DEPENDENCIES.md): Markdown и граница Material. |
| ac-research-baseline | [baseline.json](baseline.json): staged/unstaged/untracked, HEAD и хеши всех 548 отслеживаемых файлов. Четыре изменения восстановления помечены `foreignChanges`; патчи сохранены отдельно. [VERIFICATION.md](VERIFICATION.md): команды, результаты и ограничения. |

## Архитектура и порядок миграции

Будущий KMP-модуль `:designSystem` предоставляет `PaperTheme`, семантические токены и компоненты из карты. Имена Paper* — контракт назначения, ещё не существующие API. Модуль поддерживает те же targets, что `shared`: JVM, Android, JS, Wasm. `shared` зависит от DS; DS не зависит от `shared`, ViewModel, репозиториев, доменных моделей или плагинов. Ресурсы шрифтов переносятся в DS либо независимый ресурсный слой без циклов.

`commonMain` содержит визуальные примитивы и семантику; JVM-адаптер определяет платформу один раз и предоставляет поведение macOS/Windows. Остальные targets получают совместимый профиль. Feature-компоненты композируют DS; доменная логика и состояние остаются в feature. Публичные API DS не возвращают `ColorScheme`, `Typography`, `ButtonColors` или другие Material-типы. Layout/Foundation допустимы в feature, но новые собственные интерактивные примитивы, цвета, размеры контролов и состояния фокуса должны появляться в DS.

Material 2/3, включая FQ-вызовы, alias и wildcard-импорты, разрешён только внутри DS. Markdown M3 и его расширения также инкапсулируются там. Прямые Gradle-зависимости Material/Markdown renderer в `shared` удаляются после переноса. Транзитивное присутствие Material в runtime допустимо, пока он является внутренним renderer DS. Архитектурная проверка этапа 8 должна охватывать все source sets, desktopApp, встроенные плагины и build-файлы; временные исключения требуют списка с владельцем и сроком удаления. Нельзя объявить миграцию готовой только по замене импортов.

| Этап | Результат и зависимость |
|---|---|
| 2 — скилл, `92610538-166a-4b52-b613-551b223d6586` | Собственный узкий скилл по этому контракту; примеры Compose, проверка применимости, источники и локальная установка. |
| 3 — модуль, `8879dbba-68bb-48bb-bc7a-ece3421572c1` | Tokens/theme; Text/Icon/Button/IconButton/Field/Choice/Panel/Divider/Progress/Tooltip/Menu/Dialog; window-policy, focus, semantics, совместимые targets; затем составные API из карты. |
| 5 — оболочка, `21417b79-d79e-4b17-a51f-763b483863b9` | App, desktop entry, window adapters, sidebar сессий, настройки и вложенные редакторы. Зависит от 3. |
| 6 — чат/планировщик, `1c1bef7f-a975-4183-9220-cf925d24dec2` | Chat/Coding, дерево проектов, chat rows, composer, approvals/questionnaire, граф, scheduling, Markdown/reader, вложения, computer-use. Зависит от 3; интегрируется с 5. |
| 7 — остальные, `e3995487-9da9-419d-ac67-e5774675cc1e` | Welcome, Docs, Plugins и все common/JVM plugin panels; DI/SPI проверка. Зависит от 3 и Markdown API этапа 6. |
| 8 — проверки, `9a678be3-c605-4fcb-a73f-5a4a8cdefcc7` | Запрет обхода DS, build targets, UI/accessibility/manual platform evidence. |
| 9 — итог, `91525fc5-a931-4bb4-8f39-dd3ebf132974` | Только собственные изменения, сохранение baseline, итоговая проверка перед коммитом. |

Этап 4 отсутствует в переданном каталоге; не придумывать отдельного исполнителя или зависимость на него.

## Точки входа и полная навигация

Desktop `desktopApp/.../main.kt → application → Window → App → MagicPaperTheme → MainArea`. `DesktopWindowMode.kt` выбирает MAC_SYSTEM, WINDOWS_JBR_CUSTOM, Windows system fallback либо Linux custom; реализации и inset-provider включены в карту. Сейчас окно 1000×700 dp, `onCloseRequest = exitApplication`, системного `MenuBar` в entry нет. Это исходное состояние, не целевой контракт жизненного цикла.

`App` показывает Welcome при `showWelcome`; далее enum `Screen` маршрутизирует CHAT, CODING, PLUGINS, DOCS, SETTINGS. Отдельно: TopBar/WindowButtons/Notice, SessionsPanel, глобальный ModelSwitcherDialog, ActivePlugins. Android `MainActivity.onCreate` и web `main` также вызывают App и обязаны компилироваться после переноса общей темы.

| Поверхность | Вложенные области и обязательные API DS | Этап |
|---|---|---|
| Welcome | Intro/Model/Search/Plugins, PageDots, поля и выбор профиля → PaperPage, PaperNavigation, PaperField, PaperChoice | 7 |
| Settings | ProviderRow, SubscriptionAccount, NavEntry, Section, Field; SearchProviderPicker/API key/server/connection cards; EnginesSettings/EngineStatusCard/NewCodingSessionDialog; ModelsSettings/LibraryModelRow/VariantEditor/ModelDescriptionEditor/EditorDialog → PaperSettingsSection, PaperListRow, PaperField, PaperDialog | 5; NewCodingSessionDialog также потребляет 6 |
| Chat | MessagesList, EmptyHint, MessageBubble, ModelChip, Composer; model chooser, pins/browser, attachments, schedule → PaperChatTranscript, PaperComposer, PaperMarkdown, PaperChoice, PaperScheduleEditor | 6 |
| Coding | ResizableProjectPanels, ProjectsPanel, ProjectRow/Header/PinnedSession, SessionRow/Tab/Area, HoverActions/RowMenu/StatusTooltip; CodingChat/History/MessageBubble/StepRows/ToolContent/DraftFragment/AgentStatus/Composer → PaperSplitPane, PaperTreeRow, PaperTab, PaperMenu, PaperTooltip, PaperStatus, PaperChatTranscript, PaperComposer | 6 |
| Planning | CodingPlanningPlugin, граф и проекции, proposal, branch labels, questions/blocker docks, orchestration status и сообщения → PaperGraphCanvas/Node, PaperQuestionnaire, PaperApprovalDock, PaperStatus | 6 |
| Shared chat overlays | MessagePreview/reader, ChatMarkdown/document/blocks, StreamingText, RequestPinsBrowser, SessionContextMessage, ComputerUsePanel, CodingApprovalDock → PaperReader, PaperMarkdown, PaperCodeBlock, PaperDialog, PaperApprovalDock | 6 |
| Docs | DocsScreen, article/search → PaperPage, PaperField, PaperPanel, PaperText | 7 |
| Plugins | PluginsScreen/PluginRow/ActivePlugins и тела Content/SessionPanel → PaperPluginHost, PaperPanel, PaperListRow, PaperChoice | 7 |

Частные функции, которые не перечислены в этой обзорной таблице, **не исключены**: полный перечень с номерами строк в MIGRATION.md. Невизуальные helpers внутри UI тоже перечислены, но не подлежат механической замене на компоненты.

## Встроенные плагины и JVM-панели

| Реализация / ID | Фактическое подключение | Миграция |
|---|---|---|
| NotesPlugin / notes | common DI registry; Content | 7: PaperField, PaperButton, PaperPanel |
| FocusPlugin / focus | common registry; таймер/действия | 7: PaperText, PaperButton, PaperStatus |
| CalcPlugin / calc | common registry; ввод/результат | 7: PaperField, PaperText, PaperPanel |
| SkillsRepositoryPlugin / skill-shop | common registry; каталог/установка/активация | 7: PaperListRow, PaperField, PaperChoice, PaperStatus |
| SelfEducationPlugin / self-education | common реализация существует, но сейчас не регистрируется: default experiencePlugin=null; Android/web factory не передают | 7: PaperPanel, PaperField, PaperChoice; включить в запрет Material даже неактивный код |
| CodingPlanningPlugin / coding-planning (DecisionPlanningPlugin.kt) | common registry, CodingSessionPanel; вход через Coding/планирование | 6: PaperSplitPane, PaperGraphCanvas, PaperQuestionnaire |
| LocalSkillsPlugin / local-skill-packages | JVM platformPlugins | 7: PaperPanel, PaperListRow, PaperDialog, PaperField |
| LocalExperiencePlugin / self-education | JVM experiencePlugin factory; локальная реализация этого ID | 7: PaperPanel, PaperListRow, PaperChoice |
| UnavailableExperiencePlugin / self-education | JVM fallback при недоступной локальной реализации | 7: PaperStatus; fallback тоже должен мигрировать |
| ProjectSkillsPanel | JVM runtime constructor, **ProjectSkills.Content(projectId)**; вызов `vm.projectSkills?.Content(projectId)` в CodingScreen; не отдельный registry-ID | 7: PaperPanel, PaperListRow, PaperChoice, PaperButton, PaperStatus |
| SkillCatalogPanel, ProjectSkillRollback | JVM вложенные панели/rollback UI; не самостоятельные плагины | 7: PaperPanel, PaperDialog, PaperStatus |

`MagicPlugin.Content`, `CodingSessionPanel.SessionPanel` и `ProjectSkills.Content(projectId)` — три разные точки внедрения UI. Последняя объявлена в `domain/ProjectSkills.kt` с полным именем Compose-аннотации и реализована JVM ProjectSkillsPanel; CodingSessionPanel относится к планировщику. Все три контракта включены в SURFACE-MAP. `PluginRegistry` допускает замену по одинаковому ID; common и JVM self-education нельзя считать двумя одновременно активными панелями. Миграция должна сохранить активацию, установку, rollback, disabled/unavailable/error состояния и работу в выбранном проекте.

## Пергаментные токены

Ниже **точные исходные значения**, прочитанные из `MagicTheme.kt`. Сохранить как брендовый baseline; не объявлять автоматически пригодными для любого сочетания foreground/background.

| Семантика | ARGB |
|---|---|
| background / Parchment | FFF5EFE3 |
| surface / PaperSurface / agent bubble | FFFBF7EE |
| primary / lavender | FF7C6FA7 |
| onPrimary | FFFFFBF3 |
| secondary / sage | FF9AA98F |
| secondaryContainer | FFEAEFDE |
| tertiary / rose | FFC9A9A2 |
| tertiaryContainer | FFF3E3DF |
| onBackground/onSurface/onContainer / ink | FF3E3950 |
| onSurfaceVariant / muted ink | FF7A7386 |
| surfaceVariant | FFEFE7D6 |
| outline | FFD8CFBE |
| outlineVariant | FFE7DFCF |
| error (исходный декоративный) | FFA96A6A |
| primaryContainer / user bubble | FFE9E2F4 |

Текущие радиусы small/medium/large: 10/14/20 dp. Исходная типографика: Cormorant Garamond для display и крупных заголовков, Literata для текста/подписей, JetBrains Mono для кода. Шрифты лежат в common composeResources/font, лицензии — OFL_ALL.txt. Сохранить кириллицу и fallback для отсутствующих глифов.

| Роль | Размер/lineHeight sp | Вес |
|---|---|---|
| display L/M/S | 34/40, 30/36, 26/32 | Bold/Bold/SemiBold |
| headline L/M/S | 24/30, 22/28, 20/26 | SemiBold/SemiBold/Medium |
| title L/M/S | 22/28, 18/24, 16/22 | SemiBold/Medium/SemiBold |
| body L/M/S | 15/23, 14/21, 13/19 | Normal |
| label L/M/S | 14, 13, 12; lineHeight не задан | Medium |

**Новые решения DS, не нормативные размеры Apple/Microsoft:** spacing 4/8/12/16/24/32 dp; compact control macOS minHeight 28 dp, Windows 32 dp; row 28/32 dp, form field 30/32 dp; удобный профиль 40 dp и выше для увеличения текста/касания. Размеры — минимумы, содержимое может увеличивать высоту. Toolbar использует системную доступную область, а не навязывает ОС фиксированный размер. Компактные control corners 6 dp, field 6 dp, menu 8 dp; поверхности сохраняют 10/14/20. Иконки 16/20 dp; focus outline 2 dp с 2 dp inset/outset без сдвига layout.

Целевые роли: text.primary, text.secondaryAccessible, text.errorAccessible, accent.decorative, accent.action, onAction, border.decorative, border.control, focusRing, selection.active/inactive, surface.hover/pressed/disabled. Hover/pressed/focus/selected/disabled/loading/error определяются DS, не opacity-магией feature. Семантическая ошибка включает текст/иконку, состояние не передаётся только цветом.

Локальный расчёт sRGB contrast: ink/surface **10,31:1**, muted/surface **4,25:1**, onPrimary/primary **4,34:1**, error/surface **3,96:1**, outline/surface **1,45:1**. Поэтому исходный muted не использовать для мелкого важного текста; PaperButton не наследует исходное onPrimary/primary без коррекции. Цель проекта: 4,5:1 для обычного текста, 3:1 для крупного текста и значимых границ/focus. До проверки новых пар применять ink к обычным надписям на светлой поверхности; декоративные лаванда/шалфей/роза сохраняются. Пример errorAccessible #864747 даёт 6,53:1 на paper surface; окончательные accessible/action/focus токены этап 3 обязан измерить для всех состояний. Не фиксировать непроверенные альфа-смеси как доступные.

Общий книжный текст остаётся Literata, бренд Cormorant, код JetBrains Mono. Системные меню/диалоги/оконные элементы используют системную типографику. Текущий toolbar уже использует SansSerif 13/16; оформить как отдельную chrome-роль. Не заменять весь бренд на SF/Segoe и не распространять системные шрифты в ресурсах. Светлая пергаментная тема — обязательный baseline; отдельная тёмная тема не входит в этот этап. Контрастный профиль и reduced-motion не должны зависеть от будущей тёмной темы.

## Матрица поведения macOS / Windows

Это **целевой контракт MagicPaper**. Основания A*/M*/C* раскрыты в SOURCES.md; адаптация рекомендаций к Compose является инженерным решением.

| Область / владелец DS | macOS | Windows | Проверяемый сценарий |
|---|---|---|---|
| Плотность / PaperDensity | Компактный профиль 28 dp, чтение без тесных строк | Компактный профиль 32 dp | 100% и увеличенный текст: подписи и локализация не обрезаются; row hit-area не пересекается с соседней |
| Фокус / PaperFocus | Поддержать Full Keyboard Access и видимый focus; не перехватывать системные команды | Tab/Shift+Tab между контролами; стрелки внутри composite; focus виден | Пройти настройки/дерево/диалог без мыши, disabled пропускаются; после dismiss focus возвращается к opener; удалённый opener → ближайший живой элемент |
| Клавиатура / PaperCommands | Command для copy/paste/undo/find; Cmd+, настройки; Cmd+W закрывает окно, Cmd+Q завершает приложение | Ctrl для edit/find; Alt+F4 закрывает; Alt/F10 меню, Shift+F10/Menu контекстное | Проверить отдельную обработку key-down без дублирования key-up; текстовый редактор имеет приоритет над глобальными shortcut |
| Меню / PaperCommandMenu | Системная menu bar с App/Edit/View/Window/Help; команды окна/настроек, disabled и shortcut-глифы соответствуют состоянию | Меню окна/команд доступно через Alt/F10; контекстное через правую кнопку и Shift+F10 | Open → arrows → Enter, Esc закрывает только верхний popup; одна модель команд для menu/toolbar/shortcut; нет действий только по hover |
| Диалоги / PaperDialog | Parent-owned; sheet-подобная привязка к окну. Подтверждение справа, cancel слева в двухкнопочном варианте | Parent-owned modal; primary слева, safe close/cancel справа по M2 | Focus внутри modal, фон не активируется; Escape=cancel; Enter только безопасное default действие и никогда при IME composition; длинный контент прокручивается |
| Текст / PaperField, PaperComposer | Cmd+A/C/X/V/Z, Shift+Cmd+Z redo, Option+стрелки для слов; сохраняется штатное поведение редактора | Ctrl+A/C/X/V/Z, Ctrl+Y redo, Ctrl+стрелки, Home/End | Русская/латинская раскладка, IME, emoji/суррогаты, многострочная вставка, выделение мышью, undo/redo, context menu; ошибки inline, placeholder не заменяет label |
| Отправка сообщения / PaperComposer | Enter отправляет, Shift+Enter новая строка; Cmd+Enter дополнительная явная отправка | Enter отправляет, Shift+Enter новая строка; Ctrl+Enter дополнительная отправка | Это правило продукта, не HIG. Во время composing Enter подтверждает IME; пустой ввод не отправляется; repeat не создаёт дубликаты; в обычном multiline field Enter всегда новая строка |
| Выбор / PaperListRow, PaperTreeRow | Focus отдельно от selection; контекстное меню не совершает основное действие | То же; стрелки и Home/End внутри списка, expand/collapse дерева | Выбранная сессия не меняется от hover; действия архив/rename доступны при focus; после удаления выбрать предсказуемый соседний элемент |
| Текстовый контент / PaperMarkdown | Выделение, Cmd+C, ссылки доступны клавиатурой, code copy | Выделение, Ctrl+C, ссылки и code copy | Длинный streaming ответ не сбрасывает выделение/scroll anchor; списки/таблицы/code сохраняют текст при copy |
| Scroll / PaperScroll | Уважать системное направление и trackpad; не добавлять искусственную инерцию | Колесо/precision trackpad; видимый scrollbar по профилю | Пользователь прокрутил вверх → streaming не возвращает вниз; кнопка follow-end восстанавливает слежение; вложенные reader scroll не перехватывают всё окно |
| Масштаб / PaperScale | Logical dp/sp, Retina/non-Retina и перенос между мониторами | 100/125/150/200% DPI и переход между мониторами | Bounds/focus/menu anchors пересчитываются; текстовый масштаб отдельно от display scale; минимум 200% text без потери действий, появляется scroll/reflow |
| Оконные области / PaperWindowHost | Системные traffic lights/resize/fullscreen; безопасный leading inset, toolbar не перекрывает кнопки | Реальная рамка, caption buttons/Snap/system menu сохраняются; JBR custom при поддержке, иначе system fallback | Drag только свободной области; поле/кнопка/selection не двигают окно; double-click учитывает платформу; resize/restore/fullscreen/inactive без дубля caption кнопок |
| Жизненный цикл / PaperWindowHost | Close window и Quit — отдельные команды; Dock re-open возвращает окно | Close primary window завершает приложение после применимой проверки незавершённой работы | Не терять drafts и активные сессии; текущий exitApplication на macOS — gap. Конкретная background-run policy должна сохранять существующий runtime, без молчаливой отмены задач |
| Motion/доступность / PaperTheme | Reduced Motion выключает декоративную бумажную анимацию; VoiceOver semantics | Reduced Motion, контраст; Java Access Bridge + NVDA/JAWS проверка | Роль/имя/value/state читаются; фокус не исчезает в анимации; pending не только spinner; keyboard mode сохраняется при переключении окна |
| File dialogs / PaperFilePicker | Системный выбор файла/директории, owner window | Системный выбор файла/директории с fallback | Cancel не меняет выбор, повторное открытие работает; UI-thread не блокируется тяжёлым I/O |

App-local mnemonic/shortcut, не подтверждённый платформой, должен быть помечен как правило продукта и видим в меню/tooltip. Не обещать поддержку macOS accessibility preferences в Compose без проверки на целевом runtime. Sheet здесь означает требуемое поведение ownership/modality; настоящая AppKit sheet потребует отдельного JVM bridge и не считается доступной автоматически через Compose Dialog.

## Приёмка следующих этапов

Каждый перенесённый компонент проверяется в default/hover/pressed/focus/selected/disabled/error/loading, где применимо. Проверить длинную русскую строку, пустые данные, 200% текста, keyboard-only и чтение screen reader. Dialog/menu дополнительно проверять nested popup, outside click, Esc, возврат фокуса, смену окна; async completion не закрывает уже другой диалог.

Платформенный прогон: macOS на системном chrome, Windows с JBR и system fallback; 1000×700 и узкое окно 720×480 logical units (проектная проверка, не существующий min size), split-pane min widths/reflow; 1×/2× macOS, 100/125/150/200% Windows; normal/maximized/fullscreen/inactive. Windows Snap и non-client hit test проверять на Windows, не по скриншоту с Mac. Смена DPI/размера не сбрасывает выбранную сессию и draft.

Регрессии: ChatScroll/CodingChatScroll, Markdown parsing/preview/selection, CodingComposerRender, ProjectsPanelCollapse/Sticky, UserInteractionStatus, DesktopWindowMode и тесты LocalSkills/ProjectSkills/LocalExperience. Список точных имеющихся тестовых файлов получается `rg --files shared/src desktopApp/src | rg 'Test'`; запускать подходящие тесты при изменении соответствующего поведения. Этот исследовательский этап production-код не меняет и UI-тестов не заявляет.

Скилл этапа 2 должен ссылаться на контракт и требовать: сначала инвентаризация/чтение существующей DS; компоненты только через DS; платформенная матрица; сохранение бренда и пользовательских данных; meaningful checks; отдельная фиксация непроверенных платформ. Не включать перенос на WebView, обязательный MVI/Hilt/Navigation3 или новый redesign Android/web.
