# Компьютер и приложение: проверка и оставшиеся ограничения

## Передача результата с принятыми ограничениями

После сообщения об ограничениях пользователь явно поручил: «влей без проверки».
Результат реализации передаётся без недоступной нативной и визуальной приёмки:
реальные macOS AX/захват окна, Windows UIA и визуальный осмотр PNG остаются
неподтверждёнными. Это принятое ограничение, а не успешный результат этих проверок.
Сохранённый FAILED opt-in preflight ниже отражает отсутствие системного
Accessibility-доступа до начала GUI-сценария; его результат не исправлялся и
проверки безопасности не ослаблялись. Пройденные локальные проверки перечислены
ниже. Обязательная проверка объединения остаётся за приложением Worktree.

## Изменённые места

Маршрут: **Настройки → Движки → Компьютер и приложения**.
Одна настройка на режим вместо ручного включения перед каждым запросом.
Сохранение автоматически; изменение одного режима не меняет второй.

```text
┌ Компьютер и приложения ───────────────────────┐
│ Предупреждение о передаче содержимого модели   │
│ Весь компьютер                               │
│ [Выключено] [Просмотр] [Управление]            │
│ Предупреждение об общем вводе                 │
│ ──────────────────────────────────────────── │
│ Приложение в фоне                            │
│ [Выключено] [Просмотр] [Управление]            │
│ Ограничения и применение к следующему запросу │
└──────────────────────────────────────────────┘
```

Общий leading edge заголовков и групп, перенос choices на узком окне.
В кодинг-сессии: статус слева, «Отключить» справа, ошибка и восстановление ниже.
Application-only не предлагает снимок общего desktop. В обычном чате отмена
запроса отзывает доступ; глобальная политика меняется в тех же настройках.

## Preview / render matrix

| Область / preview symbol | Состояния | Исполняемая проверка / артефакты |
| --- | --- | --- |
| `AutomationSettingsPreview`, группа `Automation access` | OFF, 390/900 dp, font scale 1/1.5 | `AutomationSettingsRenderTest`; [narrow](../../feature/settings/impl/build/reports/automation-settings/narrow.png), [wide](../../feature/settings/impl/build/reports/automation-settings/wide.png), [large text](../../feature/settings/impl/build/reports/automation-settings/large-text.png) |
| `AutomationSelectedPreview` | computer SCREEN / application CONTROL | pointer activation и независимые selected semantics в том же тесте |
| `AutomationUnavailablePreview`, `AutomationSavingPreview` | disabled, saving, нет выдачи доступа при render | [unavailable](../../feature/settings/impl/build/reports/automation-settings/unavailable.png), [saving](../../feature/settings/impl/build/reports/automation-settings/saving.png) |
| `BackgroundApplicationPreview`, группа `Automation session` | application-only, busy, font scale 1/1.5 | `ComputerUsePanelTest`; [active](../../feature/session/impl/build/reports/computer-use/application-false-1.0.png) |
| `ApplicationPermissionPreview`, `AutomationOffPreview` | permission failure/recovery, OFF | [error large text](../../feature/session/impl/build/reports/computer-use/application-true-1.5.png); нет desktop preview, есть stop и системные настройки |

Previews находятся в `feature/settings/impl/.../screens/AutomationSettingsPreviews.kt`
и `feature/session/impl/.../components/ComputerUsePanelPreviews.kt`. Артефакты
генерируются тестами, не являются файлами исходников.

Проверено на macOS arm64: headless Compose render и semantics/pointer assertions.
Проверены labels, selected/disabled states, независимость режимов, отсутствие
выдачи доступа при построении UI и доступная во время busy остановка.
**Не выполнена перцептивная визуальная приёмка:** просмотрщик этой сессии вернул
`Current model does not support images`. Наличие PNG не означает визуальный PASS.
Нативные focus/keyboard, screen reader, контраст по пикселям и FPS также не измерены.
Для ручной приёмки открыть указанный маршрут при 390/900 dp и 100/150% текста:
проверить перенос без clipping, общий leading edge, hit areas, контраст и focus order.

## Исполняемые доказательства

- `:core:model:jvmTest`: сериализация/модели.
- `:feature:settings:impl:jvmTest`: полный owner suite, включая render и persistence.
- `:app:jvmTest`: `AutomationPolicyTest`, `ChatInputQueueTest`, `SessionInputQueueTest`,
  `CodingResumeTest`, `RuntimeLifecycleTest`, `ChatServiceLifecycleTest`.
- `:feature:session:impl:jvmTest`: `ApplicationUseTest`, `NativeApplicationDesktopTest`,
  `DesktopComputerUseTest`, `ComputerUseBridgeTest`, `CodingClientComputerUseTest`,
  `ComputerUsePanelTest`, `CodingRuntimeOwnershipTest`, `ComputerUsePiIntegrationTest`.
- Реальный установленный Pi → generated extension → MCP → fake native window →
  локальная fake vision model: оба PNG round trips прошли. Внешние аккаунты/LLM
  не использовались. Тестовая среда получает фиктивный API key через штатную env
  reference, не через models.json. Node-extension checks также включены.
- `:desktopApp:compileKotlin`: desktop host компилируется; это не проверка установочного пакета.
- Swift typecheck и сборка bundled helper; subprocess framing/stale-window smoke
  без чтения рабочего стола и без запроса TCC. Повторный Gradle использовал
  configuration cache успешно.
- Architecture, design-system и explicit surface-map verifiers с negative controls.
  В карте также исправлен существовавший drift annotation lines; исходники этих
  посторонних UI-поверхностей не изменялись.

Команды владельцев — [COMPUTER-USE.md](../COMPUTER-USE.md). Для Pi smoke задать
`MAGICPAPER_COMPUTER_NODE` и `MAGICPAPER_COMPUTER_PI` путями установленных Node/CLI;
без них этот тест помечен skipped. Не путать fake-backed round trip с native AX/UIA.
XML/HTML лежат в `<module>/build/test-results/jvmTest` и `build/reports/tests/jvmTest`.
Локальный Gradle-прогон по перечисленным JVM-фильтрам завершился `BUILD SUCCESSFUL`.
Это не результат отдельного opt-in нативного сценария ниже.
Проверены актуальные XML: `ApplicationUseTest` — 6, Pi — 2,
`SessionInputQueueTest` — 4, `ChatInputQueueTest` — 3;
у всех ноль failures, errors и skipped. Новым тестом покрыт также отзыв
старого ручного разрешения при применении OFF/OFF.

## Нативная приёмка: недоступна, ограничение принято

Добавлен `ApplicationUseMacIntegrationTest` и AppKit fixture
`feature/session/impl/src/jvmTest/resources/computer/application-fixture.swift`.
Сценарий проверяет реальный AX-ввод Unicode и кнопку, отзыв устаревших ссылок,
сокрытие password value, закрытое окно, PNG красного окна под синим перекрытием,
неизменность foreground, курсора и счётчика изменений clipboard. Это программа
теста, а не перечень уже пройденных нативных проверок.

Запуск с `-Pmagicpaper.application.native=true` **не прошёл preflight**:
`accessibility=false`, `screen_capture=true` (XML от 2026-09-16T04:55:34Z).
До запуска fixture, перечисления окон, захвата и ввода выполнение не дошло.
Разрешения TCC не запрашивались и не изменялись. [Сохранённый XML](../../feature/session/impl/build/reports/application-native/preflight.xml)
отделён от обычных JVM-отчётов, чтобы следующий фильтр его не перезаписал.
Без opt-in тест явно skipped; это не подтверждает выполнение нативного сценария.

Helper теста хранится по стабильному пути
`feature/session/impl/build/application-use/native-acceptance/<sha256>/application-use`.
Для продолжения нужен подготовленный интерактивный Mac с системными разрешениями
для helper/responsible process. Пользователь подтвердил отсутствие подготовленного
стенда; визуальный отзыв по PNG не получен. Автоматические тесты не заменяют этот отзыв.
Swift fixture прошёл typecheck; его GUI-сценарий ещё не исполнялся.
После добавления preflight повторены только затронутые проверки:
`ApplicationUseTest` — 6 PASS, `NativeApplicationDesktopTest` — 4 PASS,
`ApplicationUseMacIntegrationTest` без opt-in — 1 SKIPPED, desktop compile — PASS.
Swift helper/fixture typecheck, architecture verifier и `git diff --check` — PASS.
Этот успешный обычный прогон не отменяет FAILED opt-in preflight выше.

## Остальная native / distribution приёмка: NOT_RUN

- Настоящие macOS AX-действия, window capture, отказ/выдача TCC и сохранение
  foreground/input/clipboard при работе в другом приложении не проверены.
- Windows UI Automation, PowerShell helper, `PrintWindow`, UAC и enterprise policies
  не исполнялись: Windows host отсутствует. macOS smoke их не проверяет.
- Codex live integration, подписанные установочные пакеты, upgrade/uninstall,
  Android/browser execution не запускались.

Сценарий с временным native fixture описан в [COMPUTER-USE.md](../COMPUTER-USE.md).
Эти ограничения остаются отдельной платформенной приёмкой, не скрыты успешными JVM-тестами.
