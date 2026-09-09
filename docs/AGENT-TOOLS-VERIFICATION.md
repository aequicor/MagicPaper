# Проверка единых инструментов агентов

Проверено 2026-09-09 на macOS. База — зафиксированный `main` (`c86268f`).
Рабочая ветка: `codex/unified-agent-tools`; основной worktree:
`/private/tmp/MagicPaper-unified-agent-tools`.

## Результаты

- Целевой JVM-набор: **300 тестов, 0 ошибок, 0 пропусков**, включая реальные движки.
- pi **0.84.4** и Codex **0.153.4**: вызов инструмента приложения, результат в следующем
  запросе модели, продолжение ответа и отказ в запрещённой операции. Модели — локальные
  HTTP-серверы с фиксированными ответами; платные запросы не используются.
- Нативное планирование обоих движков читает актуальные файлы, staged/unstaged изменения
  и новые файлы; попытки записи не меняют ни файлы, ни индекс Git.
- Node **26.8.1**: **14 проверок, 0 ошибок** для planning-tools и provider-bridge.
- Компиляция `:desktopApp:compileKotlin`, `:shared:compileKotlinJs`,
  `:shared:compileKotlinWasmJs` прошла.
- Дизайн-система с локальными правками пользователя: **11 тестов, 0 ошибок**.
- `git diff --check` прошёл.

## Сценарии

`AgentToolsTest` проверяет каталог ролей и режимов, отсутствие возможностей без плана,
ошибочные аргументы, неизвестные инструменты, доверенный контекст и владельца статистики,
параллельные вызовы, повторную доставку, отмену и блокировку неоднозначного повтора.
Опросник ожидает подтверждённый ответ и не выдаёт разрешения. Наблюдатели скрывают секреты,
а нативные события не запускают команды повторно.

`OrchestrationToolsTest` проходит весь путь: предложение через `plan.propose` →
подтверждение → рабочие сессии → `stage.handoff` → `stage.resolve` → приёмка.
Проверяются чужие идентификаторы, устаревшая ревизия, ограничения автоматических сообщений,
выборочная пауза зависимых этапов, расписание, фиксированное время ожидания и восстановление
доставки при потере окончательной квитанции. Старые сохранённые решения проходят через
команды; текст исторического сообщения не становится действием.

Карточка родительской команды остаётся видимой при вложенном планировании. Ключи вызовов
сохраняются при переходе в историю; сохранённые дочерние вызовы не дублируются в живом
ответе. Проверены состояния ожидания/отмены, раскрытие и большой вывод. Render-тест сохраняет
изображение в `shared/build/reports/agent-tools/cancelled-tool.png`.

`CodexToolEventsTest` проверяет произвольные MCP-вызовы, прогресс, результат, ошибку,
отмену и разделение потоков. `AgentToolBridgeTest` проверяет единый объект разрешённых
MCP-серверов в защищённом режиме: пустой корневой список не перекрывает мост приложения.
В обычном режиме сохраняются унаследованные настройки серверов пользователя.

## Полный набор и прежние ошибки

В контрольном worktree с копиями всех семи незакоммиченных правок интерфейса выполнен
полный набор: **980 тестов shared, 12 ошибок, 2 пропуска**. Набор ошибок совпадает с
исходной веткой; новых ошибок интерфейса не появилось. Последующее исправление объединения
настроек MCP проверено целевым набором с реальными pi/Codex и отдельной проверкой конфигурации.

Восемь прежних ошибок длинных сообщений, прокрутки и маркеров описаны и воспроизведены
в [USAGE-VERIFICATION.md](USAGE-VERIFICATION.md#existing-ui-baseline-failures).
Ещё четыре отдельно воспроизведены на неизменённом `c86268f`:

- `PlanningSessionStatusTest.finalChecksRemainWorkingUntilAProposalCanBeConfirmed`;
- `PlanningSessionStatusTest.unavailableProposalDoesNotHideResumeOrAnEventWait`;
- `PlanningSessionStatusTest.proposalDuringExecutionDoesNotAskForAnotherAnswer`;
- `PlanningProposalRenderTest.collapsedSummaryKeepsProposalReachableWhileShowingActualWork`.

Обнаруженная нестабильность `ModelLibraryTest` устранена: время `UsageArchive` в тестовых
данных фиксировано, поэтому сериализация не зависит от смены миллисекунды. Производственное
поведение учёта расходов не менялось.

## Воспроизведение

Полный набор и компиляция:

```sh
./gradlew :shared:jvmTest :designSystem:jvmTest :desktopApp:compileKotlin \
  :shared:compileKotlinJs :shared:compileKotlinWasmJs --offline --continue
```

Сценарии инструментов и реальных движков:

```sh
./gradlew :shared:jvmTest --tests '*AgentTool*' --tests '*OrchestrationToolsTest' \
  --tests '*PlanningRuntimeIntegrationTest' --tests '*CodexToolEventsTest' \
  --tests '*CodingChatRowsTest' --tests '*CodingToolPreviewRenderTest' \
  -Pmagicpaper.pi.it=true -Pmagicpaper.codex.it=true --offline
node --test shared/src/jvmTest/resources/coding/planning-tools.test.mjs \
  shared/src/jvmTest/resources/coding/provider-bridge.test.mjs
```

Для защиты локальной работы сохранены отдельные staged/unstaged патчи, список untracked
файлов и контрольные суммы. Их копии проверены в интеграционном worktree; исходные правки
интерфейса не включаются в коммиты этой задачи. Публикация в удалённый репозиторий не выполняется.
