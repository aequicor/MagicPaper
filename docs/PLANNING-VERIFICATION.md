# Проверка планирования — 2026-09-07

Продолжение checkpoint `cd343be`, macOS ARM64. Проверки используют временные
проекты, локальные тестовые модели и изолированный профиль Codex.

Итоговый прогон: **BUILD SUCCESSFUL**, JVM-отчёт — **254 теста, 0 ошибок**.
Компиляция JS/Wasm/Desktop и сборка Android debug прошли. Все четыре
интеграционных теста Pi/Codex включены и прошли. Windows-only тест на macOS
возвращается без исполнения; счётчик JVM включает его, поэтому он не означает
повторной проверки Windows. После прогона процессы тестовых исполнителей
и дочерние процессы crash-fixture не обнаружены. `git diff --check` прошёл.

## Команда

```sh
./gradlew :shared:jvmTest :shared:compileKotlinJs :shared:compileKotlinWasmJs :androidApp:assembleDebug :desktopApp:compileKotlin -Pmagicpaper.codex.it=true -Pmagicpaper.pi.it=true --console=plain
```

Журнал: `/tmp/magicpaper-planning-final-verification.log`.
Отчёт тестов: `shared/build/reports/tests/jvmTest/index.html`.
Изображения: `shared/build/reports/planning/`.

## Матрица ошибок и восстановления

| Область | Подтверждение и предел проверки |
|---|---|
| Сеть, 429, 5xx, Retry-After | `PlanningRetryPolicyTest`, `retryCountIsDurable`, `transportLimitSurvivesExplicitContinuation`; новый тест исключений подготовки проверяет HTTP 500 и три сохранённых повтора |
| Исключения проверяющего этапа | `thrownVerificationErrorsPreservePhaseAndExhaustDurableRetries`: четыре обращения, три повтора, единственный запуск реализации, сохранённая фаза VERIFYING |
| Исключения итогового проверяющего | `finalVerifierThrownErrorRetainsAttemptAndRetryBudget`: исчерпанный бюджет блокирует продолжение, реализация не повторяется |
| Параллельная ошибка заменяет общее ожидание | `savedRetryDeadlineIsRespectedEvenWithoutTheRunIssue`: срок попытки продолжает блокировать запуск |
| Невалидный JSON | `DecisionPlannerTest`, включая `verifierRequiresVerdictSchemaAndNeverAcceptsEmptyReport`: ограниченные исправления; недоступная проверка не подтверждает результат |
| Авторизация и настройки | Проверка кода preflight и разрешения назначения; Codex integration проверяет пустой профиль и чтение моделей. Исправление/истечение настоящих удалённых учётных данных не воспроизводилось |
| Неуспешный результат | `failedChecksHaveOnlyTwoRepairs`, `engineFailureCannotPassPositiveVerifier`: ограничение исправлений и запрет ложного успеха |
| Объединение и перенос | `GitPlanningWorkspaceTest`: изолированные Git-репозитории, сохранение index и пользовательских файлов, возобновление операций; сервис проверяет сохранённые фазы merge/delivery до переноса |
| Неизвестный внешний эффект | `unknownExternalCommandWaitsBeforeStartingAnotherExecutor`, `finalVerificationUnknownExternalCommandRequiresAcknowledgement`: новый исполнитель не стартует до подтверждения |
| Потеря владельца процесса | `recoveryAfterOwnerCrashTerminatesAgentAndChildTool`: реальное принудительное завершение отдельного JVM-владельца и очистка записанного агента с дочерним инструментом на macOS |
| Повреждение и ошибка записи | `JsonPlanningRepositoryTest`: резервный снимок, восстановление по контрольным записям, сохранение удаления, блокировка изменений после ошибки записи |
| Пауза и остановка | `bootstrapLeavesUserPauseAndStopAlone`, `pauseAllowsCurrentWorkToFinishButDoesNotDispatchNext`, `stopAbortsSessionAndReleasesOwner` |
| Pi и Codex | Флаги интеграции включены: установка Pi, редактирование типографики, продолжение обрезанного ответа; Codex app-server с изолированным пустым профилем |
| Граф | Compose рисует 1000×600, 390×600, 200 узлов, проверяет выбор и показ целиком. Изображения просмотрены: кнопка не перекрывает первый этап; при показе 200 узлов подписи закономерно требуют увеличения |

## Оставшиеся критерии

- Полное аварийное завершение GUI с настоящими удалёнными исполнителями на
  каждой границе подготовки, исполнения, проверки, объединения и переноса.
- Нативный прогон Linux; повторный Windows-прогон новых изменений.
- На macOS тест подтверждает очистку при восстановлении живого записанного
  агента. Он не подтверждает немедленное завершение инструментов при падении
  приложения и не покрывает случай, когда агент уже умер, а его дочерние
  процессы отделились. Текущая запись владения хранит PID агента, а не весь
  долговечный реестр потомков; этот случай требует отдельного решения.
- Настоящие ошибки авторизации и сетевые разрывы удалённых провайдеров на всех
  границах не проверялись. Локальные инъекции сбоев не заменяют эти сценарии.

Эта проверка не означает завершение всех критериев исходного плана.
