# Сквозная приёмка lifecycle SKILLS

Дата проверки: 2026-09-08. Проверено актуальное незакоммиченное дерево без
отката, индексации или публикации чужих изменений. Перед запуском перечитаны
`SkillAdapterWireIntegrationTest.kt`, `adapter-blockers.md`, контракты импорта,
опыта, кандидатов, доверенного текста и пересекающиеся runtime/repository-файлы.

## Итог

Локальная hermetic-приёмка и принудительная desktop-компиляция — **PASS**.
Профиль содержит **207 тестов: 205 passed, 2 skipped, 0 failures, 0 errors**.
Оба пропуска — явно gated live-GitHub сценарии; они не засчитаны как PASS.

## Матрица lifecycle

| Область | Проверенное поведение | Статус |
| --- | --- | --- |
| Каталог и источники | Локальный каталог, directory, ZIP, HTTPS/package и pinned GitHub URL проходят единый `ValidatedSkillImport`; сетевые запросы в hermetic-тестах используют фиксированный transport, проверяются URL, SHA и отсутствие ambient credentials | PASS (hermetic) |
| Готовый текст | Preview содержит package metadata, observed `READY_TEXT` source, exact entries и checksum; отмена не пишет release; запись возможна только отдельным `install`; результат только `QUARANTINED`, без review/activation/project bind; одинаковые bytes дедуплицируются; новая версия не меняет существующий pin; invalid YAML frontmatter и невалидная UTF-8 строка отвергаются до release | PASS |
| Quarantine → review → consent → activation/bind | Нельзя активировать или подключить непроверенный release; review и согласия привязаны к точным checksum/generation; новые permissions требуют отдельного согласия; импорт и генерация не выполняют эти шаги автоматически | PASS |
| Project pins и rollback | Pins переживают reopen, изолированы между проектами и не меняются при импорте обновления; rollback атомарен, требует свежего preview/consent, восстанавливает точный состав, но не восстанавливает trusted-text opt-in | PASS |
| Выполнение Pi/Codex | Реальные runtime-пути доведены до дочерних wire-фикстур: trusted pin передаёт exact id/version/checksum/text через Pi stdin и Codex `turn/start`; используется fresh start, quarantined/unbound/disabled release исключён; пустой состав корректно resume-ит без SKILLS payload | PASS (hermetic wire) |
| Run experience | Один UUID идемпотентен при replay, конкуренции и reopen; `SUCCESS`, `FAILURE`, `CANCELLATION`, `UNKNOWN` различаются; только независимый `PASSED` превращает завершённый run в положительный опыт; `FAILED` и `UNAVAILABLE` fail closed | PASS |
| Автоматический кандидат | Нужны три разных UUID с одинаковым scenario/features и только `PASSED`/`USER_CONFIRMED`; replay, UNKNOWN/UNAVAILABLE/FAILURE/CANCELLATION и другая сигнатура не учитываются; до preview и явного generate-consent нет LLM-вызова или записи; кандидат остаётся в карантине и требует review плюс отдельную activation/bind-процедуру | PASS |
| Дедупликация и конкуренция | Exact package bytes дедуплицируются; использованные evidence не переиспользуются после reopen; конкурентная генерация создаёт один кандидат и устаревший token отвергается | PASS |
| Сбои и восстановление | Покрыты повреждённые snapshot/payload/pin, несовпадающий checksum/version, stale consent, прерванный commit/evaluation, отказ transport/provider/verifier, timeout, cancellation, late callback после удаления, retention fence, backup/restore и reopen | PASS |
| Приватность опыта | Chat/session/prompt/output, tool/file paths, project names, ошибки, secrets и profile key не записываются в experience и не отправляются в generation; GitHub download не наследует credentials/query/fragment | PASS |

Штатный production `SkillRunVerifier` возвращает `UNAVAILABLE`. Проверено, что
это сохраняется как `UNKNOWN` без scenario/features и не создаёт положительный
опыт или предложение. Положительный автокандидат возможен только при внедрённом
прикладном verifier с `PASSED` либо по явно подтверждённым пользователем данным.

## PASS

Запущена текущая реализация, без переноса исторических XML:

```sh
./gradlew --no-daemon :shared:jvmTest --rerun \
  --tests 'io.aequicor.magicpaper.data.skills.*' \
  --tests '*ProjectsPanel*Test' \
  --tests '*CodingChatScrollTest' \
  --tests '*CodingComposerRenderTest' \
  --tests '*RequestPinsRenderTest' \
  --tests '*ActiveCodingChatRenderTest' \
  --tests '*AgentUiThreadTest' \
  --tests '*CodexCodingPermissionsTest' \
  --tests '*CodingDraftUpdatesTest' \
  --tests '*PlanRunnerTest' \
  --tests '*CodingRunRecorderTest' \
  --tests '*PlanningExecutionServiceTest' \
  :desktopApp:compileKotlin --console=plain
```

Результат: `BUILD SUCCESSFUL in 1m`; 35 JUnit suites, 207 tests, 205 passed,
2 skipped, 0 failures/errors. `SkillAdapterWireIntegrationTest` действительно
выполнил оба теста (2 tests, пустой `system-out`, без ветки `NOT_RUN`).

Desktop отдельно перекомпилирован без опоры на `UP-TO-DATE`:

```sh
./gradlew --no-daemon :desktopApp:compileKotlin --rerun-tasks --console=plain
```

Результат: `BUILD SUCCESSFUL in 16s`; 13 actionable tasks, 13 executed,
включая `:shared:compileKotlinJvm` и `:desktopApp:compileKotlin`. Есть только
существующие compiler warnings, ошибок нет. Использованы установленный toolchain
и обычный Gradle cache. Первый sandbox-запуск остановился на локальном служебном
socket; штатный повтор с разрешением успешен.

## Skipped

Ровно два теста пропущены JUnit assumption с сообщением
`Set MAGICPAPER_SKILL_CATALOG_LIVE=true`:

- `GithubSkillCatalogLiveTest.liveSearchAndTypedLinkImport`;
- `SkillCatalogLiveUiTest.searchAndEnteredLinkThroughProjectCatalog`.

Это только реальные сетевые GitHub search/link и production Compose UI с
реальным GitHub. Их hermetic аналоги и политика URL/download прошли.
Служебные Gradle-задачи со статусом `SKIPPED` не являются пропущенными тестами.

## NOT_RUN

- реальные внешние GitHub API/download и live UI-сценарий по ссылке;
- установленные Pi CLI и Codex app-server с реальным backend/model/account;
- ручной click-through запущенного desktop-приложения;
- penetration-проверка filesystem/network/process policy и проверка памяти
  реального backend после смены состава.

## BLOCKED внешним контрактом

- независимое OS-enforcement, запрещающее агенту исполнить команду из доверенного
  текста при сохранении обычных coding-инструментов;
- доказуемое удаление уже переданных инструкций из backend history. Fresh
  Pi/Codex start на следующем запуске проверен на wire-границе, но это не API
  очистки памяти backend и не удаляет изменения файлов прошлого запуска.

Эти ограничения не маскируются локальными PASS и не ослабляют fail-closed
поведение quarantine/review/consent/verifier.

## Проверки diff

После добавления этого отчёта повторены:

```sh
git diff --check
git diff --cached --check
```

Обе команды завершились без вывода: **PASS**.
