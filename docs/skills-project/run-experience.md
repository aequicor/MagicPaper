# Завершение coding-запуска → строгий локальный опыт

Этап 4, 2026-09-08. Перед изменениями прочитаны фактический незакоммиченный
снимок, `uncommitted-snapshot.md`, `SKILLS-EXPERIENCE.md` и исходники журнала,
runtime, DI и вызывающих компонентов. Существующие staged/unstaged изменения
сохранены; публикаций и изменений index не было.

## Контракт

- `CodingRunCheckpoint.runId` сохраняется вместе с запросом. Значение по
  умолчанию — его `messageId`, поэтому старые checkpoint восстанавливают ту же
  идентичность. Desktop преобразует пару session/request в непрозрачный UUID.
  Один UUID используется в квитанции SKILLS и в опыте. Этапы плана используют
  attempt/turn, разрешение конфликта — attempt/merge, legacy PlanRunner — plan/milestone.
  Для прямого вызова runtime без checkpoint UUID стабилен в пределах возвращённого
  Flow; для восстановления после перезапуска вызывающая сторона должна передать checkpoint.
- `beginRun` до исполнения сохраняет `SkillRunTicket(runId, generation)`.
  `completeRun` фиксирует первый результат атомарно; повтор, конкурентная доставка
  и повтор после открытия не создают вторую запись и не повторяют проверку.
- Различаются `SUCCESS`, `FAILURE`, `CANCELLATION`, `UNKNOWN`; прежнее поле
  `success` совместимо и истинно только для `SUCCESS`. Панель показывает четыре исхода.
- `Finished`, `AgentEnd`, отсутствие ошибки инструмента и текст «SUCCESS» не
  подтверждают выполнение задачи. `SkillRunVerifier` получает только UUID и
  возвращает закрытые verdict/scenario/features. Проверка выполняется вне
  блокировки журнала, ограничена пятью секундами; ошибка/таймаут дают `UNKNOWN`.
  Ошибка транспорта, отмена или незавершённый поток не допускаются к положительной проверке.
- **Штатный verifier возвращает UNAVAILABLE:** универсальная независимая проверка
  произвольной coding-задачи отсутствует. Такие завершения уже записываются как
  `UNKNOWN`, без сценария и признаков. Для подтверждённого успеха требуется
  внедрённый прикладной verifier; положительный путь проверен тестами. Этот этап
  не выдаёт утверждения модели за независимую проверку и не запускает LLM/сеть.
- В журнал поступают только UUID, время, закрытые перечисления, boolean и
  существующие структурированные данные кандидатов. Чат, prompt, output,
  содержимое/пути файлов, названия проектов, session ID и ошибки не передаются
  verifier и не записываются. Неопределённый сценарий не участвует в повторах;
  непроверенные результаты не допускаются в preview обучения.
- Удаление (включая отдельную запись и retention) атомарно меняет durable
  generation и очищает регистрации запусков. Все прежние tickets становятся
  недействительными; проверка сверяет generation повторно перед записью.
  Поздний callback не восстанавливает удалённое, в том числе после открытия.
  Это консервативно отбрасывает и остальные находившиеся в полёте проверки.
  Новый запуск явно регистрируется через `beginRun`; callback вызывает только `completeRun`.
- Прежние D2-записи считаются явно введёнными пользователем
  (`USER_CONFIRMED`). Неизвестные поля/enum и положительные записи с
  `UNAVAILABLE` отклоняются при открытии. Лимит — по 200 исходов и регистраций,
  срок регистраций равен retention журнала. Сбой или заполнение необязательного
  журнала не заменяет результат coding-запроса.

## Проверка критериев

| Критерий | Проверки в `SkillRunCompletionTest` |
| --- | --- |
| Стабильная идентичность | `checkpointIdentitySurvivesSerializationRecoveryAndSeparatesTurns`, `desktopRuntimeUsesCheckpointIdentityAcrossCallsAndRecordsCancellationBeforeTransport` |
| Идемпотентность, конкурентный callback, restart | `duplicateConcurrentCompletionAndReopenVerifyOnceAndRecordOnce` |
| Четыре исхода, независимое подтверждение | `allFourResultsAndFailedVerificationAreDistinct`, `failedCancelledIncompleteAndAbortedFlowsCannotUsePassedVerifier` |
| Непроверенный результат не положительный | `flowReplayFinishedAndModelClaimsNeverProducePositiveExperienceOrLeakText`, `unavailableThrowingAndTimedOutVerifiersFailClosed` |
| Запрет исходных текстов и несовместимого JSON | предыдущий flow-тест, `legacyManualRowsRemainExplicitAndUnverifiedPositiveRowsAreRejectedOnOpen` |
| Удаление во время проверки и restart | `deletionDuringIndependentVerificationAndReopenRejectLateCallbacks`, `individualDeletionAndRetentionFencePendingRuns` |

Финальная команда:

```sh
./gradlew :shared:jvmTest --tests '*SkillRunCompletionTest' --tests '*LocalSkillExperienceTest' --tests '*PlanRunnerTest' --tests '*CodingRunRecorderTest' --tests '*PlanningExecutionServiceTest' --tests '*ProjectSkillsTest' --console=plain
git diff --check
git diff --cached --check
```

Результат: **BUILD SUCCESSFUL, 100 тестов, 0 failures/errors/skipped**:
10 новых, 23 LocalSkillExperience, 6 PlanRunner, 17 CodingRunRecorder,
31 PlanningExecutionService, 13 ProjectSkills. Оба diff-check — PASS.
XML: `shared/build/test-results/jvmTest/TEST-*.xml`.
Сборка использовала установленный toolchain и обычный Gradle cache.
Первый sandbox-запуск остановился на запрете локального сокета Gradle;
штатное escalated-разрешение позволило продолжить. Промежуточные ошибки
компиляции исправлены; две ошибки параллельного этапа исчезли после его правок,
его файлы в этом этапе не редактировались.

## Файлы этого этапа

- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt`
- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/PlanRunner.kt`
- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/PlanningExecutionService.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/coding/DesktopCodingRuntime.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillExperience.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/SkillRunCompletion.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/di/Dependencies.jvm.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/plugins/builtin/LocalExperiencePlugin.kt`
- `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/SkillRunCompletionTest.kt`
- `docs/skills-project/run-experience.md`

Автоматический запуск создания кандидатов остаётся следующим этапом; текущие
карантин, holdout-проверки, review и согласие на активацию сохранены.

## Повторная проверка пересечений (turn 1)

После трёх уведомлений оркестратора перечитаны фактические
`Dependencies.jvm.kt`, `LocalSkillExperience.kt`, `LocalExperiencePlugin.kt`
и их diff. Несовместимых правок не обнаружено: DI сохраняет единый journal
для runtime и панели, project selection/квитанции и фоновую retention;
журнал сохраняет прежние generate/holdout/delete и новые run tickets;
панель поддерживает nullable scenario и четыре исхода.
Повторные правки исходников не потребовались, чужие изменения не откатывались.

Принудительно повторён именно тестовый task:

```sh
./gradlew :shared:jvmTest --rerun --tests '*SkillRunCompletionTest' --tests '*LocalSkillExperienceTest' --tests '*PlanRunnerTest' --tests '*CodingRunRecorderTest' --tests '*PlanningExecutionServiceTest' --tests '*ProjectSkillsTest' --console=plain
git diff --check
git diff --cached --check
```

**BUILD SUCCESSFUL: 100 tests, 0 failures, 0 errors, 0 skipped**; оба
diff-check — PASS. В этом продолжении изменён только данный отчёт.
