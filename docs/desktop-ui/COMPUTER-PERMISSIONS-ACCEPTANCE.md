# «Управление компьютером»: раздел настроек и онбординг

## Маршрут и композиция

**Настройки → Управление компьютером**, `/settings/computer`,
`magicpaper://settings/computer`. Из «Движков» оставлена ссылка на раздел.
Больше не нужно выбирать кодинг-сессию, чтобы найти настройки доступа.

```text
‹ Настройки
Управление компьютером
┌ Компьютер и приложения ───────────────────┐
│ Весь компьютер      [выкл][просмотр][ввод] │
│ Приложение в фоне   [выкл][просмотр][ввод] │
└───────────────────────────────────────────┘
Разрешения системы
┌ 1. Запись экрана ─────────────────────────┐
│ Приложение / помощник · состояние         │
│ [Открыть запись экрана]                   │
└───────────────────────────────────────────┘
┌ 2. Универсальный доступ ──────────────────┐
│ Приложение / помощник · состояние         │
│ [Открыть Универсальный доступ]            │
└───────────────────────────────────────────┘
[Перетащить: приложение] [Показать в Finder]
[Проверить]
```

Одна ведущая направляющая, отступ страницы 24 dp, панелей 16 dp,
межгрупповые интервалы 12/16 dp. Ширина чтения ограничена 760 dp.
Здесь выбрана одна колонка вместо искусственного деления 62/38: на 390 dp
и при крупном тексте шаги остаются последовательными, кнопки переносятся,
вся страница прокручивается. Все контролы — Paper, включая новый
`PaperFileTransfer`: нативный Copy-only file drag и обычная доступная активация
для показа файла. Перенос файла сам по себе **не** показывает «Разрешено».

До: Настройки → Движки → поиск блока → общая панель конфиденциальности → поиск
нужного разрешения и файла. Теперь: Настройки → Управление компьютером →
нужная системная панель → drop/системный «+» → переключатель macOS → Проверить.
Системное согласие не обходится. Настройки сохраняются отдельно от выдачи
сессионного доступа. Открытие/восстановление страницы не сохраняет настройки,
не запускает автоматизацию и не показывает системный consent prompt.

## Источники и границы выводов

- Apple: [Allow accessibility apps to access your Mac](https://support.apple.com/guide/mac-help/allow-accessibility-apps-to-access-your-mac-mh43185/mac).
- Apple: [Control access to screen and system audio recording](https://support.apple.com/guide/mac-help/control-access-to-screen-and-system-audio-recording-mchld6aa7d23/mac).
  Документация описывает Privacy & Security, переключатели и добавление через «+».
  Приём drop конкретной версией System Settings не следует считать доказанным
  этой документацией. Предусмотрены Finder и «+», без эмуляции системного списка.
- Microsoft: [Only elevate UIAccess applications installed in secure locations](https://learn.microsoft.com/en-us/windows/security/application-security/application-control/user-account-control/security-policy-settings/user-account-control-only-elevate-uiaccess-applications-that-are-installed-in-secure-locations).
  UIPI не мешает процессам одинакового integrity level, но ограничивает
  управление повышенными приложениями. `uiAccess` требует подписи и защищённого
  расположения. MagicPaper эти требования не обходит; Windows-экран не содержит
  фиктивных macOS-разрешений или кнопки несвязанной страницы privacy.
- Compose 1.11.1: проверены локальные `DragAndDropSource.kt`,
  `DragAndDropSource.skiko.kt`, `DragAndDrop.desktop.kt` из pinned sources JAR.
  Использован штатный `Modifier.dragAndDropSource`, AWT `javaFileListFlavor`,
  только `DragAndDropTransferAction.Copy`. Собственный глобальный input hook
  или clipboard bridge не добавлен.

## Preview и локальные проверки

Файл: `feature/settings/impl/src/commonMain/kotlin/io/aequicor/magicpaper/ui/screens/ComputerSettingsPreviews.kt`.
Группы **Computer settings**, **Computer permissions**. Именованные cases:
`ComputerSettingsPreview`, `ComputerPermissionMissingPreview`,
`ComputerPermissionGrantedPreview`, `ComputerPermissionLoadingPreview`,
`ComputerPermissionErrorPreview`, `ComputerPermissionWindowsPreview`.
Рендерятся реальные composables с изолированными значениями, не пользовательские данные.

Артефакты: `feature/settings/impl/build/reports/computer-permissions/`.

| Область | Файлы | Проверка |
| --- | --- | --- |
| Страница, верх / после прокрутки | `screen-{390,900}.png`, `screen-bottom-{390,900}.png` | ScrollBy приводит к доступной кнопке «Проверить»; render не вызывает save/open/reveal |
| Нет разрешения / разрешено | `missing-{1.0,2.0}.png`, `granted-{1.0,2.0}.png` | Реальный pointer click вызывает правильный permission enum и точный target; hit areas внутри ширины 390 |
| Загрузка / ошибка | `loading-{1.0,2.0}.png`, `error-{1.0,2.0}.png` | Disabled semantics, явная ошибка, повторная проверка; старый ответ не перезаписывает новый |
| Windows / всё OFF / unavailable | `{windows,off,unsupported}-{1.0,2.0}.png` | Нет кнопок открытия macOS; render не вызывает эффекты |

`ComputerSettingsRenderTest` — 2 теста, 14 onboarding-рендеров + верх/низ
страницы на двух ширинах. `ComputerPermissionControllerTest` — 2 теста ошибок,
отмены и запоздавшего ответа. `ComputerSettingsPersistenceTest` сохраняет
черновик общих настроек, проверяет ошибку записи и запрещает возврат старой
политики из черновика. `PaperFileTransferTest` — 2 теста file flavor и доступной
активации/disabled. `PaperContrastTest` — 2 теста токенов, не измерение пикселей
нового экрана. `DesktopComputerPermissionsTest` проверяет две process identity,
матрицу требований, точные команды, отказ чужому target и настоящий непросящий
main-process preflight на macOS. `NativeApplicationDesktopTest` отдельно проверяет
непросящий preflight помощника; это не проверка его AX-действий.

Пройденные команды из корня worktree:

```sh
./gradlew :designSystem:jvmTest --tests '*PaperFileTransferTest' --tests '*PaperContrastTest' \
  :feature:settings:impl:jvmTest \
  :feature:session:impl:jvmTest --tests '*DesktopComputerPermissionsTest' \
  --tests '*ApplicationUseTest' --tests '*NativeApplicationDesktopTest' --tests '*ComputerUsePanelTest' \
  :app:jvmTest --tests '*AppRouteCodecTest' --tests '*RootComponentTest' \
  --tests '*AutomationPolicyTest' --tests '*ShellSettingsComponentTest' \
  -Pmagicpaper.codingTools.offline=true --console=plain
./gradlew :webApp:compileKotlinJs :webApp:compileKotlinWasmJs :desktopApp:compileKotlin \
  -Pmagicpaper.codingTools.offline=true --console=plain
ANDROID_HOME=/Users/aequicor/Library/Android/sdk ./gradlew :app:compileAndroidMain \
  -Pmagicpaper.codingTools.offline=true --console=plain
python3 docs/desktop-ui/verify-map.py --self-test
python3 docs/desktop-ui/verify-design-system.py --self-test
python3 docs/verify-module-architecture.py --self-test
git diff --check
```

Android сначала остановился на отсутствии SDK location в worktree; повтор с
существующим SDK через environment прошёл, `local.properties` не менялся.
Компиляция Android/JS/Wasm/desktop не равна выполнению приложения или установщика.

## Непроверенная нативная/визуальная часть

Ранее пользователь поручил «влей без проверки» после сообщения об отсутствии
подготовленного native-стенда и визуальной приёмки. Эти ограничения не превращены
в PASS; локальные автоматические проверки новой реализации выполняются.

- **NOT_RUN — perceptual review.** Чтение `missing-1.0.png`, `missing-2.0.png`
  и `screen-900.png` вернуло «Current model does not support images».
  PNG созданы, но модель не видела их пиксели. Проверить ведущие направляющие,
  переносы русских подписей, контраст состояния и отсутствие обрезки на 390/900 dp,
  text scale 1/2. Семантика/геометрия не заменяет осмотр.
- **NOT_RUN — installed macOS.** В установленном `.app` открыть каждую кнопку,
  проверить конкретную панель, перетащить app и реальный helper, включить доступ,
  вернуться и нажать «Проверить». Повторить при отзыве доступа, после требуемого
  macOS restart и обновления помощника. Отдельно проверить IDE attribution.
  Успешный file-flavor тест не доказывает приём System Settings или TCC attribution.
  Прошлый AX-native fixture был заблокирован отсутствующим Accessibility.
- **NOT_RUN — Windows runtime.** Windows-хост отсутствует; проверить обычное
  приложение, повышенное/защищённое окно и отказ корпоративной политики.
- **NOT_RUN — native accessibility / performance.** VoiceOver, NVDA, OS file drag,
  focus traversal и frame timing при прокрутке требуют целевого UI-стенда.
  Прогресс и ошибки не блокируют UI thread; FPS/latency не измерены.

Ни настройки ОС, ни TCC-база, ни UAC, ни пользовательские проекты/история
для этих проверок не изменялись. После handoff приложение само проверяет
интеграцию; принятие handoff не подтверждает уже выполненное слияние.
