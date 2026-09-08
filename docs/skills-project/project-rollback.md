# Постоянный проектный откат: контракт для ms_repository_live

**UI-интеграция получена и перепроверена.** [Актуальная матрица](final-matrix.md): 158 tests, 0 failures/errors, 2 skipped; BUILD SUCCESSFUL. ProjectSkillRollbackUiTest и оба repository rollback-теста — PASS. Упоминания PENDING ниже относятся к предыдущим снимкам до интеграции, не текущему результату. Панель и DI проверяющий исполнитель не менял.

## API

- `SkillReleaseSnapshot.previousProjects: Map<String, Map<String, String>>` — один предыдущий состав на проект. Внутри: `id@version -> checksum`. Отсутствие ключа означает отсутствие отката; пустой состав означает откат к отключению всех скилов. Не использовать `previousActive`: это отдельный глобальный механизм.
- `suspend LocalSkillRepository.rollbackProject(projectId: String, consent: SkillActivationConsent): SkillReleaseSnapshot` — публичный durable API. Аналогичный метод есть у `SkillReleaseStore`.
- Preview получает `snapshot()` один раз, берёт `previousProjects[projectId]` и generation. Подтверждение передаёт именно этот targetChecksums, `reviewedChanges=true`, явно подтверждённые добавляемые permissions. Устаревший generation, другой checksum или отсутствие подтверждения отклоняются без commit.
- После изменения состава `bindProject` сохраняет предыдущий состав. Изменение только text-consent не уничтожает историю. Откат меняет местами текущий и предыдущий состав (одноуровневый undo/redo), касается только заданного проекта и требует нового согласия каждый раз.
- Откат **всегда сбрасывает text opt-in**, даже если вызывающий передал `trustedCodingText=true`. Fresh-session marker ранее доверенного проекта сохраняется; повторное включение доверенного текста — отдельное подтверждение точного состава.
- Повторно проверяются review/quarantine, passed improvement, dependency graph, permissions и checksum. Исторический проверенный improvement допускается при rollback без требования совпадения с текущим baseline, как в глобальном откате. До commit durable API повторно читает/проверяет все файлы целевого пакета; отсутствующие/повреждённые байты запрещают откат.
- `previousProjects` записывается атомарно с projects, generation и consent, копируется в snapshots, сериализуется в backup/restore. Старые snapshots без поля имеют пустую историю. Восстановление backup по-прежнему не восстанавливает доверенный text-consent.
- Явное удаление release через forget удаляет целиком затронутые предыдущие составы (не подменяет их частичными составами). Подключённые версии по-прежнему нельзя удалить без отключения.

## Задание интегратору через координатора

`ProjectSkillsPanel.kt` и `Dependencies.jvm.kt` в этом продолжении НЕ изменялись. Для UI: показать «Откатить состав» при наличии ключа в previousProjects, показать полный target с версиями/checksum и permission diff, запросить новое подтверждение, вызвать rollbackProject. Не вызвать bindProject вместо rollbackProject: проверка historical improvement отличается. Не предлагать одновременно восстановление text opt-in. При исключении оставить текущие привязки и показать причину, обновить preview при stale generation. После интеграции повторить общие skills/UI-тесты и desktop-компиляцию.

## Новые тесты

В `ProjectSkillsTest.kt`:

1. `projectRollbackPersistsIsAtomicAndNeverRestoresTextOptIn`: сохранение 1.0.0 перед переходом на 2.0.0, opt-in без потери истории; исключение перед commit сохраняет snapshot и байты snapshot.json; повторное открытие; неверные/устаревшие/неподтверждённые approvals; успешный откат A при неизменном B; A→B→A; неизменность уже полученного run selection; принудительный сброс opt-in; повторное открытие после успеха и сохранение redo; откат B к пустому составу.
2. `projectRollbackRejectsUnavailableReleaseAndMissingPermissions`: отсутствие истории, неподтверждённый NETWORK, отозванный review и удалённый SKILL.md запрещают откат; последний отказ сохраняет snapshot и файл состояния. После восстановления байтов с новым подходящим согласием откат успешен.

## Проверка текущего дерева

```sh
./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*ChatScrollToBottomTest' --tests '*CodingToolPreview*Test' --tests '*CodingComposerRenderTest' --tests '*RequestPinsRenderTest' --tests '*CodexCodingPermissionsTest' --tests '*CodingDraftUpdatesTest' --tests '*ActiveCodingChatRenderTest' --tests '*AgentUiThreadTest' :desktopApp:compileKotlin
```

BUILD SUCCESSFUL; XML при завершении: 135 tests, 0 failures/errors, 2 skipped. Desktop compileKotlin выполнен успешно. Лог: `shared/build/skills-project-rollback-check.log`. Обе проверки git diff — PASS. Ошибка `ZipFile invalid LOC header` в этом запуске не воспроизведена; исправление ZIP/кэшей не заявляется. Новая интеграция каталога присутствует в соседних файлах текущего дерева, но UI-вызов нового rollback API ещё должен добавить ms_repository_live. Живой Pi/Codex остаётся NOT_RUN.

Изменены в этом продолжении только SkillReleaseStore.kt, LocalSkillRepository.kt, ProjectSkillsTest.kt и этот контракт. Staged/unstaged пересечения просмотрены; чужие изменения STOP/IDLE/DRAFT, каталога, панели и DI сохранены. Результат не подтверждает последующие изменения интегратора.

Повторная передача задания: API сверено с текущими файлами, повторная реализация не выполнялась. Та же команда — BUILD SUCCESSFUL, 135 tests, 0 failures/errors, 2 skipped; оба новых projectRollback-теста отдельно подтверждены в XML как PASS. Desktop compileKotlin UP-TO-DATE. Лог: `shared/build/skills-project-rollback-recheck.log`. Обе проверки git diff — PASS. В этой повторной проверке изменён только данный отчёт. В просмотренном ProjectSkillsPanel по-прежнему нет вызова rollbackProject: UI-приёмка отката остаётся PENDING у ms_repository_live, а не подтверждается тестами store/repository.
