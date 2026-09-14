# Приёмка Paper Editor — 2026-09-13

База Mission Visualization: `4f0854953222eaf789d64e86e646201212b142af`.
Расширение: `cddeaf9f78273a0aad072e67815c856f9c04013d`, опубликовано в
`origin/codex/paper-design-system-plugin`. Родительский gitlink фиксирует этот коммит.

## Автоматический запуск из чата MagicPaper

Маршрут: выбрать проект в «Проекты и код» → открыть обычный чат → написать
«Создай макет экрана входа». Открывается отдельное окно Paper Editor с папкой
`design/layouts/chat-<ID>`, агент сохраняет проверенный документ и возвращает PNG.
Последующие правки сохраняют привязку проекта. Coding-сессии Pi/Codex не охватываются.

Последний прогон: 701 тест, без failures/errors/skips: chat impl 43, app 8
(ChatLayoutWorkflowTest, ChatServiceLifecycleTest, RuntimeLifecycleTest), core model 68,
Mission shared 569, Paper plugin 11, Paper Editor 2. Проверены повторные попытки
исправления документа, неизвестные компоненты, отмена, конфликт ручной правки,
сохранение PNG и привязки диалога, отсутствие запуска при восстановлении, защита
пути проекта от выхода через symlink. Общие изменения компилируются для JS и Wasm.
Архитектура, Paper API и карта поверхностей прошли self-test.

`DesktopLayoutEditorIntegrationTest` прошёл на macOS с настоящим исполняемым файлом
редактора, вложенным в собранный `MagicPaper.app`: автоматическое открытие нужной
папки с пробелами в пути → реальный Compose-рендер → публикация → повторное открытие.
Модель в этом тесте локальная фикстура: живой провайдер и качество его макетов
отдельно не проверялись. Осмотрен [полученный PNG](../../feature/session/impl/build/reports/layout-chat/generated.png).
Права исполнения вложенного приложения проверены после упаковки. Windows и Linux
для этого сценария не запускались. Команда opt-in теста приведена в карте проверок.

## Проверки первоначальной интеграции каталога

| Проверка | Результат |
|---|---|
| Paper plugin JVM | 11 tests, PASS |
| :designSystem:jvmTest | 57 tests, PASS |
| Mission IR JVM | 285 tests, PASS |
| Mission frontend JVM | 454 tests, PASS |
| Mission backend-compose JVM | 25 tests, PASS |
| Mission shared JVM | 569 tests, PASS |
| :tools:paper-editor:createDistributable | PASS, macOS .app |
| verify-module-architecture / verify-design-system / verify-map --self-test | PASS |

Итого 1401 тест без failures/errors/skips. Проверены штатная цепочка вставка →
свойства → Undo/Redo → сериализация → повторное открытие, неизвестные ссылки,
типизация, заблокированные слои, ограничение кеша, Retry, отмена и освобождение
изображений при закрытии во время рендера. Pixel-проверка Canvas охватывает zoom/pan,
обрезку родителем и порядок перекрытия. Отрицательные проверки исключений отличают
`tools/mission-visualization` от `tools/paper-plugin` и `tools/mission-visualization-copy`.

## Визуальная и интерактивная проверка

Маршрут: Open project → копия `examples/paper-workspace.layout.md` в отдельной папке
→ Components → PaperButton → Component → Interactive preview.

Рендеры настоящего workbench: [1440 × 960](../paper-plugin/build/reports/paper-plugin/workbench-1440.png),
[720 × 960](../paper-plugin/build/reports/paper-plugin/workbench-720.png).
Проверенные области: каталог/поиск слева, выравнивание объектов и clipping на холсте,
размер живого preview и свойства справа; узкий режим сохраняет ширину холста и
переключает боковые панели вкладками. Текст каталога на отрендеренной поверхности
`#FBFDFF`: `#0E4F9D`, контраст 7.85:1. Контрасты Paper проверены его JVM-набором.

Осмотрены default-контролы и составные панели, disabled/selected/loading/error,
пустой текст и узкие 240 px при 200% текста; [матрица покрытия](../paper-plugin/COVERAGE.md).
Фиксированный размер Instance ограничивает видимую область длинного текста: измените
размер в Layout, если в макете требуется показать его целиком. Именованные Preview:
`PaperButtonPreview`, `PaperFieldErrorPreview`, `PaperComposerNarrowPreview`,
`PaperEmptyFieldPreview` из `PaperPreviews.kt`, группа `Paper plugin`.

На реальной macOS запущен упакованный редактор, открыт пример через нативный folder
picker, вставлен PaperButton, проверено сохранение Instance в `.layout.md` и нажатие
в живом preview. Счётчик нажатий менялся локально и не попадал в props документа.
Compose-тесты подтверждают pointer/Enter, изменение текста, disabled, открытие и
завершение меню/диалога. Нативную клавиатурную активацию через средство управления
окном подтвердить не удалось; native popup key dispatch и Windows остаются для
платформенной проверки. Windows policy в снимках — моделирование метрик на macOS.
FPS/scroll performance на реальной Windows не измерены; отсутствие повторного
рендера при неизменном ключе и геометрия масштаба проверены отдельно.

## Свежий checkout

Итоговая интеграция чата повторно проверена в чистом clone с применённым staged
diff, без переноса build outputs. `git submodule update --init --recursive` получил
`cddeaf9f78273a0aad072e67815c856f9c04013d` из GitHub. Обычная
`:desktopApp:compileKotlin` прошла без флага. С флагом `-PpaperEditor=true`
`:desktopApp:createDistributable` собрал пакет; opt-in
`DesktopLayoutEditorIntegrationTest` прошёл с вложенным редактором именно из этого
пакета, настоящим окном и рендером, временным проектом и тестовой моделью.

Ниже — дополнительная проверка отсутствующего субмодуля и ручного запуска,
выполненная на первоначальной ревизии интеграции каталога `c450224…`.

Проверка выполняется на свежем clone MagicPaper с применённым staged diff интеграции,
без копирования локальных build outputs. До инициализации субмодуля
`:desktopApp:compileKotlin --offline` прошёл. `-PpaperEditor=true help` корректно
завершился ошибкой с `git submodule update --init --recursive`.

`git submodule update --init --recursive` загрузил опубликованный `c450224…` из
GitHub. Компиляция обоих модулей инструмента прошла; 11 тестов плагина повторно
выполнены после `cleanTest` с `--no-build-cache`, PASS. Команда
`-PpaperEditor=true -PpaperEditorDataDir=/private/tmp/paper-fresh-run-data :tools:paper-editor:run`
дошла до работающего desktop-процесса без ошибки запуска; после smoke-проверки
процесс остановлен. Команды воспроизведения и пути генерируемых отчётов приведены
в [README](README.md).
