# Унификация сессий — 14 сентября 2026

## Изменение

`feature/chat` и `feature/coding` объединены в `feature/session` (`api`/`impl`).
Все hosts и потребители используют новый Gradle-владелец. Kotlin-пакеты, ключи
истории, сериализуемые идентификаторы и resource paths `coding/*` сохранены.
Новые поля очереди, checkpoint и native ID имеют совместимые значения по умолчанию.

Desktop-чат вызывает тот же `CodingRuntime`, Pi/Codex и recorder, что проектная
сессия. Старый `MagicAgent` заменён HTTP-адаптером `GatewaySessionRuntime` для
платформ без native-процессов. Явный workflow макетов сохранён у `LayoutChatAgent`.
Чат и проектное исполнение используют один `CodingComposer`; старое хранилище чатов
сохранено как совместимый формат, без массового переписывания пользовательской истории.

## Оркестрация и жизненный цикл

- `feature/custom-tools` владеет контрактом, каталогом и проверкой доступа к
  оркестрации. Фильтр native-каталога и проверка вызова требуют `PLANNING`.
  Вспомогательное исполнение не получает полномочий управлять деревом.
- Создание ребёнка поддерживает CODE/RESEARCH/PLANNING, наследуя правила и бюджет.
  CODE требует доступной изоляции рабочего каталога. Существующие имена инструментов
  и fingerprints команд без нового необязательного поля сохранены.
- Удаление через `session.delete` ограничено непосредственным ребёнком и включает
  его потомков. Удаление зиготы включает независимый иммунитет. Сначала фиксируется
  остановка, затем подтверждается завершение, затем сохраняется tombstone и удаляются
  проекции истории. Tombstone препятствует воскрешению при восстановлении.
- Закрепления, черновики и unread-состояние подключены к тому же пути удаления,
  включая вызов инструментом и повтор очистки после сбоя.
- Пауза сохраняет checkpoint; продолжение использует тот же native ID и учитывает
  уже выполненные действия. Уточнение сохраняется до отмены текущего запуска;
  следующий запуск ждёт фактического cleanup. Очередь хранится отдельно и не
  поглощается уточнением. Новое содержимое composer не очищается старым запуском.
- UNKNOWN не означает завершение. Новое поколение требует native-сверки и
  отсутствия нерешённых эффектов инструментов/интеграции. Неизвестный вспомогательный
  запуск продолжает удерживать свои ресурсы.
- Запись в checkout исключается по каноническому пути, а не по ID сессии.
  Изолированные рабочие каталоги остаются независимыми.

## Модели, инструкции и диагностика

Объявленные context/output limits ограничивают effective-настройки, в том числе
варианты модели и резерв reasoning. Отсутствующий лимит не превращается в выдуманное
«1M для всех Qwen». Выбор модели и effort берётся для следующего хода без смены
native conversation ID; завершение предыдущего хода не затирает новый выбор.

Согласованные project skills передаются через общий audited input builder, включая
планировщик; ограничения инструментов режима сохраняются. Политика fresh-session
для доверенных пакетов сохранена. AGENTS.md проверяется на проводе native-адаптеров.

Уровень по умолчанию — INFO. Подготовка/передача skills не создаёт технические
сообщения в каждом ответе; детали доступны в DEBUG, содержимое — только по
отдельной политике TRACE. Ошибки загрузки и аудита имеют владельца и безопасное
уведомление. `shell-timeout.mjs` перенесён с сохранением исправления из upstream:
тайм-аут по умолчанию 300 секунд для bash и PowerShell. Исправлен запуск
provider bridge через символьные ссылки macOS. После объединения с upstream
повторно пройдены все 17 локальных Node-протокольных тестов.

## Проверка

`checkMigrationJvm compileMigrationTargets` завершился **BUILD SUCCESSFUL**:
1666 JVM-тестовых случаев, 0 failures/errors, 3 skips. Отдельно успешно выполнены
8 native-сценариев и `SessionContextRuntimeTest` (9 случаев, без пропусков).
Подробные счётчики и имена проверок: [verification JSON](session-unification-verification.json).

| Проверка | Результат |
| --- | --- |
| Session JVM | 1085 случаев, 0 ошибок, 1 skip |
| App JVM | 98, 0 ошибок |
| Skills JVM | 147, 0 ошибок, 2 skip для live-каталога |
| Paper JVM | 57, 0 ошибок |
| Model / AI / logging JVM | 75 / 76 / 16, 0 ошибок |
| Custom tools JVM | 2, 0 ошибок |
| Node protocol tests | PASS, входят в `checkMigrationJvm` |
| Architecture / Paper / surface map self-tests | PASS |
| Desktop compile, Android assembleDebug, JS/Wasm distributions | PASS |
| Pi/Codex: chat + смена модели + AGENTS | PASS |
| Pi/Codex: planning + skills + AGENTS + запрет записи | PASS |
| Pi/Codex: research + проверка + продолжение + запрет записи | PASS |
| Native tool bridge, тихое ожидание модели | PASS |
| Pi: обрезка thinking/output и продолжение той же сессии | PASS |

Полные команды:
```sh
./gradlew checkMigrationJvm compileMigrationTargets -Pmagicpaper.node=/Users/aequicor/.local/bin/node --offline --continue
./gradlew :feature:session:impl:jvmTest --tests '*PlanningRuntimeIntegrationTest' --tests '*ResearchRuntimeIntegrationTest' --tests '*ChatRuntimeIntegrationTest' --tests '*AgentToolEngineIntegrationTest' --tests '*PiCodingRuntimeIntegrationTest.truncatedEmptyAnswerIsContinuedInSameSession' --tests '*SessionContextRuntimeTest' -Pmagicpaper.pi.it=true -Pmagicpaper.codex.it=true -Pmagicpaper.research.native=true --offline
```

Полные логи текущего рабочего дерева: `/tmp/magicpaper-complete-verification.log`
и `/tmp/magicpaper-native-final.log`. Счётчики JVM сохранены до отдельного native
прогона, поскольку Gradle заменяет XML при запуске выбранных тестов.

Native fixtures используют установленные движки, отдельные временные каталоги,
локальные scripted HTTP-модели и фиктивные ключи. Они не проверяют доступность
коммерческого провайдера или корректность каждой записи его живого каталога.
Нативные Windows/Linux и запуск на Android-устройстве здесь не выполнялись.

## Приёмка UI

Маршруты: обычный чат и проект → обычная сессия. Во время выполнения в одном
composer доступны «Пауза», «Уточнить», «В очередь»; после паузы — «Продолжить».
Прикрепление файла в обычном чате остаётся одним действием. Проверены 390 и 1000 px,
обычный масштаб текста, Compose/JVM на macOS. Семантические действия и границы
кнопок проверяются `CodingComposerRenderTest`; состояние вложений — двумя
`ChatComposer*RenderTest`. Live draft не перерисовывает сохранённую историю/composer:
`ActiveCodingChatRenderTest`.

Именованные previews, группа `Session input`:
`RunningSessionComposerPreview` и `RunningChatComposerPreview`.
Рендеры: `feature/session/impl/build/reports/session-input/`.
Сборка и headless render не являются ручной проверкой всех платформ и настроек
системной доступности.
