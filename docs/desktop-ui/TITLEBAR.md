# Упрощённый title bar

В оконном режиме слева находятся контурная иконка списка сессий и `MagicPaper`.
Справа сохранены расходы, настройки и необходимые платформенные кнопки окна.
Иконка приложения, Back/Forward, разделитель и название текущего раздела убраны.
Иконка списка переключает его за один клик, доступна с клавиатуры и имеет
динамическую подпись «Скрыть/Показать список сессий».

`PaperAppTitleBar` владеет геометрией, иконкой, нативной drag area и Paper actions.
`:app` передаёт состояние списка и действия. Высота `LocalWindowToolbarHeight`
используется одновременно title bar, sidebar и содержимым чатов. В fullscreen
host передаёт `0.dp`: строка, её focus targets, frost/divider и отступ исчезают.
Настройки остаются доступны через системное меню. Выбор сессии, активный компонент,
видимость списка и журнал навигации не меняются. При выходе строка возвращается.

Полноэкранность берётся из `WindowState.placement == WindowPlacement.Fullscreen`,
который Compose синхронизирует с нативным окном. Сравнение размеров с экраном
удалено: обычное разворачивание окна не скрывает панель, а macOS safe-area/notch
не мешает распознаванию fullscreen. Системные menu bar и traffic lights управляются
ОС и могут появляться при наведении на верхний край. Высота оконного toolbar
учитывает увеличение текста (28 dp macOS / 40 dp остальные desktop, минимум
высоты chrome-текста + 8 dp).

## Приёмка

Маршрут: открыть приложение → любой чат/проект/справочник. Вверху проверить
иконку списка и название, отсутствие прежних навигационных кнопок и названия
раздела. Переключить список, войти в fullscreen системной кнопкой, выйти:
контент занимает освободившуюся высоту; выбор и состояние списка сохраняются.
Перетаскивание/системные controls принадлежат прежним платформенным адаптерам.

Preview group **App title bar**: `PaperAppTitleBarPreview` (640, 320, 480 dp / 2×),
`PaperAppTitleBarFullscreenPreview`; источник —
`designSystem/src/commonMain/kotlin/io/aequicor/magicpaper/designsystem/PaperAppTitleBar.kt`.

Реальные Compose/AWT рендеры:

- [Оконный режим, весь shell](../../app/build/reports/app-shell/sidebar-visible.png)
- [Fullscreen, весь shell](../../app/build/reports/app-shell/fullscreen.png)
- [Узкий toolbar](../../designSystem/build/reports/titlebar/windowed-320-1.0.png)
- [Текст 2×](../../designSystem/build/reports/titlebar/windowed-480-2.0.png)

`PaperAppTitleBarTest`: состав и расположение элементов, действия, отсутствие
интерактивных элементов fullscreen, восстановление collapsed sidebar.
`AppShellRenderTest`: настоящий shell с memory repositories, сохранение активного
компонента/журнала/состояния панели; исчезновение настроек/расходов, смещение
содержимого и обратное восстановление. `DesktopWindowModeTest`: fullscreen
отличается от maximize; масштабирование текста.

Отдельный native macOS тест (не открывает пользовательское хранилище):
`./gradlew -Pmagicpaper.window.native=true :desktopApp:test --tests '*DesktopFullscreenTest'`.
Он меняет состояние нативного окна, ждёт обратного обновления Compose и измеряет
высоту toolbar; перед обратным переходом ждёт системного окончания анимации.
[Результат native-переходов](../../desktopApp/build/reports/titlebar/native-macos.txt).
Нативные Windows/Linux, VoiceOver и измерение FPS отдельно не проверялись.
JS/Wasm/Android проверяются компиляцией, без изменения браузерного/мобильного chrome.
