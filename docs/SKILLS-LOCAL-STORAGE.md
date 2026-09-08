# Локальное хранилище пакетов навыков

Этап «Встроенное локальное хранилище», 2026-09-07. Основание:
[карта компонентов](SKILLS-DISCOVERY.md) и [контракт ms_contract](SKILLS-CONTRACT.md).

## Реализация и входы

- [LocalSkillRepository](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepository.kt):
  установленный каталог, локальный поиск, неизменяемые версии, наблюдаемые источники,
  review, активный/предыдущий набор, diff, backup/restore и `activeInstructions()`.
- [SkillPackageImporter](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/SkillPackageImporter.kt):
  четыре явно вызываемых вида импорта; все проходят прежний `SkillPackageValidator`.
- [SkillPublicDownload](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/SkillPublicDownload.kt):
  отдельный анонимный GET-транспорт без доступа к проекту, чату, профилю LLM,
  cookie, proxy, credential helpers, клиентским TLS-ключам или заголовку Authorization.
- [MacSkillDirectoryReader](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/MacSkillDirectoryReader.kt):
  чтение каталогов через дескрипторы на macOS, где NIO не предоставляет
  SecureDirectoryStream; переиспользуется JNA из установленной зависимости OSHI.
- [SkillReleaseStore](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/SkillReleaseStore.kt):
  общий автомат получил начальный снимок и callback сохранения, вызываемый **до**
  публикации нового состояния. Прежний конструктор/поведение в памяти совместимы.
- [LocalSkillsPlugin](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/plugins/builtin/LocalSkillsPlugin.kt):
  панель «Пакеты навыков» в списке плагинов desktop. Подключена через
  [общую DI](../shared/src/commonMain/kotlin/io/aequicor/magicpaper/di/Dependencies.kt)
  и [desktop DI](../shared/src/jvmMain/kotlin/io/aequicor/magicpaper/di/Dependencies.jvm.kt).
  Хранилище открывается при показе панели; закрывается при завершении приложения.

Путь приложения: `~/.MagicPaper/skill-packages`. Тесты используют исключительно
временные каталоги. Работа исполнителя не создавала библиотеку в пользовательском
home, не обращалась к рабочим данным для загрузок и не выполняла публикаций.

Панель показывает источник, зафиксированный импортёром, отдельно от утверждений
автора, а также лицензию, версию, разрешения и карантин. Для пакета без манифеста
пользователь вводит ID, точную версию, название и назначение до импорта. Авторство
и лицензия не выдумываются. Проверка происхождения, лицензии и содержимого — три
отдельных флага с обязательным обоснованием. Установка не проставляет их.

Предпросмотр показывает полный набор до/после, исходные и новые метаданные,
инструкции, источники, добавленные/удалённые/изменённые ресурсы, их размеры и SHA-256.
Текстовые ресурсы до 64 KiB показываются непосредственно; большие и двоичные
явно обозначены как требующие просмотра исходного файла. Нет исполнения ресурсов.
Точные установленные зависимости добавляются в предпросмотр; сеть для них
автоматически не вызывается. Активация требует отдельного подтверждения изменений
и перечисленных новых разрешений, привязанного к generation и всем checksum.

## Хранение и восстановление

`releases/<manifest-sha256>/` содержит точные байты манифеста и ресурсов.
Импорт сохраняет собственную копию, а не ссылку на исходный каталог. Новая версия
сначала записывается в приватный staging; файлы и каталоги синхронизируются,
затем staging атомарно переименовывается в каталог версии на том же разделе.
Идентичный повторный импорт идемпотентен, замена байтов прежнего ID/version запрещена.

`snapshot.json` содержит generation, установленные checksum, наблюдаемые источники,
локальные review и обе карты активных версий. Репозиторий держит эксклюзивную
файловую блокировку; внутри экземпляра операции сериализованы Mutex. Сохранение
проверяет generation на диске, записывает временный файл, делает fsync, atomic rename
и fsync родительского каталога. Нет неатомарного fallback. Сбой до смены снимка
оставляет весь прежний набор; недостижимые staging/версии не активируются.
Если ошибка ввода-вывода возникла после начала атомарной записи, экземпляр закрывается:
для определения целиком завершённого состояния требуется повторное открытие.
Физический сбой питания не эмулировался; гарантия ограничена семантикой FS/fsync.

При открытии повторно проверяются все манифесты, байты, review и графы active/
previousActive. Коррупция не превращается в пустую библиотеку и не затирается
следующей установкой. Панель открывает специальный режим восстановления:
обычные операции в нём запрещены до явного восстановления проверенной копии.

Резервная копия включает снимок и точные байты всех достижимых установленных
версий (Base64). Максимальный размер — 128 MiB; размер оценивается до сборки копии.
Хеш копии показывается пользователю для отдельного сохранения. Восстановление
требует этого хеша и явного подтверждения доверия к **локальной резервной копии**:
оно повторно проверяет все пакеты и граф, сохраняет карантин/review и обе карты,
повышает generation и одним commit заменяет снимок. Повреждённые каталоги версий
при подтверждённом ремонте перемещаются в `damaged/`, а не используются.
Обычный импорт пакета/профиля никогда не принимает review из его содержимого.
Хеш копии — контроль целостности, не доказательство авторства чужого backup.
Резервные копии не шифруются и не включают чаты, ключи или настройки LLM.

## Транспорты и границы

| Вход | Реализованная политика |
| --- | --- |
| Каталог | На macOS: openat/O_NOFOLLOW, fstat/readdir по дескрипторам, отказ для ссылок и специальных файлов; на NIO с SecureDirectoryStream: относительное открытие NOFOLLOW. Без безопасного механизма — отказ с предложением ZIP. Все байты ограничены до материализации, затем валидируются. |
| ZIP | Не более 5 MiB входа; проверка центрального каталога до инфляции, совпадение локальных имён, ограничения путей/дубликатов/типов, отказ для шифрования, ZIP64, link-capable extra records и специальных файлов. До 20 MiB payload + 256 KiB манифеста, 512 ресурсов, 1024 записей с каталогами по фактическим потокам. Архивы внутри остаются обычными данными. |
| Git | Поддерживаются публичные GitHub HTTPS-репозитории, архив полного 40-символьного commit SHA через codeload.github.com. Оба origin требуют явного одобрения. Нет запуска Git, hooks, submodules, LFS, smudge/clean или конфигурации репозитория. Branch/tag не принимаются. Остальные Git-хосты пока отвергаются. |
| HTTPS | Пакет ZIP с фиксированным SHA-256 манифеста либо запись SkillCatalogRelease с проверкой полного совпадения метаданных. Пользователь явно подтверждает сеть и origin. Начальный URL и каждый redirect проверяются; до трёх redirects. Нет userinfo/query/fragment, HTTP и произвольных портов. |

DNS ограничен 10 секундами и двумя daemon-worker с ограниченной очередью.
Все полученные адреса проверяются; TCP соединяется с **проверенным IP**, не делая
повторного DNS-разрешения. TLS проверяет исходное имя хоста и системную цепочку
доверия. Private/loopback/link-local/multicast/reserved IPv4, mapped IPv6 и
неглобальные/переходные IPv6 отвергаются консервативным списком. Таймаут TCP —
10 секунд, чтения — 15 секунд, общий предел одного соединения — 60 секунд.
Сжатое HTTP-содержимое не принимается; весь ответ ограничен 5 MiB.

Это JVM/desktop реализация этапа хранения. Android/web адаптеры, остальные Git-хосты
и применение нового активного набора в MagicAgent/Pi/Codex здесь не подключены.
Для следующего этапа runtime предоставлен `activeInstructions()`: только активные
пакеты, повторная проверка байтов, отсутствие сетевого обращения. Старые встроенный
каталог и черновики сохранены в прежнем потоке; legacy `enabled` не повышает доверие
внешнего пакета. Автоматический анализ вредоносного смысла инструкций не заявляется.

## Проверки

[LocalSkillRepositoryTest](../shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepositoryTest.kt)
добавляет 16 тестов:

| Тест | Проверяемый результат |
| --- | --- |
| quarantinedMetadataAndLocalSearchSurviveRestart | Источник/MIT/версия видимы, поиск локален, карантин после открытия, активация запрещена. |
| unknownOriginAndLicenseIndependentlyStayQuarantinedAfterRestart | Каждый неизвестный компонент review независимо блокирует активацию после перезапуска. |
| networkUpgradeDenialAndConsentPersistAndRollbackWorksOffline | Отказ NETWORK сохраняет снимок, согласие активирует; повторное открытие и откат работают без сети. |
| interruptedCommitKeepsEntirePreviousSnapshot | Исключение после записи новой версии, до commit снимка: прежний active и installed сохранены после открытия. |
| interruptedActivationRetainsActiveAndRollbackSnapshot | Прерванное подтверждение не меняет active/previousActive/generation. |
| backupRestoresExactFilesReviewsQuarantineAndBothVersionMaps | Восстановление в новое хранилище, проверка хеша/подтверждения, сохранение карантина, устаревание согласия, откат после открытия. |
| corruptSnapshotAndPayloadFailClosed | Повреждённые JSON и ресурс блокируют обычное открытие, JSON не затирается. |
| explicitRecoveryRepairsCorruptSnapshotAndPayloadWithoutExposingEmptyLibrary | В recovery-режиме каталог/установка/runtime запрещены; подтверждённая копия восстанавливает испорченные снимок и payload. |
| nestedDirectoryImportReadsOnlyRegularFilesAndRejectsLinkedParent | Вложенные ресурсы читаются; ссылка в родительском каталоге отвергается. |
| secondWriterIsRejectedAndVersionBytesAreImmutable | Второй экземпляр блокируется; повтор идемпотентен; подмена прежней версии не меняет снимок. |
| localDirectoryAndZipPassSameValidatorWithoutExecutingScripts | Оба локальных входа проходят общий валидатор; install.sh сохраняется как данные. |
| looseSkillRequiresPreviewAndKeepsUnknownMetadata | SKILL.md без манифеста требует введённых метаданных и не получает выдуманную лицензию. |
| directoryRejectsSymlinkAndZipRejectsTraversalDuplicatesAndUnixLinks | Отказ для ссылки наружу, traversal, дубликата и Unix symlink в ZIP. |
| zipRejectsExpansionBeyondActualStreamBudget | ZIP с малым сжатым размером и payload сверх 20 MiB отвергается по потоку. |
| httpsAndPinnedGitUseAnonymousSourceOnlyRequestsAndRemainQuarantined | Через тестовый транспорт проверены оба сетевых адаптера, явное согласие, точные URL/commit, отсутствие рабочих данных в запросе и карантин. |
| networkPolicyRejectsCredentialsQueriesUnapprovedOriginsAndNonPublicAddresses | URL с credentials/query/fragment, чужой origin/порт и набор неглобальных IP отвергаются. |

[LocalSkillsPanelTest](../shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillsPanelTest.kt)
проверяет headless Compose-отрисовку карантинной библиотеки при ширине 440 и 1000 px;
изображения: `shared/build/reports/local-skills/library-440.png` и `library-1000.png`.
Это проверка отрисовки, не автоматизация полного пользовательского click-through.

Команды и результаты:

- `./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.SkillPackageContractTest'`:
  **BUILD SUCCESSFUL**, прежние 18 тестов контракта совместимы.
- `./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' :desktopApp:compileKotlin`:
  **BUILD SUCCESSFUL**; 48 тестов (18 контракт + 16 хранилище + 1 панель + 13 прежних), без failures/errors.
- `./gradlew --no-daemon :shared:jvmTest :desktopApp:compileKotlin`:
  на промежуточном состоянии **397 тестов, 396 успешных, 1 failure** в
  `CodexCodingPermissionsTest.cacheIsWritableButNetworkStillRequiresReview`:
  expected `auto_review`, actual `user`. Это наблюдение само по себе не доказывало
  причину расхождения; диагностика и повторная проверка приведены ниже.
  Исходники политики и её тест этим этапом не исправлялись. Desktop компилируется.
- Первые обычные запуски Gradle были отклонены песочницей на служебном сокете
  FileLockContentionHandler. Штатные эскалации автоматически одобрены; использован
  обычный кеш, без переноса GRADLE_USER_HOME и без `--offline`.
- Реальное TLS-соединение к внешнему серверу, DNS-rebinding-сервер, Android/web,
  Windows/Linux/macOS Intel и физический сбой питания не испытывались. Сетевые
  адаптеры проверены фикстурами, ограничения адресов — модульными тестами;
  успешное скачивание из живого GitHub не заявляется.

Чужие незавершённые изменения (включая planning/coding/UI) сохранены. Прежние
SKILLS-CONTRACT.md, SKILLS-DISCOVERY.md, SkillPackage.kt и валидатор не менялись.

Заключительная сверка: `git diff --check` — PASS; Python проверил 12 ссылок
документа и отсутствие завершающих пробелов. XML семи классов `data.skills`:
48 tests, 0 failures, 0 errors, 0 skipped. Первая попытка прочитать все XML
попала на заменяемый параллельной сборкой пустой файл; повторная адресная проверка
семи классов этапа завершилась успешно. Финальная Gradle-команда выше завершилась
`BUILD SUCCESSFUL in 9s`. Полный JVM-набор после неё повторно не запускался.


## Повторная проверка для ms_acceptance

Поручение планировщика `mtrbx2vg-09806776-turn-0-action-3`, 2026-09-07.
Этот раздел заменяет прежний статус незавершённого полного JVM-прогона.

Причина предыдущего отказа установлена на уровне конкретного сравнения:
`CodexCodingPermissions.approvals()` уже формировал `approvalsReviewer=user`,
а тест `cacheIsWritableButNetworkStillRequiresReview` ещё сравнивал результат
с `auto_review`. В текущем рабочем дереве тест уже ожидает `user`.
Это не ошибка хранилища и не зависимость результата от Gradle-кеша:
метод записывает строковую константу, проверка сравнивает её напрямую.

Основание текущего поведения проверено отдельно от совпадения строки в тесте:

- diff README описывает переход от автоматического проверяющего к карточке
  пользовательского подтверждения `on-request + user`;
- `CodexAppServerOpenAiSubscription` применяет эту политику при старте,
  продолжении сессии и запуске запроса агента, принимает запросы подтверждения
  и передаёт их в `CodexApprovalBroker`;
- `CodexApprovalRoutingTest.scopesRequestToActiveCodingTurnAndPreservesStringRpcId`
  проверяет отсутствие ответа до решения пользователя и отправку `accept`
  после `ALLOW_ONCE`; остальные проверки контролируют устаревшие запросы,
  смену соединения и завершение запроса агента;
- доступ к сети остаётся выключенным в sandboxPolicy; кеш Gradle по-прежнему
  ограничен отдельным каталогом. Проверки этих ограничений сохранены.

Таким образом, зафиксирована несогласованность реализации и прежнего ожидания
во время перехода политики, а не только предположение о «чужих правках».
В этом проходе исполнитель не менял ни реализацию политики, ни ожидания тестов,
ни README, ни брокер. Это подтверждено сравнением хешей до/после проверки.
Оценка продуктового решения о смене проверяющего не относится к этапу хранения;
здесь проверена согласованность фактических реализации, документации и тестов.

| Команда | Результат |
| --- | --- |
| `./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.llm.CodexCodingPermissionsTest'` | BUILD SUCCESSFUL in 8s; 3 теста, 0 failures/errors/skipped. |
| `./gradlew --no-daemon :shared:jvmTest :desktopApp:compileKotlin` | BUILD SUCCESSFUL in 27s; 75 suites, 414 тестов, 0 failures/errors/skipped. |
| `./gradlew --no-daemon :shared:jvmTest --rerun :desktopApp:compileKotlin` | BUILD SUCCESSFUL in 30s; повторно выполнена именно задача jvmTest; 75 suites, 414 тестов, 0 failures/errors/skipped. Desktop compileKotlin — UP-TO-DATE, успешная инкрементальная проверка, не чистая пересборка. |

Повтор с `--rerun` выполнен потому, что во время первой части аудита независимо
изменился `CodexApprovalBroker.kt`. Перед повтором сняты SHA-256 всех файлов
`shared/src` и README; после повтора набор путей и хеши совпали. Результат
последнего прогона относится к стабильному состоянию этих исходников.
Среди 414 тестов — прежние 18 тестов контракта пакетов и все 48 тестов `data.skills`,
включая перезапуск, прерывание установки/активации, NETWORK-согласие,
карантин, восстановление повреждённых данных и headless-отрисовку панели.
XML подтверждены отдельным Python-подсчётом; `git diff --check` — PASS.

Флаги `magicpaper.pi.it` и `magicpaper.codex.it` не включались. Условные внешние
интеграционные методы возвращаются досрочно, хотя XML не помечает их skipped.
Поэтому 414 тестов не доказывают работу реального Pi/Codex или скачивание
из живого GitHub. Ранее перечисленные платформенные/сетевые ограничения сохранены.
Проверки использовали обычный кеш Gradle и штатное расширение разрешений;
кеш не переносился, `--offline` не применялся, внешних публикаций не было.

### Актуальный состав файлов этапа

В этом продолжении изменён **только** `docs/SKILLS-LOCAL-STORAGE.md`.
Совокупный список реализации этапа (11 файлов), передаваемый в `ms_acceptance`:

1. `docs/SKILLS-LOCAL-STORAGE.md`
2. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/SkillReleaseStore.kt`
3. `shared/src/commonMain/kotlin/io/aequicor/magicpaper/di/Dependencies.kt`
4. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/di/Dependencies.jvm.kt`
5. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepository.kt`
6. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/SkillPackageImporter.kt`
7. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/SkillPublicDownload.kt`
8. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/MacSkillDirectoryReader.kt`
9. `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/plugins/builtin/LocalSkillsPlugin.kt`
10. `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepositoryTest.kt`
11. `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillsPanelTest.kt`

Файлы формата/валидатора и тесты предшествующего этапа, а также изменения
подтверждений Codex, planning и прочего UI не включены в список этого исполнителя.

## Прямые доказательства критериев приёмки

Дополнение по замечанию о недостаточности общего сообщения «тесты успешны».
Ниже приведены входы и проверенные результаты, а не только число тестов.
В этом проходе усилены два тестовых файла; производственная реализация не менялась.

| Критерий | Конкретный сценарий и наблюдаемый результат |
| --- | --- |
| Пользователь видит метаданные импортированного пакета | `LocalSkillsPanelTest.quarantinedLibraryRendersAtNarrowAndDesktopWidths`: через `SkillPackageImporter.directory` импортированы две версии. Для 2.0.0 заданы MIT, READ_PROJECT/NETWORK и заявленный источник `https://example.org/skills/summary`; фактический источник — выбранный временный каталог. Тест сравнивает весь импортированный manifest, фактический путь и QUARANTINED. На обоих сгенерированных изображениях исполнитель визуально подтвердил строки «Резюме · 2.0.0 · карантин», «Источник: …/input», «Заявлено автором: https://example.org/skills/summary; лицензия: MIT», «Разрешения: READ_PROJECT, NETWORK». Кнопка активации недоступна. Версия 1.0.0 отдельно показывает неизвестную лицензию и отсутствие заявленных разрешений. |
| Прерывание обновления сохраняет прежнюю активную версию | `interruptedCommitKeepsEntirePreviousSnapshot`: активируется 1.0.0; при установке 2.0.0 внедряется исключение после сохранения файлов версии, но перед commit снимка. До закрытия весь снимок равен прежнему. После открытия нового экземпляра installed содержит только `local.summary@1.0.0`, а `activeInstructions()["local.summary"]` равно `Summarize 1.0.0`. `interruptedActivationRetainsActiveAndRollbackSnapshot` отдельно прерывает активацию уже установленной 2.0.0: снимок, включая active/previousActive/generation, не изменился; повторное открытие возвращает прежнюю active-карту. Это инъекция ошибки commit, не испытание отключением питания. |
| Импорт не запускает импортируемый код | `localDirectoryAndZipPassSameValidatorWithoutExecutingScripts` и `httpsAndPinnedGitUseAnonymousSourceOnlyRequestsAndRemainQuarantined`: каждый пакет содержит `install.sh` с командой создания файла-маркера по абсолютному пути. После всех четырёх видов импорта маркер отсутствует независимо от рабочего каталога потенциального процесса. Дополнительно `assertContentEquals` подтверждает, что сохранённый `install.sh` побайтно равен исходному. Пакеты в карантине. Проверка исходников импортёра/хранилища не выявила вызовов ProcessBuilder, exec или ScriptEngine для запуска пакетов. HTTPS/Git используют тестовый транспорт; реальный внешний сервер здесь не запускался. |
| Установленные навыки доступны без сети | `networkUpgradeDenialAndConsentPersistAndRollbackWorksOffline`: после закрытия экземпляра новый `LocalSkillRepository(root, host)` читает `Summarize 2.0.0`, выполняет откат, затем ещё один экземпляр читает `Summarize 1.0.0`. Хранилище не получает сетевой транспорт: `activeInstructions` вызывает только проверку локального каталога и чтение файла. `quarantinedMetadataAndLocalSearchSurviveRestart` отдельно проверяет локальный каталог/поиск после открытия и сохранение MIT/источника/версии. Это доказательство локального пути чтения; системный сетевой интерфейс в тесте не отключался. |
| Восстановление из резервной копии проверено | `backupRestoresExactFilesReviewsQuarantineAndBothVersionMaps`: копия содержит 1.0.0, активную 2.0.0 и карантинную 3.0.0. Без подтверждения или с неверным SHA-256 восстановление отвергается. С правильным хешем копия восстанавливается в отдельный новый каталог; после открытия доступны три версии, 3.0.0 остаётся в карантине, активная инструкция равна `Summarize 2.0.0`, откат даёт `Summarize 1.0.0`. Прежнее согласие отвергается как устаревшее. `explicitRecoveryRepairsCorruptSnapshotAndPayloadWithoutExposingEmptyLibrary` дополнительно повреждает snapshot.json и SKILL.md: обычные операции блокируются; подтверждённая копия восстанавливает 1.0.0, повреждённые данные изолируются в damaged/. |
| Общий контракт и согласия сохранены | Все 18 методов `SkillPackageContractTest` прошли. В проверке обновления без NETWORK-согласия весь снимок остаётся прежним; с явным согласием 2.0.0 активируется. Неизвестные происхождение или лицензия по отдельности сохраняют карантин после перезапуска. |
| Загрузки не отправляют рабочие данные | Тест сетевых адаптеров сравнивает точный список двух запросов: URL ZIP и GitHub codeload с полным commit SHA. Без подтверждения сети запросов нет. Производственный транспорт формирует только GET, Host, Accept, Accept-Encoding и Connection, без тела, авторизации, cookie и клиентских TLS-ключей; проект/чат/профиль ему не передаются. |

Изображения последнего прогона:

- [Панель, 440 px](../shared/build/reports/local-skills/library-440.png)
- [Панель, 1000 px](../shared/build/reports/local-skills/library-1000.png)

Они сгенерированы реальным Compose-рендером панели и просмотрены исполнителем.
Импорт выполнен через тот же адаптер, который вызывает панель; автоматизация
нажатий по полному пользовательскому сценарию не заявляется.

Команда этого прохода:

`./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 7s**. XML: **48 tests, 0 failures, 0 errors, 0 skipped**:
16 проверок LocalSkillRepositoryTest, 1 проверка панели, 18 общего контракта
и 13 прежних проверок навыков. Каждый названный выше метод присутствует в XML
без failure/error. Desktop-компиляция — UP-TO-DATE. Полный прежний прогон 414
тестов остаётся историческим; после усиления тестовых фикстур повторён целевой набор.
`git diff --check`, сверка XML и проверка ссылок отчёта — PASS.

Изменённые файлы этого продолжения:

- `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillRepositoryTest.kt`
- `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillsPanelTest.kt`
- `docs/SKILLS-LOCAL-STORAGE.md`

Совокупный список этапа по-прежнему содержит 11 файлов, перечисленных выше.
Чужие изменения не редактировались. Ограничения JVM/desktop, GitHub-only Git-импорта,
отсутствия живых сетевых испытаний и подключения нового набора к runtime сохранены.

Для сохранности результатов приёмки выполнен ещё один прогон:

`./gradlew --no-daemon --init-script shared/build/skills-acceptance.init.gradle :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' :desktopApp:compileKotlin`

**BUILD SUCCESSFUL in 9s**, те же 48 тестов без failures/errors/skipped.
Причина повтора: соседняя сборка перезаписала стандартные XML двумя своими тестами;
последующая сверка стандартного каталога обнаружила именно замену отчётов.
Временный init-script в build/ меняет только пути XML/HTML, не тесты и не кеш.
Устойчивые артефакты этого прогона:

- [HTML-отчёт](../shared/build/reports/tests/skills-storage-acceptance/index.html)
- [XML хранилища: 16 сценариев](../shared/build/test-results/skills-storage-acceptance/TEST-io.aequicor.magicpaper.data.skills.LocalSkillRepositoryTest.xml)
- [XML общего контракта: 18 сценариев](../shared/build/test-results/skills-storage-acceptance/TEST-io.aequicor.magicpaper.data.skills.SkillPackageContractTest.xml)
- [XML панели](../shared/build/test-results/skills-storage-acceptance/TEST-io.aequicor.magicpaper.data.skills.LocalSkillsPanelTest.xml)

Сверка именно этого отдельного каталога: 7 suites, 48 tests, 0 failures,
0 errors, 0 skipped; все восемь названных ключевых сценариев присутствуют
и успешны. Скрипт и отчёты — воспроизводимые build-артефакты, не новые исходники.
