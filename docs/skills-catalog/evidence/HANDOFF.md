# Четыре уточнения приёмки: конкретные доказательства

Это дополнение к [общей матрице](README.md), не повторная реализация.
Новый прогон текущего дерева: **161 tests, 0 failures/errors/skipped**,
BUILD SUCCESSFUL; desktop compileKotlin UP-TO-DATE. Сводка по конкретным
классам и именам тестов — [handoff.json](handoff.json).

## 1. После review пакет действительно подключается к проекту

`GithubSkillCatalogTest.selectionDedupeQuarantineReviewAndOfflineRestart`:
импортированный пакет сначала QUARANTINED. Затем выполняются реальные
`repo.review(key, SkillPackageReview(checksum, ... true, true, true))` и
`repo.bindProject("A", mapOf(key to checksum), SkillActivationConsent(...))`.
Bind завершается успешно, `projectCodingSelection("A").trustedText == false`.
После закрытия/повторного открытия source SHA, checksum и текст инструкции
совпадают; trustedText остаётся false. Это положительная проверка подключения,
не только проверка запрета. Review здесь обоснован синтетическим evidence;
не утверждается, что реальные Superpowers автоматически прошли review.

`newChecksumKeepsProjectPinAndRequiresNewReviewAndOptIn` дополнительно:
новая версия установлена, но проект читает старый checksum; bind нового checksum
до review бросает IllegalArgumentException. Новый review и новое согласие
подключают новую версию, не наследуя opt-in старой.

## 2. UI-откат восстанавливает именно проектную версию

`ProjectSkillRollbackUiTest.explicitRollbackCancellationStaleAndUnavailableReleaseThenRestartAndProjectRoundTrip`:
A сначала имеет test.rollback@1.0.0 (NETWORK), затем @2.0.0, B — @2.0.0.
Production ProjectSkillsPanel показывает preview. Semantics-действия подтверждают
просмотр checksum и отдельно NETWORK, затем вызывают «Подтвердить откат без opt-in».
После успешного commit тест **сравнивает полные maps**:
`projects[A] == mapOf(v1.key to v1.checksum)` и
`projects[B] == mapOf(v2.key to v2.checksum)`; trustedText(A)=false.
UI переключается A→B→A, затем хранилище закрывается и открывается заново:
инструкция A имеет checksum v1, B — v2, previousProjects[A] содержит v2 для redo.
Старый ранее полученный run selection остаётся v2. Недоступный файл и stale
preview до этого отклонены с неизменным snapshot. Это не глобальный previousActive
и не имитация отката через bindProject: production UI вызывает rollbackProject.

## 3. Загрузки не отправляют рабочие данные

`SkillDownloadPrivacyTest.completeOutboundPayloadHasOnlyPublicPathAndFixedHeadersEvenWithAmbientCredentials`:
четыре локальных synthetic canary — PRIVATE_PROJECT_CODE, PRIVATE_CHAT,
PRIVATE_EXPERIENCE, PRIVATE_LLM_KEY — отсутствуют в полном requestBytes.
Проверяется точное равенство всего HTTP plaintext, непосредственно передаваемого
production socket.outputStream.write: GET публичного API/ZIP пути, пять
HTTP-заголовков (включая Host) и их окончание; тело строго пустое.
Нет Authorization/Cookie/Referer/Content-Length/Proxy-Authorization;
установленные тестовые CookieHandler/Authenticator не вызваны (0).

[outbound-http.txt](outbound-http.txt) — полные проверенные запросы API и ZIP;
[assertions.txt](assertions.txt) — результат. Второй тест отвергает userinfo,
query, fragment, HTTP и неподтверждённый origin до сериализации.
Это byte-exact unit test используемого транспортом payload, не packet capture.
Сам транспорт получает лишь URL, не project/chat/experience/key repositories;
каталог строит URL из публичного repository/ref/SHA. IP, hostname и публичный
путь сервер видит. Произвольное содержимое чужого ответа и последующие coding
LLM-запросы этим доказательством не охватываются.

## 4. Общие изменения согласованы и перепроверены

Подтверждения, полученные через координатора, а не выведенные из наличия кода:
- `mtsgor55-e2dec8eb-turn-0-action-1`: каталог/новые JVM компоненты и вход в
  ProjectSkillsPanel переданы исполнителю каталога; постоянный rollback принадлежит
  ms_project_skills, запрещено повторно реализовывать хранилище.
- `mtsgor55-e2dec8eb-turn-1-action-0`: **передача API подтверждена**, разрешена
  интеграция previousProjects/rollbackProject в ProjectSkillsPanel с новым
  согласием и без opt-in; контракт docs/skills-project/project-rollback.md.
- `mts6ojgn-1a774adf-turn-12-action-0` и `turn-13-action-0`: ms_project_skills
  явно освободил диагностическое окно, разрешил завершающий последовательный
  прогон. Его независимая проверка UI-интеграции записана в
  docs/skills-project/final-matrix.md, раздел «Проектный откат после интеграции».

SkillReleaseStore/LocalSkillRepository/Dependencies.jvm исполнителем каталога
не переписывались. Чужой opt-in в ProjectSkillsPanel сохранён. Правки
SkillPublicDownload ограничены фиксированными заголовками и выделением прежнего
payload для byte-exact теста; политики HTTPS/DNS/ZIP не ослаблены.
Перед новым прогоном сверены staged/unstaged diff и source SHA-256. После
предыдущего снимка изменился 21 соседний исходник, поэтому старый PASS не был
выдан за текущий: заново выполнена полная команда из README.md с live-флагом,
skills, проектным/coding UI, recorder/resume и desktop-компиляцией.

Wrapper/Test Executor перед этим прогоном отсутствовали. Сохранены
`.gradle/skills-catalog-handoff/{before.json,after.json,regression.log,xml/}`.
Во время прогона shared/src не изменился, обе проверки git diff — PASS.
Исторические FAIL 93/15 и 155/86 сохранены отдельно; первопричина не установлена.
Ни один исходник в этом уточнении не изменялся, только доказательства/документы.
Окно освобождено. Дополнительная адресная перепроверка ms_project_skills нужна
при новых общих изменениях, а не для повторного разрешения уже проверенного API.

Ограничения прежние: live GitHub и production Compose semantics выполнены;
ручное desktop-окно и live Pi/Codex не проверялись. Приёмка следующего визуального
этапа не подменяется словом PASS этой тестовой выборки.
