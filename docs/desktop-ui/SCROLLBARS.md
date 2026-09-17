# Полосы прокрутки Paper

Все прокручиваемые области desktop-приложения используют одну политику: полоса
появляется при движении содержимого или наведении на край области, остаётся видимой
во время перетаскивания и скрывается через 650 мс покоя после ухода указателя.
При отсутствии переполнения нет ни бегунка, ни перекрывающей содержимое зоны.

Область наведения — 12 dp справа для вертикальной прокрутки и снизу для
горизонтальной. Бегунок — 6 dp, минимальная длина 24 dp, радиус 3 dp. Цвета
`secondaryText` и `action` сохраняют контраст с Paper surface. Полоса рисуется
поверх края, поэтому её появление не меняет ширину текста и не вызывает relayout.

## Владение и применение

- `PaperLazyColumn` сохраняет переданные `LazyListState`, ключи, padding,
  reverseLayout и ленивую отрисовку. Используется в лентах, вопросах, источниках,
  деревьях сессий, документации и каталогах.
- `PaperScrollColumn`, `PaperScrollRow` и `PaperScrollArea` добавляют полосы
  формам, вложенным спискам и диалогам. `PaperScrollViewport` оборачивает области
  с собственным `ScrollState`, например код и таблицы Markdown. Он сам не
  добавляет scroll modifier: содержимое использует тот же state.
- `PaperMenuHost` сохраняет native DropdownMenu и ограничивает внутренний
  scroll viewport доступной половиной окна, оставляя запас 64 dp для anchor
  и отступов. Последний пункт длинного меню доступен перетаскиванием бегунка.
- `PaperInput` и оба `PaperPromptField` на desktop используют существующий
  Foundation `TextFieldScrollState`. Полоса является соседом редактора,
  чтобы перетаскивание не перемещало курсор и не меняло выделение. Область
  полосы совпадает с внутренним текстом, исключая label и trailing action.

Реализация JVM/web находится в `skikoMain`: размеры бегунка, drag и page clicks
делегируются Foundation ScrollbarAdapter. Полоса не участвует в intrinsic
измерениях меню. Колёсико над краем передаётся тому же ScrollableState;
геометрия полосы редактора хранится относительно поля, без обновлений при
перемещении окна или прокрутке родителя. Offset наблюдается внутри компонента полосы; состояние её
видимости не передаётся в Markdown или содержимое списка. У каждого блока кода
сохраняются собственные highlighter и состояние результата.

Android сохраняет touch-прокрутку без desktop overlay. В web общие списки,
формы, меню и Markdown используют overlay; поля ввода пока сохраняют прежний
BasicTextField без полосы, поскольку его web overload не предоставляет
TextFieldScrollState. Native Windows и browser interaction отдельно не проверены.

## Проверка и рендеры

Named preview: [`PaperScrollPreview`, группа `Scrolling`](../../designSystem/src/commonMain/kotlin/io/aequicor/magicpaper/designsystem/PaperScrollPreviews.kt).
Матрица: обычный список, lazy list, горизонтальная строка; 520×280 при 1× и
520×360 при 2× текста. Дополнительные сцены теста: idle, hover, scrolling,
stopped, drag, отсутствие переполнения, длинное меню и длинный draft.

`PaperScrollbarTest` проверяет появление/скрытие по пикселям, вертикальное и
горизонтальное перетаскивание, колёсико над полосой, отсутствие recomposition содержимого, ограниченное
число composed rows в списке из 10 000 элементов, последний пункт меню и
неизменность draft/selection при прокрутке редактора.

Проверенные рендеры:
[hover](../../designSystem/build/reports/scrollbars/hover.png),
[после остановки](../../designSystem/build/reports/scrollbars/stopped.png),
[2× текст](../../designSystem/build/reports/scrollbars/gallery-2.0.png),
[меню](../../designSystem/build/reports/scrollbars/menu.png),
[редактор](../../designSystem/build/reports/scrollbars/editor.png),
[исследование](../../feature/session/impl/build/reports/research-workspace/reading.png).
Это Compose render evidence на macOS, а не измерение FPS установленного приложения.

Ручной маршрут: исследование с длинным ответом → прокрутить ленту → убрать
указатель от правого края → дождаться скрытия → навести на правые 12 dp →
перетащить бегунок. Повторить в источниках, вопросах, настройках и длинном меню.
В composer вставить несколько десятков строк и прокрутить за бегунок: текст,
курсор и выделение сохраняются. У широкого кода/таблицы проверить нижний край.

Команды: `:designSystem:jvmTest --tests '*PaperScrollbarTest' --tests '*PaperMarkdown*Test'`;
session `ResearchWorkspaceRenderTest`, `ResearchScrollRegressionTest`,
`CodingComposerRenderTest`, `CodingApprovalRenderTest`, `PlanningProposalRenderTest`,
`MessageHistoryActionsRenderTest`; app `AppShellRenderTest`, `VisitPresentation*Test`.
