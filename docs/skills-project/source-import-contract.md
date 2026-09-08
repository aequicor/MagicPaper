# Единый contract источников SKILLS

Этап 2, 2026-09-08. Этот contract — единственная граница между внешним
источником и `LocalSkillRepository`; он не заменяет review, activation или
project pin.

## Нормализация

`SkillPackageImporter` приводит каталог, ZIP, фиксированную GitHub URL,
HTTPS-пакет и готовый `SKILL.md` к `ValidatedSkillImport`. Его обязательные
поля — один `ValidatedSkillPackage`, наблюдаемый `SkillObservedSource` и
защищённая копия точных package bytes. `ValidatedSkillPackage.checksum` —
SHA-256 точных байтов `skill-package.json`; поэтому его manifest и все
payload bytes уже проверены `SkillPackageValidator` до возврата объекта.

`SkillImportKind.READY_TEXT` — только канал происхождения. Готовый текст
получает наблюдаемый адрес `inline:sha256=<SHA-256 SKILL.md>`; сам текст в
адрес, лог или сетевой запрос не попадает. Его package origin, id, version,
совместимость и описание обязан явно задать editor. Это позволяет следующему
этапу использовать `prepareSkillMarkdown` без нового хранилища или обхода
валидатора.

Manifest `origin` остаётся заявлением автора. `SkillObservedSource` хранит
фактически наблюдённый канал, URL/локальный путь и immutable revision GitHub.
Оба сохраняются: manifest вместе с exact bytes в release, observed source — в
локальном snapshot. ZIP и каталог сохраняют распакованные точные package
files, а не повторно сериализованный ZIP.

## Единственная запись

Только `LocalSkillRepository.install(ValidatedSkillImport)` коммитит
подготовленный пакет. Он повторно сверяет bytes с checksum и manifest перед
атомарным сохранением. Результат всегда `QUARANTINED`; он не может:

- подтвердить review;
- активировать release;
- подключить его к project pins;
- передать текст агенту или выполнить payload.

Ключ release — `id@version`, а версия immutable: иной checksum с тем же ключом
отклоняется. Равные exact bytes имеют один checksum и один каталог release;
повторный импорт не дублирует bytes. Более новая version добавляет новый
release и никогда не переписывает существующие project pins.

Старый overload `install(entries, source, ...)` оставлен только для внутренних
programmatic producers (например, кандидатов опыта). Новые пользовательские
источники должны сначала получить `ValidatedSkillImport`.
