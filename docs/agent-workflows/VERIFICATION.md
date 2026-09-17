# Проверки по характеру изменения

Команды выполняются из корня. Для простой задачи достаточно теста владельца
из колонки «Ближайшая проверка» в CODEMAP.md; не читайте всю таблицу.
Полный прогон нужен при миграции, изменении общих контрактов или широкой
интеграции. Обычный локальный fix начинается с теста владельца. При доступном
кэше допустим `--offline`; отсутствие артефакта в кэше не является ошибкой
исходников.

| Изменение | Команда / доказательство |
| --- | --- |
| Только AGENTS/навыки/ссылки | Проверка frontmatter навыков, существования локальных ссылок и `git diff --check -- <изменённые файлы>`; сборка приложения не нужна |
| Поведение одного модуля | `./gradlew :feature:session:impl:jvmTest --tests '*ИмяТеста'` — подставить владельца и реальный класс |
| Границы или Gradle dependencies | `python3 docs/verify-module-architecture.py --self-test` |
| UI, визуальный или интерактивный компонент | `python3 docs/desktop-ui/verify-design-system.py --self-test`, тесты Paper/consumer; рендер при изменении компоновки |
| Приёмка Compose-компонента | Именованные `@Preview` и матрица состояний по [Compose preview workflow](../../skills/magicpaper-desktop-ui/references/compose-previews.md); осмотр реального рендера, interactive preview и semantics checks по применимости |
| Перенос/добавление UI-поверхности | `python3 docs/desktop-ui/verify-map.py --self-test`; обновить явные bindings, а не исключать новую поверхность |
| Runtime / DI / startup / reset | `./gradlew :app:jvmTest --tests '*RuntimeLifecycleTest' --tests '*AppRootHostTest'` |
| Journal / deep links / dialogs | `./gradlew :app:jvmTest --tests '*RootComponentTest' --tests '*RootDialogLifecycleTest'`; browser bridge и host tests при изменении адаптера |
| Черновики, миграции, секреты | `./gradlew :core:storage:impl:jvmTest :feature:settings:impl:jvmTest :core:platform:jvmTest`; проверить reopen, ошибку записи, отмену, delete/reset, stale update |
| Native wire resources | `./gradlew :feature:session:impl:nodeProtocolTest` |
| Computer/application-use | `:feature:session:impl:jvmTest --tests '*ApplicationUseTest' --tests '*NativeApplicationDesktopTest' --tests '*ComputerUse*Test'`; политики/очереди — `:app:jvmTest --tests '*AutomationPolicyTest' --tests '*SessionInputQueueTest' --tests '*ChatInputQueueTest'`. Реальное окно отдельно: [opt-in macOS-сценарий](../COMPUTER-USE.md#проверка); на интерактивном Windows desktop — `tools/check-computer-use-windows.ps1` ([границы проверки](../desktop-ui/COMPUTER-USE-FEEDBACK.md#проверка-windows)) |
| Онбординг разрешений компьютера | Settings `*ComputerSettings*Test`, `*ComputerPermissionControllerTest`; session `*DesktopComputerPermissionsTest`; DS `*PaperFileTransferTest`. Реальный drop/Finder/TCC и Windows проверяются отдельно: [сценарии](../desktop-ui/COMPUTER-PERMISSIONS-ACCEPTANCE.md) |
| Desktop host | `./gradlew :desktopApp:compileKotlin :desktopApp:test` |
| Android host | `./gradlew :androidApp:assembleDebug :app:testAndroidHostTest :androidApp:testDebugUnitTest` |
| JS / Wasm common API или зависимости | `./gradlew :webApp:compileKotlinJs :webApp:compileKotlinWasmJs :webApp:compileDevelopmentExecutableKotlinJs :webApp:compileDevelopmentExecutableKotlinWasmJs` |
| IndexedDB | `./gradlew :core:storage:impl:jsBrowserTest`; нужен поддерживаемый установленный браузер; при изменении Wasm interop также `:core:storage:impl:wasmJsBrowserTest` |
| Paper editor / каталог | `./gradlew -PpaperEditor=true :tools:paper-plugin:test :tools:paper-editor:compileKotlin`; PNG в `tools/paper-plugin/build/reports/paper-plugin/` |
| Макеты из чата | `:feature:session:impl:jvmTest`, `:app:jvmTest --tests '*ChatLayoutWorkflowTest' --tests '*ChatServiceLifecycleTest'`; модель в тестах заменена фикстурой |
| Чат → native Paper Editor | Сначала `./gradlew -PpaperEditor=true :desktopApp:createDistributable`, затем `./gradlew -PpaperEditor=true -Pmagicpaper.paperEditor.it=true :feature:session:impl:jvmTest --tests '*DesktopLayoutEditorIntegrationTest'`; отдельный временный проект, настоящий редактор, без LLM-запросов; PNG `feature/session/impl/build/reports/layout-chat/generated.png`. `-Pmagicpaper.paperEditor.testExecutable=…` проверяет конкретный вложенный executable |
| SPI редактора / запись экземпляров | `./gradlew -PpaperEditor=true :mission-visualization:shared:jvmTest :mission-visualization:engine:frontend:jvmTest :mission-visualization:engine:ir:jvmTest :mission-visualization:engine:backend-compose:jvmTest` |
| Полная интеграция | `./gradlew verifyMigration` — команды и состав задачи проверять в корневом `build.gradle.kts` |

## Значимые сценарии

Выберите сценарии, чьи инварианты меняются. Не повторяйте полный список для текста
кнопки или перемещения приватного helper без изменения поведения.

- Navigation: A→B→A, курсор Back/Forward, новая ветка, возврат из настроек,
  скрытие sidebar без потери экрана, закрытие диалога, restart.
- Lifecycle: один runtime и один запуск; уничтожение компонента не останавливает
  сессию; recreation не повторяет side effects; close после частичного startup.
- Persistence: новый экземпляр backend после записи; invalid input и attachment
  bytes; secret isolation; interrupted migration; ошибка записи; поздняя запись
  после удаления; более новый draft во время завершения старой отправки.
- Execution: точная identity/generation, неизвестный внешний результат, native
  cleanup и writer lease, отсутствие повторного запуска/подтверждения при restore.
- UI: реальные semantics и hit areas, keyboard/back, narrow width/large text,
  установленный Paper renderer provider в изолированном fixture.

Для coroutine race используйте управляемый dispatcher и барьеры вокруг нужной
операции. Не лечите гонку увеличением `sleep`; не вызывайте бесконечное
`advanceUntilIdle` при постоянно работающих таймерах/observers.

## Что считать доказательством

`NO-SOURCE`/skipped не означает проверенное поведение. Компиляция JS/Wasm не
заменяет linking, browser execution или transaction test. Собранный пакет не
доказывает регистрацию схемы после установки.

DMG/MSI/DEB проверяются на соответствующей ОС: холодная/повторная ссылка,
одновременный запуск, свёрнутое окно, upgrade и uninstall. Для отсутствующей ОС
укажите `NOT_RUN` и точную причину. Native engine интеграции остаются opt-in по
[ENGINES.md](../ENGINES.md): запускайте их, когда пользователь включил live-проверки
в рамках задачи. Повторное подтверждение уже разрешённых проверок не нужно.

JUnit результаты владельца: `<module>/build/test-results/jvmTest/TEST-*.xml`;
HTML: `<module>/build/reports/tests/jvmTest/index.html`. Не запускайте параллельно
одну test-task для разных фильтров: результат следующего прогона перезапишет XML.

При неожиданном падении сравните тот же тест и окружение на исходной ревизии.
[compare-jvm-baseline.py](../compare-jvm-baseline.py) сравнивает уже полученные
отчёты; отсутствие теста в baseline не является доказательством старой ошибки.
Сохраните новые/изменившиеся/исходные failures отдельно, не ослабляйте ожидания
неизвестного исхода, изоляции данных или владения ради зелёного отчёта.

Завершайте проверкой `git diff --check` для своей области и сообщайте, что именно
проверено, что не запускалось и какие ограничения остаются.
