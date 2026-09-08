# Завершение интеграции SKILLS

Дата: 2026-09-08. Проверен отдельный снимок коммита поверх `722b98b`.
Изменения соседних задач по read-only planning, прокрутке и хранению планов
не включены. В общих runtime/domain-файлах выделены только изменения SKILLS.

## Что завершено

- Каталог публичных GitHub-источников и единый импорт directory/ZIP/HTTPS/GitHub/
  готового SKILL.md через валидацию, quarantine и отдельный review.
- Проектные версии, точное согласие на trusted text, откат и восстановление
  без автоматического изменения pins или повторного включения доверенного текста.
- Передача точного состава в Pi/Codex и локальный аудит; идентичный повтор
  audit-записи теперь допускается без перезаписи. Изменённый состав или маршрут
  под прежним ID отклоняется. Это устраняет отказ восстановления checkpoint
  до обращения к движку, сохраняя неизменяемость исходного снимка.
- Готовый GitHub-пакет с собственным manifest сохраняет исходные bytes/checksum:
  общая лицензия репозитория остаётся evidence в preview и не добавляется
  в payload как незаявленный файл.
- Стабильные UUID coding/planning-запусков, четыре исхода локального опыта,
  дедупликация и удаление с блокировкой поздних callback; автоматические
  предложения кандидатов по подтверждённым повторяющимся признакам.

## Проверки

Полный JVM-прогон из отдельного снимка после исправления восстановления:

```sh
./gradlew :shared:jvmTest :desktopApp:compileKotlin --console=plain
```

**BUILD SUCCESSFUL: 144 suites, 792 tests, 0 failures/errors, 2 skipped.**
Все 18 задач выполнены, включая компиляцию desktop.

После последней правки GitHub-import и её нового регрессионного теста
перепроверен затронутый контур в окончательном составе коммита:

```sh
./gradlew :shared:jvmTest \
  --tests 'io.aequicor.magicpaper.data.skills.*' \
  --tests '*PlanRunnerTest' \
  --tests '*PlanningExecutionServiceTest' \
  :desktopApp:compileKotlin --console=plain
```

**BUILD SUCCESSFUL: 25 suites, 162 tests, 0 failures/errors, 2 skipped.**
Три теста `SkillAdapterWireIntegrationTest` выполнены без пропусков, включая
восстановление checkpoint после переоткрытия хранилища через оба транспорта.
`GithubSkillCatalogTest` — 5 тестов без пропусков; новый тест проверяет импорт
авторского manifest вместе с внешней лицензией без изменения checksum.
Отсутствие Node теперь отражается assumption-пропуском wire-тестов,
а не успешным ранним return. `git diff --check` и проверка коммитного diff — PASS.

Два пропуска — `GithubSkillCatalogLiveTest` и `SkillCatalogLiveUiTest`:
`MAGICPAPER_SKILL_CATALOG_LIVE` не включался. Wire-проверки используют локальные
дочерние процессы-фикстуры. Реальные Pi/Codex model/account, сетевой GitHub
и ручной desktop click-through в этом завершении не запускались.

Штатная независимая проверка произвольной coding-задачи остаётся `UNAVAILABLE`:
непроверенные завершения записываются как `UNKNOWN`, не создавая положительный
опыт. Отдельная OS-ACL пакета и удаление инструкций из истории реального backend
не заявляются. Подробный контракт: [SKILLS-OPERATIONS](../SKILLS-OPERATIONS.md).
