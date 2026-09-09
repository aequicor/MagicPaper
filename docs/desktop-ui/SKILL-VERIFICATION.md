# Проверка скилла desktop-интерфейсов

Дата: 2026-09-09. Этап `92610538-166a-4b52-b613-551b223d6586`.

## Результат

Создан оригинальный локальный пакет `magicpaper.desktop-ui@1.0.0` в `skills/magicpaper-desktop-ui`. Он автоматически обнаружим для подходящих задач (`allow_implicit_invocation: true`), но описание ограничивает применение UI-задачами MagicPaper на macOS/Windows. Пакет заявляет только `READ_PROJECT`, `WRITE_PROJECT`, `RUN_PROCESS`; это декларации существующего runtime и не выдача системных прав.

Инструкция требует сохранить пергаментный бренд, продуктовые данные и state restoration; адаптировать поведение к macOS/Windows; сохранять Android/JS/Wasm; использовать только публичный Paper API; расширять API сначала внутри `:designSystem`; не обходить границу через Material alias/FQ/wildcard или локальные копии. WebView/React/Node/Rust, обязательные MVI/Hilt/Navigation 3 и новый redesign mobile/web явно исключены.

## `ac-skill-valid` — PASS

- `verify-skill.py`: frontmatter name/description/license/version/package-id, отсутствие TODO, внутренние ссылки, HTTPS-ссылки, точный список файлов, размеры и SHA-256, compatibility и обязательные правила — PASS.
- Manifest SHA-256: `b923d8b4a9b49a87d91feca28a00d1f728eced1361d1b8d71c1e34ecb6a4c31c`; payload: 6 файлов, 14 003 байта.
- JVM `SkillPackageValidator` через `SkillPackageImporter.prepareDirectory` принял точные package bytes для host `1.0.0/desktop`.
- Ссылки опираются на Apple HIG, Microsoft Windows guidance и JetBrains Compose Desktop docs. Лицензии `yetone/native-feel-skill` и `Meet-Miyani/compose-skill` повторно проверены по upstream LICENSE: MIT. Их текст/код не копировался; использованы только направления оценки. WebView-архитектура первого и Material/широкие архитектурные требования второго не перенесены.
- Системный `quick_validate.py` не стартовал из-за отсутствующего в его Python-окружении `yaml`. Те же допустимые frontmatter-поля проверены системным Ruby YAML parser, а содержательный/пакетный контракт — локальным детерминированным скриптом и JVM-валидатором проекта. Зависимость PyYAML в проект не добавлялась.

## `ac-skill-active` — PASS

Фактическая project-local установка выполнена отдельной командой `:shared:installDesktopUiSkillBinding` в постоянный репозиторий `.magicpaper/skill-packages`. Команда использует только публичную штатную цепочку:

1. `SkillPackageImporter.prepareDirectory` и `LocalSkillRepository.install` — версия установлена в `QUARANTINED`, без активации или project pin.
2. `LocalSkillRepository.review` с evidence, привязанным к точному checksum — состояние `VERIFIED`.
3. `LocalSkillRepository.bindProject("MagicPaper", ...)` с generation, точными pins, разрешениями и отдельным trusted-text согласием — пакет привязан к MagicPaper.
4. `projectCodingSelection("MagicPaper")` и `prepareCodingSkillInput` для запроса о settings dialog на macOS/Windows получили `magicpaper.desktop-ui@1.0.0`, полный текст правил public Paper API/бренда/платформ и новую engine-session.

После записи команда закрыла репозиторий, повторно открыла этот же постоянный каталог и прочитала точную установленную версию, binding, trusted consent и инструкцию. Результат записан в `active-binding.json`; generation = 3. `snapshot.json` не редактировался напрямую — его и immutable release создал `LocalSkillRepository`. Project-local runtime-каталог исключён из Git, но остаётся действующим в рабочей папке. Пользовательское `~/.MagicPaper` не изменялось.

`DesktopUiSkillIntegrationTest` независимо создаёт непустой чужой active set и доверенную привязку `OtherProject`. После import/review/bind их active pins, project pins и trusted-text consent побайтно/структурно совпадают. Binding tool дополнительно сравнивает все посторонние active/project/trust записи до и после фактической установки, поэтому повторный запуск не заменяет существующие решения.

Gradle считает `skills/magicpaper-desktop-ui` входом `jvmTest`, поэтому изменение package bytes инвалидирует интеграционный тест.

## Команды

```text
python3 docs/desktop-ui/verify-skill.py
PASS: magicpaper.desktop-ui@1.0.0; files=6; manifestSha256=b923d8b4a9b49a87d91feca28a00d1f728eced1361d1b8d71c1e34ecb6a4c31c

ruby -e '<frontmatter YAML assertions>'
frontmatter PASS: description,license,metadata,name

./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.DesktopUiSkillIntegrationTest'
Final run on HEAD 393fb23: BUILD SUCCESSFUL in 7s; 1 test, 0 failures/errors/skipped

./gradlew --no-daemon :shared:installDesktopUiSkillBinding
BUILD SUCCESSFUL in 4s; PASS: magicpaper.desktop-ui@1.0.0 bound to MagicPaper in .magicpaper/skill-packages; generation=3

python3 docs/desktop-ui/verify-map.py --self-test
PASS: 183 explicit bindings; all negative controls rejected

python3 docs/desktop-ui/audit.py
Initial PASS at HEAD 1c9e380: inventoryMatches=true; unmappedUI=[]; protected recovery unchanged; tracked drift=[]
Final expected FAIL after concurrent HEAD c2dbf0f: protected Coding.kt baseline changed by that committed foreign change

git diff --check
PASS
```

Первый Gradle-запуск в sandbox ожидаемо не смог создать `FileLockContentionHandler` из-за запрета socket. Штатный повтор с разрешением и обычным Gradle cache успешен; `--offline` и кеш внутри проекта не использовались.

Во время финальной проверки внешние исполнители продвинули HEAD с `1c9e380` через `c2dbf0f` (`Show orchestrator return notices...`) на `393fb23` (`Simplify agent status and cycle dots forward`). Поэтому неизменяемый baseline этапа 1 корректно отвергает новые хеши защищённых/отслеживаемых файлов. Baseline/protected hashes не переписывались. Сдвинутые позиционные ключи `CodingScreen.kt` обновлены по фактическому исходнику штатным `verify-map.py --write`; карта снова PASS (183/183), содержательные DS-назначения не менялись.

## Границы

Это проверка пакета, маршрутизации инструкции и project binding. Нативный Windows UI, VoiceOver/NVDA/JAB, IME, Snap, DPI и будущие компоненты дизайн-системы на этом этапе не запускались; они остаются критериями этапов реализации.
