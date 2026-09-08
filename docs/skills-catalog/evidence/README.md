# Адресная приёмка каталога после уточнения критериев

## Что реально запущено

`SkillCatalogLiveUiTest.searchAndEnteredLinkThroughProjectCatalog` запускает
production ProjectSkillsPanel → SkillCatalogPanel в ImageComposeScene, выполняет
настоящие semantics OnClick/SetText и анонимные загрузки GitHub. Ни catalogFactory,
ни fetch, ни callbacks импорта в этом тесте не подменены. Это работающий Compose UI
headless, **не ручное desktop-окно**. Визуальная приёмка окна остаётся следующему этапу.

[ui-actions.txt](ui-actions.txt) — журнал успешного последнего запуска:
1. «Добавить скилы из репозиториев» → ввод debugging в поиск → «Выбрать источник»
   → согласие на GET → выбор skills/systematic-debugging/SKILL.md → импорт.
   Хранилище содержит ровно один пакет.
2. Повторный выбор того же SKILL.md и кнопка импорта: весь snapshot равен прежнему,
   включая generation, installed, проекты и согласия; пакетов по-прежнему один.
3. В production поле ссылки введено `https://github.com/obra/superpowers/tree/v4.0.0`
   → отдельное согласие → выбор skills/verification-before-completion/SKILL.md
   → импорт. Пакетов ровно два. Ref разрешён реальным GitHub API.
4. Возврат в проект; затем закрытие LocalSkillRepository и открытие нового
   экземпляра без передачи транспорта: каждый из двух поисков возвращает одну
   запись, точные пары key/checksum совпадают, обе инструкции читаются.

[imports.txt](imports.txt) — фактически сохранённые ключи, checksum, источники,
полные SHA, карантин и UNKNOWN-BLOCKED-UNTIL-REVIEW. Тест отдельно утверждает
source.location, полный SHA `95c6e1633630a1462679cf1284b95ec458a27a8e`,
64-hex checksum, null license, QUARANTINED, отсутствие review и пустые
active/projects/projectTextConsents **до и после повторного открытия**.
Неизвестная лицензия не была заменена предположением MIT.

## Отдельные защитные сценарии — фикстуры, не живые импорты

| Требование | Выполненный тест и конкретное утверждение |
|---|---|
| Review не означает coding opt-in | GithubSkillCatalogTest.selectionDedupeQuarantineReviewAndOfflineRestart: review точного checksum, отдельный bindProject, trustedText=false, повторное открытие сохраняет запрет |
| Новый checksum требует нового review и согласия | GithubSkillCatalogTest.newChecksumKeepsProjectPinAndRequiresNewReviewAndOptIn: импорт версии с новым SHA/текстом сохраняет старый pin; bind нового checksum до review отвергнут; после нового review и bind trustedText=false |
| Diff виден до обновления | SkillCatalogUpdateEvidenceTest.updateDisplaysOldAndNewChecksumsInstructionsAndResourceDiffBeforeImport: semantics содержит «До/После», оба checksum, OLD/NEW INSTRUCTION, added.txt/removed.txt/changed.txt; snapshot до импорта неизменен; UI импорт не меняет pins/consent старой версии |
| Сбой/отказ сети | GithubSkillCatalogTest.failureAndCancellationNeverInstallAndUnapprovedRequestsAreNotSent: неверный URL/нет согласия дают ноль fetch, исключение fetch передаётся как ошибка; отменённая до старта установка сохраняет snapshot |
| Отмена уже начатой загрузки | GithubSkillCatalogTest.cancellationDuringBlockingFetchDiscardsLateDownload: fetch заблокирован latch, job отменён, поздний ответ не публикуется и не достигает импорта |
| Ошибка транзакции/повреждённые байты отката | ProjectSkillRollbackUiTest: удаление целевого SKILL.md после preview отклоняет commit без изменения snapshot; устаревший generation также отвергается; после восстановления и нового согласия A откатывается, B неизменен |
| Отдельный opt-in при откате | ProjectSkillRollbackUiTest: после отката и reopen trustedText=false; A→B→A сохраняет точные pins; старый run selection неизменен |

Сбой питания и ручное закрытие desktop-процесса не моделировались. Сетевой socket
может завершиться по таймауту; проверяется отбрасывание результата, не мгновенное
физическое прерывание TCP. Подробное доказательство байтов HTTP без рабочих данных:
последний раздел [основного отчёта](../../SKILLS-CATALOG.md).

## Последний прогон

```sh
MAGICPAPER_SKILL_CATALOG_LIVE=true ./gradlew --no-daemon :shared:jvmTest --tests 'io.aequicor.magicpaper.data.skills.*' --tests '*ProjectsPanel*Test' --tests '*CodingChatScrollTest' --tests '*ChatScrollToBottomTest' --tests '*CodingToolPreview*Test' --tests '*CodingComposerRenderTest' --tests '*RequestPinsRenderTest' --tests '*CodexCodingPermissionsTest' --tests '*CodingDraftUpdatesTest' --tests '*ActiveCodingChatRenderTest' --tests '*AgentUiThreadTest' --tests '*CodingRunRecorderTest' --tests '*CodingResumeTest' :desktopApp:compileKotlin
git diff --check
git diff --cached --check
```

BUILD SUCCESSFUL, **161 tests, 0 failures/errors/skipped**. Desktop UP-TO-DATE.
[acceptance.json](acceptance.json) содержит XML-сводку и имена адресных тестов.
Лог/XML и before/after SHA-256: `.gradle/skills-catalog-acceptance/`.
Все shared/src совпадают до/после. Обе проверки diff — PASS.
Перед прогоном обнаружен Test Executor с workdir другого дерева
`/private/tmp/MagicPaper-orchestrator-confirmation-status`; он не останавливался
и его файлы не читались. Конкурирующий executor текущего рабочего дерева не найден.
Исторические 93/15 и 155/86 не переименованы в PASS; первопричина не доказана.

В этом уточнении изменён только SkillCatalogLiveUiTest (журнал и дополнительные
assertions), добавлены эти доказательства и ссылка в основном отчёте. Production
и соседние изменения не менялись. Окно освобождено; новая production-интеграция
или повторное разрешение ms_project_skills для завершения не требуются.
