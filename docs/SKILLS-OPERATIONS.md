# Навыки: эксплуатационный контракт

Актуальный desktop/JVM-контракт на 2026-09-08. Сводная приёмка и команды
проверки находятся в [итоговой матрице](skills-project/final-matrix.md).
Проверка состава завершающего коммита: [финальная интеграция](skills-project/integration-final.md).
Пакетные разрешения декларативны: они не являются отдельной OS-ACL.

## Импорт

Все источники проходят один `SkillPackageImporter` и записываются только через
`LocalSkillRepository.install(ValidatedSkillImport)`: локальный каталог,
ZIP, публичная GitHub HTTPS-ссылка на полный commit SHA, HTTPS ZIP с manifest
SHA-256 и готовый текст. Готовый текст получает наблюдаемый источник
`inline:sha256=...`; metadata пакета (id, version, name, description,
compatibility) задаётся явно. Raw text напрямую в adapter не попадает.

Импорт проверяет manifest, payload bytes, пути, типы, дубликаты, UTF-8,
checksum и лимиты. ZIP/загрузка ограничены 5 MiB, payload — 20 MiB,
manifest — 256 KiB, ресурсов — 512. Symlink, traversal, специальные файлы,
Git hooks/submodules/LFS и неразрешённые адреса отклоняются. Сеть требует
явного согласия; credentials, query/fragment и рабочие данные не передаются.
Каждый успешный импорт становится только `QUARANTINED`: он не review, не
active, не project pin и не выполняет код.
Готовый пакет с собственным manifest сохраняет исходный состав и checksum;
общая лицензия GitHub-репозитория показывается в preview без добавления
незаявленных файлов в такой пакет.

Подробности: [контракт пакета](SKILLS-CONTRACT.md), [единый контракт источников](skills-project/source-import-contract.md), [локальное хранилище](SKILLS-LOCAL-STORAGE.md).

## Review, activation и project bind

Жизненный цикл: `import → QUARANTINED → review → preview → consent → activation/project bind`.
Review привязан к exact checksum и не импортируется из manifest, каталога или
backup. Activation/bind проверяют полный граф точных зависимостей, сохранность
байтов, baseline/улучшение и новые permissions. Для новых permissions нужно
отдельное согласие. Stale generation, checksum или preview закрывает commit.
Пины хранятся отдельно для каждого проекта, переживают reopen, а обновление
библиотеки их не меняет. Откат атомарен, требует нового preview/consent и
сбрасывает trusted-text opt-in.

## Trusted text и адаптеры

Для подключённого состава пользователь отдельно включает trusted text. В
пользовательский prompt Pi/Codex передаются только точные
`id/version/checksum/text/declaredPermissions` доверенных pins. Quarantined,
unbound и disabled releases исключаются; изменение состава запускает fresh
engine session. При пустом составе resume сохраняет обычный маршрут без SKILLS.
Application receipt означает подготовку/передачу аргументов адаптеру, но не
приём текста моделью и не успех задачи.

Приложение не устанавливает расширения и не запускает установщики пакета.
Pi shell и Codex workspace-write работают с обычной политикой backend; команды
из доверенного текста могут быть выполнены агентом. Независимое OS-enforcement
не заявляется.

## Run-experience и обучение

Один run имеет стабильный UUID; completion идемпотентен при replay, конкуренции
и reopen. Повторное сохранение идентичного audit-снимка разрешено без перезаписи;
смена состава или маршрута под прежним UUID отклоняется. Результаты различаются как `SUCCESS`, `FAILURE`, `CANCELLATION` и
`UNKNOWN`. Штатный verifier возвращает `UNAVAILABLE`, что сохраняется как
`UNKNOWN` без scenario/features и не даёт положительного опыта.

Кандидат предлагается только после трёх разных UUID с одинаковыми
scenario/features и независимым `PASSED` либо `USER_CONFIRMED`. `UNKNOWN`,
`UNAVAILABLE`, failure, cancellation, replay и непроверенный успех не считаются.
После явного preview и generate-consent кандидат проходит семь сравнительных
проверок, остаётся quarantine и требует отдельного review и activation/bind.
В experience не попадают chats, prompts, outputs, пути файлов, project names,
secrets или session IDs.

## Backup и удаление

Package backup включает exact package files, review, quarantine, active/previous
maps, project pins и rollback state; требует явного подтверждения и SHA-256.
Restore повторно валидирует данные, повышает generation и сбрасывает
trusted-text consents. `coding-runs` и backend history в package backup не
входят. Локальное удаление опыта использует tombstone/retention, очищает
производные записи и блокирует поздний callback даже после restart. Чаты,
внешние backup, provider copies, изменения файлов и backend history этим не
удаляются; доказуемая очистка backend history — BLOCKED внешним контрактом.

## Статусы доказательств

- **PASS:** полный JVM-прогон — 792 теста, 0 failures/errors, 2 skipped;
  после последней правки импорта — 162 теста затронутого контура,
  0 failures/errors, 2 skipped. Desktop compile — BUILD SUCCESSFUL.
  Точный порядок проверок указан в [финальной интеграции](skills-project/integration-final.md).
- **SKIPPED:** два gated live-GitHub теста, требующие
  `MAGICPAPER_SKILL_CATALOG_LIVE=true`; это не PASS.
- **NOT_RUN:** реальные GitHub API/download, Pi/Codex backend/model/account,
  ручной desktop click-through и backend memory verification.
- **BLOCKED:** независимое OS-enforcement и доказуемое удаление уже переданных
  инструкций из backend history.

PASS не расширяется до NOT_RUN или BLOCKED.
