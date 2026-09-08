# Каталог и импорт по ссылке

[Подключение после review, восстановление проектной версии при откате,
точные исходящие HTTP-байты и подтверждения согласования](skills-catalog/evidence/HANDOFF.md)
— адресные assertions и новый прогон текущего дерева: **161/0/0/0**, BUILD SUCCESSFUL.

[Адресная приёмка двух UI-импортов, метаданных, дедупликации, офлайн-reopen,
ошибок/отмены и согласия нового checksum](skills-catalog/evidence/README.md)
содержит журнал реальных UI-действий, сохранённые метаданные и XML-сводку
последнего повторного прогона: **161/0/0/0**, BUILD SUCCESSFUL.

**Актуальный итог после диагностического окна: 161 tests, 0 failures/errors/skipped,
BUILD SUCCESSFUL; desktop-компиляция UP-TO-DATE.** Живые GitHub-импорты выполнены;
UI diff обновления и отсутствие рабочих данных в исходящем HTTP проверены
адресно (подробности в последнем разделе). Старые результаты ниже — история.

## Реализовано

По согласованию `mtsgor55-e2dec8eb-turn-0-action-1` добавлены отдельные
GithubSkillCatalog и SkillCatalogPanel, вход «Добавить скилы из репозиториев»
в ProjectSkillsPanel. Перед работой сверены опубликованные контракты,
trusted-text.md и staged/unstaged diff. LocalSkillRepository, SkillReleaseStore
и Dependencies.jvm.kt этим исполнителем не изменялись; используется прежний
общий экземпляр хранилища. Чужие изменения сохранены.

Каталог поддерживает локальный поиск источников и установленных пакетов,
введённую GitHub-ссылку, явное согласие на сеть, разрешение ref в полный SHA,
выбор одного из найденных SKILL.md, предпросмотр и штатный атомарный install.
ZIP-проверки, лимиты, карантин и неизменяемость версии переиспользованы.
Есть отмена загрузки/предпросмотра, ошибки, сравнение предыдущих установленных
версий с кандидатом, отдельный review точного checksum и проверка обновления.

Исходный SKILL.md сохраняется побайтно вместе с frontmatter; UI показывает его
отдельно от метаданных адаптера. При отсутствии манифеста ID детерминирован
из repository/path, версия хоста — из SHA (это не версия издателя и не временной
порядок), имя — из пути. Лицензия не угадывается: null блокирует подключение
до обоснованного review. Корневой LICENSE сохраняется как доказательство
UPSTREAM-LICENSE.txt, но его применимость не утверждается автоматически.
Полный SHA и фактический источник сохранены; checksum вычисляет общий валидатор.

Импорт не вызывает review, bindProject, activate или coding. Идентичный повтор
не создаёт дубль. Новая версия не меняет project pin и не наследует review/opt-in.
Отмена проверяется после блокирующих загрузок и перед транзакцией; поздний ответ
не публикуется. Уже завершённый атомарный commit не отменяется задним числом,
что отмечено в UI. Мгновенное прерывание socket не обещается: действуют таймауты.
Ресурсы не исполняются, Git/checkout/hooks не запускаются.

## Реальная сеть, не фикстуры

В каталоге один проверенный репозиторий с выбором из 14 пакетов:
`https://github.com/obra/superpowers/tree/95c6e1633630a1462679cf1284b95ec458a27a8e`
(ref v4.0.0). Это не демонстрационные записи.

Свежий SHA b36e0829c6d0140e93cfef2ca599b1b07d4a7797 был отвергнут существующей
ZIP-проверкой: в архиве есть симлинк AGENTS.md. Проверка не ослаблялась.
Весь архив репозитория должен укладываться в существующие лимиты; большой
репозиторий или архив с недопустимыми записями отвергается целиком.

Живой GitHub API потребовал минимальной правки SkillPublicDownload:
Accept: */* вместо application/zip (прежний давал HTTP 415), фиксированный
User-Agent: MagicPaper-Skill-Catalog/1 (без него HTTP 403). URL/IP/TLS/redirect
политики не менялись. Только анонимные GET без тела, Authorization и cookie;
компонент не получает проект, чат, опыт, профиль или ключи LLM.

Оба реально импортированных пакета имеют SHA
95c6e1633630a1462679cf1284b95ec458a27a8e:

| Путь | SHA-256 манифеста |
| --- | --- |
| skills/systematic-debugging/SKILL.md | b06dc5851d90f183dc413ff2187d25c26e1e9c28c9a2f50fa64700c337330ca6 |
| skills/verification-before-completion/SKILL.md | 5eed45c6869136cac918aeac50111f96f0a48291cd24b1567fce0a188b8ecfd7 |

Оба QUARANTINED, лицензия UNKNOWN-BLOCKED-UNTIL-REVIEW;
active/projects/projectTextConsents пусты.

- GithubSkillCatalogLiveTest: поиск источника, первый импорт; независимая
  введённая ссылка /tree/v4.0.0 с реальным разрешением через API, второй импорт;
  дедупликация; повторное открытие хранилища без сетевого транспорта, чтение
  и поиск обеих версий. PASS.
- SkillCatalogLiveUiTest: настоящие ProjectSkillsPanel/SkillCatalogPanel в
  ImageComposeScene. Semantics-действия открывают каталог, вводят debugging,
  выбирают источник, подтверждают сеть, выбирают один пакет и импортируют.
  Затем вводят ссылку в production TextField и импортируют другой пакет,
  возвращаются в проект; restart находит обе записи без opt-in. Реальная сеть,
  без fake fetch и подмены callbacks. PASS. Это сквозной headless UI-сценарий,
  **не** ручная проверка desktop-окна или координат мыши. Semantics может
  действовать вне viewport; прокрутка до каждого элемента этим не доказана.

Артефакты: shared/build/reports/skills-catalog-live/imports.txt,
shared/build/reports/skills-catalog-live-ui/imports.txt и project-after-imports.png.

## Фикстуры и регрессия

GithubSkillCatalogTest: четыре сценария — выбор пакета/исходные ресурсы/restart/
review без opt-in; обновление требует нового review, сохраняет старый pin до
подключения и не переносит opt-in; отмена во время блокирующего fetch отбрасывает
поздний ответ; неверные URL/отказ согласия не вызывают сеть, сбой и отменённая
установка не меняют snapshot. PASS.

SkillCatalogPanelTest: рендер 440/1000 px без сети, PASS исполнения. Снимки в
shared/build/reports/skills-catalog-ui/. Визуальный аудит изображений данной
моделью недоступен и не заявляется.

Промежуточный тест выявил ClassFormatError из-за return@key в Compose; заменён
на if/else. Повторный рендер и общая регрессия успешны; дефект не приписан
соседним изменениям.

```sh
MAGICPAPER_SKILL_CATALOG_LIVE=true ./gradlew --no-daemon :shared:jvmTest --tests '*SkillCatalogLiveUiTest'
MAGICPAPER_SKILL_CATALOG_LIVE=true ./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingComposerRenderTest' --tests '*CodingChatScrollTest' :desktopApp:compileKotlin
git diff --check
git diff --cached --check
```

Промежуточный общий прогон до интеграции отката: BUILD SUCCESSFUL, **111 tests,
0 failures/errors/skipped**. Оба live-теста действительно включены переменной
среды; без неё JUnit помечает их skipped. Desktop compile — UP-TO-DATE после
предшествующей успешной компиляции. Лог: shared/build/skills-catalog/regression.log.
SHA-256 всех shared/src до/после этого прогона совпали; обе проверки diff — PASS.
Стандартные XML впоследствии перезаписаны соседним прогоном: их текущие счётчики
не следует выдавать за этот результат. Живые Pi/Codex и LLM-вызовы не выполнялись.

## Интеграция проектного отката

Передача API подтверждена координатором mtsgor55-e2dec8eb-turn-1-action-0.
Перечитаны docs/skills-project/project-rollback.md, актуальные методы и оба diff.
Добавлен ProjectSkillRollback и его вызов из ProjectSkillsPanel. Хранилище,
DI и чужие STOP/IDLE/DRAFT изменения не переписывались.

Preview получает generation и previousProjects из одного snapshot, показывает
точные текущие/предыдущие id@version и checksum, метаданные, источник,
инструкции и добавляемые разрешения относительно текущей версии того же ID.
Пустой target явно означает отключение всех навыков проекта. Обязательна отметка
просмотра изменений; новые permissions требуют второй отметки. Подтверждение
вызывает только durable rollbackProject с сохранённым generation/target и
trustedCodingText=false. Opt-in в форме отката отсутствует.

При исключении preview и согласия сбрасываются; показана причина и предложение
открыть свежий preview. Недоступные/непроверенные версии запрещены при preview,
хранилище повторно проверяет байты перед commit. При успехе обновляются pins
родительской панели и очищается незавершённое подтверждение подключения.

ProjectSkillRollbackUiTest — **фикстуры**, настоящий Compose UI:
- v1 NETWORK → v2 без NETWORK в проекте A; проект B остаётся на v2;
- preview содержит оба checksum и NETWORK; кнопка недоступна без просмотра и
  отдельного разрешения; отмена сохраняет snapshot;
- внешний review меняет generation после preview: подтверждение отклоняется,
  snapshot неизменен, требуется свежий preview;
- удалён SKILL.md целевой версии после preview: откат отклонён без изменений;
- после восстановления файла и нового согласия откат возвращает A на v1,
  B остаётся на v2, opt-in A сброшен; ранее полученный run selection неизменен;
- UI переключается A→B→A; новый экземпляр хранилища читает A=v1, B=v2 и redo=v2.

```sh
./gradlew --no-daemon :shared:jvmTest --tests '*ProjectSkillRollbackUiTest' :desktopApp:compileKotlin
MAGICPAPER_SKILL_CATALOG_LIVE=true ./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*ChatScrollToBottomTest' --tests '*CodingToolPreview*Test' --tests '*CodingComposerRenderTest' --tests '*RequestPinsRenderTest' --tests '*CodexCodingPermissionsTest' --tests '*CodingDraftUpdatesTest' --tests '*ActiveCodingChatRenderTest' --tests '*AgentUiThreadTest' :desktopApp:compileKotlin
```

Оба прогона BUILD SUCCESSFUL. Первый: новый UI-тест и desktop-компиляция
выполнены. Второй: **136 tests, 0 failures/errors/skipped**, desktop UP-TO-DATE;
живые сетевые импорты выполнены повторно. Все shared/src совпали до/после
прогона. Машинная сводка: docs/skills-catalog/verification.json. Изолированная
копия XML: shared/build/skills-catalog/final-xml, лог: final.log в том же каталоге.
Снимок UI: shared/build/reports/skills-catalog-rollback/after-rollback.png.
Проверка headless, ручное окно и визуальный аудит не заявляются.

## Владение изменениями для повторной проверки ms_project_skills

Изменены: SkillPublicDownload.kt (два HTTP-заголовка), ProjectSkillsPanel.kt
(вход каталога, if/else вместо return@key и вызов отката; чужой opt-in сохранён).
Добавлены в JVM data/skills: GithubSkillCatalog.kt; plugins/builtin:
SkillCatalogPanel.kt, ProjectSkillRollback.kt. Добавлены JVM tests data/skills:
GithubSkillCatalogTest.kt, GithubSkillCatalogLiveTest.kt, SkillCatalogPanelTest.kt,
SkillCatalogLiveUiTest.kt, ProjectSkillRollbackUiTest.kt. Документы:
docs/SKILLS-CATALOG.md и docs/skills-catalog/verification.json.

После указанного прогона обнаружены соседние изменения Coding.kt,
CodingRunRecorderTest.kt и CodingResumeTest.kt. Они сохранены; результат 136
тестов относится к зафиксированному снимку, а не к последующим изменениям.
Ни один общий исходник каталогом после этого прогона не изменялся.

После соседних изменений выполнена ещё одна регрессия той же полной командой
с добавлением `--tests '*CodingRunRecorderTest' --tests '*CodingResumeTest'`.
**BUILD SUCCESSFUL; 158 tests, 0 failures/errors/skipped**, desktop UP-TO-DATE.
Оба настоящих GitHub-теста снова выполнены. Все shared/src до/после этого
прогона совпали; `git diff --check` и `git diff --cached --check` — PASS.
Лог shared/build/skills-catalog/recheck.log; сохранённые XML recheck-xml рядом;
сводка recheck в docs/skills-catalog/verification.json. Эта проверка включает
обнаруженные соседние изменения, не приписывает их реализацию каталогу и
не заменяет повторную проверку ms_project_skills последующего дерева.

## Адресные доказательства diff и приватности; завершение после паузы

До паузы добавлены два тестовых файла и только две производственные точки:
SkillCatalogPanel принимает необязательный catalogFactory (по умолчанию прежний
production GithubSkillCatalog); SkillPublicDownload выделяет прежние байты
HTTP в requestBytes, непосредственно используемый socket.outputStream.write.
Политики URL/DNS/TLS и состав запроса не изменены. Четыре SHA-256 совпали
с переданными перед диагностикой; после возобновления исходники не менялись.

### Diff обновления — PASS, фикстуры сети, настоящий UI

`SkillCatalogUpdateEvidenceTest.updateDisplaysOldAndNewChecksumsInstructionsAndResourceDiffBeforeImport`:
изначально установлена проверенная версия commit `111…111` с OLD INSTRUCTION,
changed.txt и removed.txt, проект A подключён с opt-in. Действие production UI
«Проверить обновление…» не отправляет запрос до отдельного сетевого согласия.
После согласия HEAD разрешается фикстурой в `222…222`; выбирается тот же
skills/example/SKILL.md. **В semantics отрисованного preview** проверены:
«До:», «После», оба фактических checksum, OLD INSTRUCTION, NEW INSTRUCTION,
`Добавлены: [added.txt]`, `удалены: [removed.txt]`,
`изменены: [SKILL.md, changed.txt]`. Это проверка показанного текста,
а не только вычисленного model diff. Snapshot до импорта побайтно по значениям
не изменён. После действия «Импортировать без подключения» новая версия
QUARANTINED без review; старые pins и consent неизменны. Подключение новой
версии до review отвергается; после нового review и отдельного bind consent
она подключается, но trustedText=false.

Артефакт: `.gradle/skills-catalog-final/rendered-diff.txt` содержит текст дерева UI.
Сеть здесь подменена явно; это не доказательство живого upstream-обновления.
Оба живых импорта из поиска/введённой ссылки проверены отдельными live-тестами.

### Исходящие загрузочные запросы — PASS, точные байты

`SkillDownloadPrivacyTest` содержит два теста. Первый создаёт синтетические
локальные маркеры кода проекта, чата, опыта и ключа LLM; устанавливает тестовые
CookieHandler/Authenticator, возвращающие эти маркеры, с восстановлением в
finally. Проверяет **весь**, а не выбранные поля, production requestBytes для
API `/repos/owner/repo/commits/HEAD` и ZIP `/owner/repo/zip/<40 SHA>`:
GET, Host, фиксированные User-Agent/Accept/Accept-Encoding/Connection,
заключительный CRLF и строго пустое тело. Нет ни одного приватного маркера,
Authorization, Cookie, Referer, Content-Length или Proxy-Authorization;
credential/cookie callbacks вызваны ноль раз. Второй тест отвергает URL с
userinfo, query, fragment, HTTP и неподтверждённым origin до сериализации.

Артефакты `.gradle/skills-catalog-final/outbound-http.txt` и `assertions.txt`.
Это побайтовый тест plaintext, передаваемого TLS-сокету, **не** перехват пакетов.
Code review: единственная HTTP-запись транспорта использует эти байты;
редиректы снова проходят validateUrl; каталог передаёт только канонические
публичные owner/repo/ref/SHA, не имеет параметров проекта/чата/опыта/ключей.
Сами файлы приложения транспорт не читает. IP, TLS hostname и публичный путь
естественно видны серверу; намеренное помещение пользователем данных в имя
публичного источника не является обещанной системой предотвращения утечек.
Проверка относится к запросам загрузки, не к произвольному содержимому ответа
чужого репозитория и не к последующим LLM-запросам coding-агента.

### Последовательная регрессия актуального дерева

Пауза снята сообщениями координатора turn-12/13 и пользовательским go.
Wrapper/Test Executor перед стартом не обнаружены. Прочитан
`docs/skills-project/final-matrix.md`; сравнение с authorized-recheck/after.json
обнаружило 23 изменённых/добавленных исходника соседней работы (в том числе
UiState, OrchestrationStatus, CodingScreen и planning). Это не тот же старый
снимок. Staged/unstaged diff сохранены, пересечения ProjectSkillsPanel,
Dependencies.jvm, MagicPaperViewModel и coding UI просмотрены и сохранены.
DI/хранилище/соседние исходники не редактировались.

```sh
MAGICPAPER_SKILL_CATALOG_LIVE=true ./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*ChatScrollToBottomTest' --tests '*CodingToolPreview*Test' --tests '*CodingComposerRenderTest' --tests '*RequestPinsRenderTest' --tests '*CodexCodingPermissionsTest' --tests '*CodingDraftUpdatesTest' --tests '*ActiveCodingChatRenderTest' --tests '*AgentUiThreadTest' --tests '*CodingRunRecorderTest' --tests '*CodingResumeTest' :desktopApp:compileKotlin
git diff --check
git diff --cached --check
```

**BUILD SUCCESSFUL; 161 tests, 0 failures/errors/skipped.** Новый UI diff (1),
HTTP privacy (2), оба live GitHub (2) и UI rollback (1) отдельно сверены по XML.
Desktop compileKotlin UP-TO-DATE. Все shared/src до/после совпадают по SHA-256.
Обе проверки diff — PASS. Лог, отдельная копия XML, before/after hashes и diff
в `.gradle/skills-catalog-final/`; latest в docs/skills-catalog/verification.json.
Это единый прогон, не сумма пересекающихся выборок диагностики.

Исторические FAIL **93/15** и **155/86** остаются FAIL, отдельно от этого PASS:
ссылки и сохранённые трассы в docs/skills-project/final-matrix.md и
`.gradle/skills-diagnostics/authorized-recheck/catalog-historical/`.
Исчезновение ClassNotFoundException не устанавливает первопричину;
исправление кэшей/ZIP не заявляется. Ручная desktop/визуальная приёмка и live
Pi/Codex остаются NOT_RUN и передаются соответствующим следующим этапам.

Окно освобождено, активных операций этого исполнителя нет. Для ms_project_skills:
новых исходников после его диагностики не внесено; только этот отчёт и latest
сводка. Повторная адресная проверка нужна при следующих изменениях общих файлов,
а не как блокирующий повторный запрос разрешения на уже завершённый прогон.
К списку владения выше добавлены SkillCatalogUpdateEvidenceTest.kt и
SkillDownloadPrivacyTest.kt; уточнения двух production-файлов описаны выше.
