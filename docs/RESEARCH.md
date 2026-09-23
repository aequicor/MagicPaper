# Исследование проекта

В меню возле поля ввода «Проекты и код» есть три равноправных варианта: обычный режим,
исследование и планирование. Исследование — диалог о текущем проекте: чтение, поиск,
Git, объяснения и защищённые проверки. Текстовый план создаётся только по запросу.
Граф задач, исполнители и автоматическая реализация не запускаются.

Обычный режим и исследование переключаются между запросами. Планирование закрепляется
за сессией. Активный запрос и открытый опросник блокируют переключение; после явной
остановки смена режима закрывает старый checkpoint. История, модель, черновик,
вложения и журнал остаются. Управление компьютером в исследовании недоступно.

## Состояние и контекст

`CodingInteractionMode` объединяет совместимые `planningMode` и `researchMode` (по
умолчанию `false` для старых записей). Два флага одновременно недопустимы. Переходы
проверяются по актуальной записи под mutex хранилища; обычное сохранение устаревшей
записи не меняет режим. Исполнители и архивные сессии не переключаются.

Каждый `CodingRunCheckpoint` закрепляет режим. Восстановление, повтор и фоновые
обращения проходят одну проверку ограничений. Несовпадение checkpoint и сессии
завершает запрос ошибкой до вызова движка. Смена режима сбрасывает нативный контекст;
следующий запрос получает последние сообщения в пределах настройки контекста
(до 200 сообщений, 12 000 символов на сообщение и 60 000 всего). Системные квитанции
не становятся пользовательскими инструкциями. Начальный отчёт о контексте использует
тот же сборщик исследовательских инструкций, что и движки.

Codex получает `readOnly` и `approvalPolicy=never`; набор MCP содержит только сервисы
приложения для исследования и опросника. Pi получает `read`, `grep`, `find`, `ls`,
`planning_git`, `questionnaire`, `research_check`, без обычных `bash`, `write`, `edit`.
Расширение Pi дополнительно проверяет каждый вызов. Навыки не меняют эти права.
Автоматическое создание опыта навыков по результату исследовательского запуска отключено.

## Проверки

`research_check` принимает `command: string[]` и необязательный `cwd`, относительный
путь внутри проекта. Рабочая папка проверяется после раскрытия ссылок. Вывод поступает
в журнал действий; ответ содержит код завершения или причину недоступности. Права
и каталоги записи невозможно передать параметрами инструмента.

Сервис `ResearchCheckRunner` независим от установленного Codex. Перед первой проверкой
он выполняет нативную пробу записи/удаления/переименования исходника, создания нового
файла, записи Git и разрешённого артефакта. Если проба не подтверждает изоляцию,
остаются только чтение и анализ. Резервного запуска произвольной команды без изоляции нет.
Проба доказывает ограничение записи, поэтому она нужна только произвольным командам:
точный read-only allowlist Git несёт своё доказательство в аргументах и от пробы не зависит.

| ОС | Защита |
| --- | --- |
| macOS | Seatbelt через системный `sandbox-exec`, запрет записи по умолчанию; отдельная группа процессов. Системные вызовы смены группы/сессии и `posix_spawn` запрещены, чтобы потомки не отделялись. Java использует поддерживаемый запуск `fork`. Инструменты, которым обязательно нужен `posix_spawn`, могут не работать; ограничение показывается в результате. |
| Linux | `bubblewrap` (`/usr/bin/bwrap` или `/bin/bwrap`), корень только для чтения, отдельные user/PID/IPC/network namespaces, без capabilities и вложенных user namespaces. Нужны разрешённые ядром user namespaces и версия bwrap с `--disable-userns`. Сеть отключена. |
| Windows | JNA, 64-битная Windows и NTFS. Токен запуска с низким уровнем целостности: `CreateRestrictedToken` c `DISABLE_MAX_PRIVILEGE` и `SetTokenInformation(TokenIntegrityLevel)` в Low. Чтение остаётся пользовательским, запись вверх запрещена: каталоги результатов на время проверки получают наследуемую метку Low, а проект и реальный `.git` остаются Medium. После проверки исходная метка возвращается и её восстановление доказывается. Отдельный desktop с меткой Low, наследуются только stdio, Job Object с `KILL_ON_JOB_CLOSE`, без breakaway. |

**Почему не `WRITE_RESTRICTED`.** Процесс с токеном `WRITE_RESTRICTED` завершается
`STATUS_DLL_INIT_FAILED` (`0xC0000142`) до первой инструкции. Воспроизведено с run SID,
logon SID, `Everyone`, `RESTRICTED_CODE` и всеми группами самого токена в списке restricting
SID, с выдачей run SID прав на `winsta0` и на отдельный desktop, с `CREATE_NO_WINDOW` и с
`DETACHED_PROCESS`, для `powershell.exe`, `java.exe`, `cmd.exe` и обёртки `Git\cmd\git.exe`;
та же подготовка с обычным токеном процесс запускает. Консоль такому процессу недоступна
в принципе: её выделение требует объекта, который может записать только SYSTEM, поэтому
дерево консольных процессов не стартует ни с каким набором restricting SID кроме SYSTEM,
а SYSTEM обнулил бы саму защиту. Метка целостности — штатный механизм Windows, который
изоляцию записи сохраняет и деревья процессов не ломает: `ResearchSandboxNativeTest` на
реальной ОС подтверждает отказы записи исходников и `.git`, запись артефакта, hardlink и
symlink, отмену, повтор и восстановление после краха, поэтому
`WindowsResearchSandbox.confinesWrites` остаётся `true`.

Read-only Git (`GIT_READ_ONLY`, `METADATA_READ_ONLY`) больше не требует пробы песочницы ни
на какой платформе: это точный allowlist аргументов, усиленный `--no-optional-locks`, пустым
`core.hooksPath`, `GIT_OPTIONAL_LOCKS=0` и закалёнными исполняемыми ключами конфига
(`SandboxCheckDriver.hardenedGitConfiguration`: `core.fsmonitor=false`, `core.editor=true`,
`sequence.editor=true`, `commit.gpgSign=false`, `tag.gpgSign=false`, `protocol.allow=never`,
`core.quotepath=false`),
а экран проекта, ожидание worktree и возобновление агента читают Git раньше любого запуска.
Системный и глобальный конфиг пользователя остаются в силе: они решают преобразование окончаний
строк, файл атрибутов, `safe.directory` и фильтры, то есть какие байты Git считает неизменными.
Их отключение (`GIT_CONFIG_NOSYSTEM`, `GIT_CONFIG_GLOBAL`) заставляло приложение видеть
CRLF-чекаут как незакоммиченные изменения и коммитить в управляемой копии байты без принятой у
пользователя нормализации; согласие с git пользователя закрепляет
`GitConfigurationAgreementNativeTest`, а закалённые ключи — `OwnedGitMetadataPolicyTest`.
Отказ песочницы по-прежнему оставляет произвольные команды (`PROTECTED_PROJECT`)
отказанными: резервного запуска без изоляции нет. Платформа, которая не может ограничить
запись вовсе (`ResearchSandbox.confinesWrites = false`), выполняет такие чтения без файловой
политики и не делает вид, что они изолированы.

Новые артефакты разрешены только в стандартных каталогах рядом с обнаруженными
манифестами, включая модули:

| Манифест | Каталоги |
| --- | --- |
| Gradle | `.gradle`, `build` |
| Maven, Cargo | `target` |
| Node | `build`, `dist`, `.next`, `coverage`, `.cache` |
| Python (`pyproject.toml`, `pytest.ini`) | `.pytest_cache`, `.mypy_cache`, `.ruff_cache` |

Каталоги с отслеживаемыми файлами, неизвестными существующими файлами, symlink,
hardlink или Windows reparse point не получают запись. Расширение `.txt` и запись
в `.gitignore` не делают пользовательский файл артефактом. Для повторных проверок
приложение хранит SHA-256 своих результатов вне доступной песочнице области: только
неизменённые результаты предыдущего защищённого запуска могут перезаписываться.
Обычная сборка до включения исследования не считается подтверждением происхождения
результатов. При изменении файлов извне каталог снова защищается. Реальные `.git`,
`git-dir` и `git-common-dir` защищены, в том числе в worktree и при выборе подпапки.
Нестандартные выходные пути не разрешаются автоматически.

Кэши Gradle, npm, pip, Cargo, Python, .NET и NuGet и временные файлы перенаправляются
в отдельную папку запуска. Общие пользовательские кэши доступны лишь для чтения;
секреты окружения не передаются. Установка зависимостей и исправления не являются
исследовательскими проверками. При отсутствии нужных зависимостей или несовместимой
системе сборки агент объясняет ограничение. Каталоги сборки могут остаться в проекте;
временные кэши удаляются по окончании, остановке и восстановлении прерванной проверки.

У каждого запуска есть лимит 15 минут и запись владельца процесса. Завершение команды,
отмена и закрытие приложения завершают дочерние процессы. При восстановлении сначала
проверяются PID, время запуска и владелец, затем удаляются временные ресурсы.

## Нативная приёмка

Запускать **на каждой ОС**, из этой ветки, с установленными Git, JDK/Gradle toolchains,
движками Pi и Codex; на Unix также нужен C compiler (`cc`) для проверки отделения
процессов. Используется локальная детерминированная модель HTTP, платных запросов нет.
На Windows нужен PowerShell; запускать из обычной пользовательской учётной записи.

macOS / Linux:

```sh
./gradlew :shared:jvmTest --tests '*Research*Test' --tests '*CodingInteractionModeTest' \
  -Pmagicpaper.research.native=true -Pmagicpaper.pi.it=true -Pmagicpaper.codex.it=true
```

Windows PowerShell:

```powershell
.\gradlew.bat :shared:jvmTest --tests '*Research*Test' --tests '*CodingInteractionModeTest' `
  -Pmagicpaper.research.native=true -Pmagicpaper.pi.it=true -Pmagicpaper.codex.it=true
```

Нужны успешные результаты `ResearchSandboxNativeTest`, `ResearchRuntimeIntegrationTest`,
`ResearchModeRenderTest`, `CodingResearchModeTest`, `ResearchWorkspacePolicyTest` и
`CodingInteractionModeTest`. Проверяются реальные отказы записи, удаления, переименования,
ссылки, Git, дочерние процессы, артефакты, отмена, повтор и восстановление. Недоступная
песочница является провалом нативной приёмки, хотя приложение корректно оставляет чтение.
При обычном запуске JVM-тестов нативные тесты и реальные движки включаются только этими
флагами, поэтому обычный зелёный `jvmTest` не заменяет три нативных прогона.

Сохранить `shared/build/test-results/jvmTest` и `shared/build/reports/tests/jvmTest`,
ОС/архитектуру и commit SHA. Скриншоты интерфейса: `shared/build/reports/research-mode`.
Общий регресс: `:shared:jvmTest`, `:desktopApp:build`, `:shared:compileKotlinJs`,
`:shared:compileKotlinWasmJs`, `:shared:compileAndroidMain` (нужен Android SDK).

Ветка `codex/research-mode`, worktree `/private/tmp/MagicPaper-research-mode`.
Для этой реализации пользователь 9 сентября 2026 года разрешил локальное слияние
после проверок macOS и сверки Windows/Linux с документацией, без фактических прогонов
Windows/Linux. Приведённые нативные тесты сохраняются для дальнейшей приёмки этих ОС.
Перед слиянием включить последние коммиты локального `main` и повторить затронутые
проверки. Публикация в GitHub не требуется.

Windows-приёмка выполнена фактически 22 сентября 2026 года (Windows 11, x64, NTFS) на ветке
`fix/restore-child-sessions-after-crash`: `.\gradlew.bat :magic-agent:checks:impl:jvmTest
-Pmagicpaper.research.native=true --rerun-tasks` — 85 тестов, 0 отказов, 1 пропущен (Unix-only).
Проходят `ResearchSandboxNativeTest` (проба песочницы, отказы записи/удаления/переименования
исходников и `.git`, запись артефакта, hardlink и symlink, потомки после завершения и отмены,
повторные проверки, восстановление после краха отдельной JVM), `NativeCheckProcessNativeTest`,
`CheckBinaryNativeTest` и `NativeCheckReceiptTest`. Дополнительно подтверждён фактический
Git-read через обёртку `Git\cmd\git.exe` в реальном репозитории: `git --version`,
`rev-parse --is-inside-work-tree` и `symbolic-ref --quiet --short HEAD` возвращают код 0 — это
те же команды, которыми `GitTaskWorkspace.availability` решает доступность worktree-режима.
Эта цепочка целиком закреплена opt-in тестом `:magic-agent:runtime:impl:jvmTest
-Pmagicpaper.research.native=true --tests '*GitTaskWorkspaceAvailabilityNativeTest'*`: настоящий
репозиторий, настоящий владелец проверок и настоящая песочница обязаны дать `available = true`.
Linux по-прежнему не запускался; macOS в этом прогоне не проверялась.

Windows-приёмка конфигурации Git выполнена 23 сентября 2026 года (Windows 11, x64, NTFS,
системный `core.autocrlf=true`, в рабочем репозитории 1826 файлов `i/lf w/crlf`) на ветке
`fix/restore-child-sessions-after-crash`: `.\gradlew.bat :magic-agent:checks:impl:jvmTest
:magic-agent:runtime:impl:jvmTest -Pmagicpaper.research.native=true` — `:magic-agent:checks:impl`
90 тестов, 0 отказов, 1 пропущен (Unix-only); `:magic-agent:runtime:impl` 1194 теста, 8 отказов —
те же, что в задокументированной Windows-базе (семь `TaskWorktreeIntegrationChecksTest` и
`ResearchCheckBridgeTest.piProtocolReusesTheNativeCallIdAcrossWireRetries`), новых нет.
`GitConfigurationAgreementNativeTest` подтверждает на настоящем репозитории, что read-only
`status --porcelain --untracked-files=all` приложения даёт тот же вердикт, что и `git status`
пользователя, на чекауте с преобразованием окончаний строк; с отключённым системным и глобальным
конфигом тот же тест красный (`expected:<[]> but was:<[M source.txt]>`).
`GitTaskWorkspaceTest.realCheckOwnerDeliversATaskThroughNativeGit` проводит задачу через штатного
владельца проверок — `worktree add`, `add`, `commit`, `merge --ff-only` с закалённым конфигом —
и доставляет результат в исходную папку. Linux и macOS в этом прогоне не проверялись.

## Сверка с контрактами платформ

Проверены флаги `DISABLE_MAX_PRIVILEGE` и `TokenIntegrityLevel`, состав метки `S:(ML;OICI;NW;;;LW)`,
доступ `WRITE_OWNER` для понижения метки и отдельный desktop из [CreateRestrictedToken](https://learn.microsoft.com/en-us/windows/win32/api/securitybaseapi/nf-securitybaseapi-createrestrictedtoken)
и [TOKEN_MANDATORY_LABEL](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-token_mandatory_label).
`WRITE_RESTRICTED` с уникальным restricting SID на Windows не применяется: выделение консоли требует
объекта, доступного на запись только SYSTEM, поэтому любое дерево консольных процессов (обёртка
`Git\cmd\git.exe`, `cmd`, Gradle) умирало с `STATUS_DLL_INIT_FAILED` (0xC0000142) до первой команды,
проба песочницы никогда не подтверждала защиту, и все sandbox-проверки — включая Git-чтения, от которых
зависит доступность worktree-режима — оставались заблокированными. Наборы restricting SID
(Everyone, RESTRICTED_CODE, logon SID) этот отказ не снимают; проверено фактическим запуском.
Токен имеет требуемые `TOKEN_DUPLICATE | TOKEN_ASSIGN_PRIMARY | TOKEN_QUERY` для
[CreateProcessAsUserW](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessasuserw);
дескриптор, возвращённый `CreateRestrictedToken`, не может понизить собственный уровень, поэтому метка
ставится на его полную копию. Поток стартует suspended и возобновляется после назначения в Job Object. Проверены
[список наследуемых handle](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-updateprocthreadattribute),
[структура ограничений Job Object](https://learn.microsoft.com/en-us/windows/win32/api/winnt/ns-winnt-jobobject_extended_limit_information)
и [метка целостности](https://learn.microsoft.com/en-us/windows/win32/secauthz/mandatory-integrity-control).
Ошибка привилегий запуска, метки или файловой системы блокирует проверку; приложение
не использует другой способ запуска с повышенными правами.

Параметры namespace, bind mount, `--die-with-parent`, `--disable-userns` и сброс
capabilities сверены с [исходниками bubblewrap](https://github.com/containers/bubblewrap/blob/main/bubblewrap.c).
Для Unix launcher нужны `posix_spawn_file_actions_addchdir_np` и 64-битная libc с
поддерживаемым ABI. На macOS константы spawn/syscall сверены с системным SDK;
проба защиты и тесты атак выполняются реальными системными вызовами. Сверка
документации не считается фактическим прохождением нативных тестов Windows/Linux.
