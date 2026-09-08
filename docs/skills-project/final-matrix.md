# Итоговая матрица проектных SKILLS

Актуальный снимок: 2026-09-08. Это эксплуатационная сверка реализации и
приёмки; соседние исходные изменения не откатывались и внешних публикаций не
было. Основной контракт: [SKILLS-OPERATIONS](../SKILLS-OPERATIONS.md).

Свежая проверка завершающего коммита и исправлений восстановления/импорта:
[финальная интеграция](integration-final.md). Числа адресного прогона ниже
сохранены как история предыдущего этапа.

## Матрица

| Область | Фактически подтверждено | Статус |
|---|---|---|
| Источники импорта | directory, ZIP, HTTPS, pinned GitHub и `READY_TEXT` проходят единый importer/validator; exact manifest/payload checksum, лимиты, URL/privacy guards | PASS (hermetic) |
| Quarantine и review | Импорт только `QUARANTINED`; review происхождения, лицензии и содержимого не наследуется из пакета/backup | PASS |
| Consent, activation, bind | Exact checksum + generation, полный dependency graph, diff и отдельное согласие новых permissions; quarantine не активируется | PASS |
| Project pins | Изоляция проектов, restart/reopen, immutable run snapshot, обновление не меняет pin | PASS |
| Rollback | Атомарный exact rollback состава, свежий preview/consent, trusted-text opt-in сбрасывается | PASS |
| Trusted text delivery | Pi/Codex wire-фикстуры получают exact id/version/checksum/text только доверенных pins; start/resume и empty composition проверены | PASS (hermetic wire) |
| Automatic learning | Три разных UUID с одинаковыми scenario/features и PASSED/USER_CONFIRMED; кандидат quarantine до review и activation/bind | PASS |
| Run experience | UUID, replay/concurrency/reopen deduplication; SUCCESS/FAILURE/CANCELLATION/UNKNOWN разделены; UNAVAILABLE → UNKNOWN | PASS |
| Deletion and backup | Tombstone/retention, late-callback fence, exact backup/restore, damaged isolation, pin/review/quarantine preservation | PASS (local) |
| Privacy | Experience не содержит chat/prompt/output/files/projects/secrets/session IDs; imports не наследуют credentials | PASS |

## Границы статусов

### PASS

```text
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

`BUILD SUCCESSFUL`: 207 тестов, 205 passed, 0 failures/errors; desktop compile
успешен. Принудительная проверка:

```text
./gradlew --no-daemon :desktopApp:compileKotlin --rerun-tasks --console=plain
```

`BUILD SUCCESSFUL`, 13/13 задач выполнены. `git diff --check` и
`git diff --cached --check` — PASS.

### SKIPPED

Ровно два gated live-GitHub теста пропущены по assumption
`Set MAGICPAPER_SKILL_CATALOG_LIVE=true`:

- `GithubSkillCatalogLiveTest.liveSearchAndTypedLinkImport`;
- `SkillCatalogLiveUiTest.searchAndEnteredLinkThroughProjectCatalog`.

Их hermetic аналоги прошли; пропуск не считается успешным live-тестом.

### NOT_RUN

- реальные GitHub API/download;
- установленные Pi CLI и Codex app-server с backend/model/account;
- ручной click-through desktop UI;
- проверка памяти реального backend после смены состава.

### BLOCKED внешним контрактом

- независимое OS-enforcement файловой/сетевой/процессной политики, сохраняющее
  обычные coding-инструменты;
- доказуемая очистка уже переданных инструкций из backend history.

Fresh-session и пустой payload на hermetic wire-границе не являются API очистки
backend и не удаляют изменения файлов прошлого запуска. Эти ограничения не
маскируются локальными PASS.

## Связанные документы

- [импорт источников](source-import-contract.md)
- [жизненный цикл](lifecycle-acceptance.md)
- [автоматические кандидаты](automatic-candidates.md)
- [run-experience](run-experience.md)
- [trusted text](trusted-text.md)
- [rollback](project-rollback.md)
- [adapter blockers](adapter-blockers.md)
