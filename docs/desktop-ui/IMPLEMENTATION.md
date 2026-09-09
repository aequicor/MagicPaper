# Реализация desktop UI · 2026-09-09

Доработан существующий `:designSystem`. Актуальная палитра и правила взаимодействия — [BRANDBOOK.md](BRANDBOOK.md). Скилл `magicpaper.desktop-ui@1.1.0` установлен через `LocalSkillRepository`, проверен и привязан к проекту; доказательство — [active-binding.json](active-binding.json). Корневой `AGENTS.md` направляет последующие UI-задачи к этому скиллу.

## Изменения

- Paper-контролы используют общую форму для клика, полного hover/pressed-заполнения и клавиатурной обводки. Нажатие мышью не рисует клавиатурную обводку поверх заливки. `PaperButton.state` действительно управляет состоянием; busy/disabled блокируют активацию.
- Компактные поля с постоянной подписью, переключатели, флажки, меню, иконки и затухание длинной подписи находятся в DS. Внутренние отступы сообщений исправлены: закрепление не перекрывает текст.
- В производственных source sets удалены прямые Material/Markdown-renderer импорты и зависимости. Парсер Markdown остаётся отдельной невизуальной зависимостью. Компиляции и Gradle `check` запускают проверку границы DS, включая отрицательные примеры.
- Восстановлена потерянная при предыдущей миграции обработка ограниченных Markdown-фрагментов: стили, списки, код и ссылки сохраняются; в длинных сообщениях композируются видимые фрагменты. Кеш и управление прокруткой остаются у чата, renderer — внутри DS.
- Системное меню desktop-хоста вызывает настройки и закрытие окна с платформенными сочетаниями клавиш. Оконный фон использует цвет DS.
- Убраны описания внутренней реализации из настроек и каталога; сохранены сведения для выбора источника, проверки прав и подтверждения последствий. Карта обновлена по фактическим декларациям: 187 связей.

Два рендер-теста переведены со старых фиксированных пикселей на реальные границы подписей контролов. Тест предложения доработки синхронизирован с существующим правилом доступности подтверждения активного предложения; доменная логика не изменялась.

## Воспроизведение

```sh
python3 docs/desktop-ui/verify-design-system.py --self-test
python3 docs/desktop-ui/verify-skill.py
python3 docs/desktop-ui/verify-map.py --self-test
./gradlew :designSystem:jvmTest :desktopApp:test :desktopApp:compileKotlin \
  :shared:compileKotlinJs :shared:compileKotlinWasmJs :shared:compileAndroidMain \
  :shared:jvmTest --tests '*DesktopUiSkillIntegrationTest' \
  --tests '*ui.components.*' --tests '*ui.screens.*' \
  --tests '*LocalSkillsPanelTest' --tests '*LocalExperiencePanelTest' \
  --tests '*ProjectSkillRollbackUiTest' --tests '*SkillCatalog*Test' --console=plain
```

Gradle использует установленный toolchain и стандартный кеш пользователя. Каталоги рендеров: `designSystem/build/reports/paper-controls/` (macOS и Windows density profiles), `shared/build/reports/` (экраны, сообщения, граф и анкеты).

## Результаты итогового прогона

Сборка Gradle: **PASS**. Компиляции JVM desktop, Android, JS и Wasm: **PASS**. Проверки границы DS, манифеста скилла, карты с отрицательными контролями и `git diff --check`: **PASS**.

| Набор | Всего | Пройдено | Пропущено | Ошибок |
|---|---:|---:|---:|---:|
| designSystem:jvmTest | 14 | 14 | 0 | 0 |
| desktopApp:test | 4 | 4 | 0 | 0 |
| shared:jvmTest | 140 | 138 | 2 | 0 |

## Границы проверки

Рендеры обоих профилей получены через `ImageComposeScene` на macOS. Они проверяют геометрию, заливку, текст, контраст, семантику и синтетический ввод; это не ручной прогон Windows. Ручные проверки macOS/Windows, VoiceOver/NVDA, Snap, полноэкранного режима и масштабирования дисплея — **NOT_RUN**. Для них нужны соответствующие интерактивные окружения. Полный набор доменных и сетевых интеграционных тестов не заявляется пройденным.

## Редизайн «Проекты и код», 2026-09-09

Применён установленный `magicpaper-desktop-ui`. Отдельный инструмент или локальный скилл «магический дизайнер» в сессии недоступен. Состояние проекта прочитано через `magicpaper_context_get`; подключение отсутствующего инструмента не заявляется.

Изменены навигация проектов/сессий, заголовок журнала, сообщения, раскрываемые действия и размышления, статусы, закреплённые запросы, панель доступа к экрану, выбор модели и компоновка ввода. Общая геометрия и новые переиспользуемые компоненты находятся в `designSystem/PaperWorkspace.kt`. Фон окна и разделители в рамках этого редизайна не редактировались. Обычный текст обеих ролей сохраняет Literata 14/21; служебные подписи используют chrome. Полные команды и вывод остаются доступны через раскрытие.

Проверки редизайна: 16 тестов DS и 42 выбранных UI-теста shared — PASS. Проверены длинные и потоковые сообщения, сохранение позиции, раскрытие 1000 фрагментов размышлений, закрепления, сворачивание и закрепление навигации, hover. После финального выравнивания панели действий повторно пройден `CodingWorkspaceRenderTest` на ширинах 1240, 720 и 600 px. JVM desktop, Android, JS и Wasm компилируются. `verify-design-system.py --self-test`, `verify-map.py --self-test` и `git diff --check` — PASS.

Рендеры: `shared/build/reports/coding-workspace/workspace-{1240,720,600}.png`. Это тестовые сцены с неподвижным песочным фоном для проверки компоновки; работающий экземпляр приложения не перезапускался. Ручная проверка нативного окна Windows/macOS — NOT_RUN.

## Отступы и индикаторы работы

Уточнённая схема пользователя реализована через `PaperActivityIndicator`: готовность — зелёная; работа и ожидание бэкенда/модели — красные; опросник, подтверждение или ошибка — жёлтые; очередь оркестратора и ожидание события — серые. Размер не меньше 10 dp, пастельная заливка с контрастным краем. Активность пульсирует без потери цвета. Исправлено определение статуса ожидания планировщика и ошибок, ранее возвращавшее готовность.

Системные карточки получили внешние отступы 12 dp и внутренние 16/12 dp, одинаковую с ответами направляющую. Статус отделён собственным вертикальным отступом 8 dp.

DS: 17 тестов прошли, включая контраст краёв индикатора ≥3:1 на заливке и фоне. Из 129 выбранных shared-тестов 128 прошли сразу; единственное устаревшее ожидание зелёного исполнителя после передачи оркестратору обновлено на QUEUED согласно запросу пользователя и успешно перепроверено вместе с новым `CodingSessionStatusTest`. JVM desktop, Android, JS и Wasm компилируются. Проверки DS, карты поверхностей и `git diff --check` пройдены. Работавшее приложение не перезапускалось; ручной прогон нативных окон не выполнялся.

## Docked composer and message surfaces

The latest user revision replaces the floating white composer with a bottom-docked muted sand surface. The transcript is measured in a separate viewport above it, with no overlay or footer-height compensation. Agent messages align left, user messages right, with 8 dp outer insets and pastel backgrounds. Pinned text uses PaperFadingText without ellipsis; the send label is centered on both axes.

Validation: desktop JVM, Android, JS and Wasm compilation passed; DS tests (including contrast on both new surfaces) passed. Long-message expansion, streaming scroll and request-pin tests passed. The workspace geometry test was corrected to select the conversation viewport rather than the sidebar, and passed at 1240, 720 and 600 px with assertions for button centering and non-overlap. The context-indicator test now explicitly delivers snapshot notifications before inspecting updated semantics, removing a timing race. Renders are in shared/build/reports/coding-workspace/. The running application was not restarted.

## Full-log collapse and refined composer

Full-log collapse no longer overwrites the pending disclosure scroll request with offset zero. The visible header retains its reading position; collapsing from an offscreen footer returns to the opening row. A regression test expands a 20,000-line log and verifies the header position within 1 px after collapse. Existing ordinary-disclosure, long-message, streaming and request-pin tests passed alongside it.

The bottom-docked composer now has a 16 dp rounded surface, warm tonal gradient and soft shadow. Focus is shared with the whole composer; the inner rectangular focus outline is suppressed. Messages use distinct opaque lavender tones, with soft shadows on standalone bubbles and no hard outline. Contrast tests cover the new surfaces. Normal and focused renders at 1240/720/600 px are in shared/build/reports/coding-workspace/.

Verification: designSystem:jvmTest passed; the selected shared UI suite passed; desktop JVM, Android, JS and Wasm compilation passed. DS boundary and surface-map checks passed. No application restart or native Windows manual verification was performed.
