# Автоматические предложения кандидатов

Этап 5, 2026-09-08. Перед изменениями прочитаны незакоммиченный снимок,
`run-experience.md`, `SKILLS-EXPERIENCE.md` и актуальные исходники журнала,
панели и репозитория. Сохранены чужие staged/unstaged/untracked изменения.

## Контракт обнаружения

- `LocalSkillExperience.suggestions()` автоматически группирует живые исходы
  по точному сценарию и множеству закрытых признаков. Порог — **3 разных UUID
  подтверждённых успешных исходов**. Это консервативное правило повторяемости,
  а не статистическая достоверность или обещание качества навыка.
- Учитываются только `SUCCESS` с `PASSED` независимого `SkillRunVerifier`
  либо явно введённым пользователем `USER_CONFIRMED`. `FAILURE`, `UNKNOWN`,
  `CANCELLATION`, неизвестный сценарий и непроверенный успех не учитываются.
  Повторная доставка одного запуска не увеличивает счётчик.
- Каждый паттерн показывает число подходящих исходов, порог и выбранное
  количество. Для контекста выбираются не более 6 старейших исходов;
  равное время разрешается сортировкой UUID. Сортировка стабильна после открытия.
- Источники всех сохранённых кандидатов исключены из автоматического подбора,
  включая регрессию и прерванную оценку. Для следующего предложения нужны
  минимум 3 ещё не использованных успеха. Явный ручной generate-контур с
  выбором 2–6 исходов сохранён, в том числе ручные повторные эксперименты.
- Предложения вычисляются из живого журнала и не создают второй постоянной
  копии опыта. `changes: StateFlow<Long>` обновляет открытую панель при записи;
  при повторном открытии предложения восстанавливаются из журнала.
  Удаление/retention автоматически убирают утратившие порог предложения.

## Согласие, карантин и продвижение

1. Обнаружение и `previewSuggestion(sources, profile)` локальны, не вызывают
   модель и не записывают пакет. Панель показывает точный контекст, профиль,
   допустимые инструкции SKILL.md и синтетические проверки.
2. Только кнопка «Разрешить отправку, создать и проверить» вызывает существующий
   `generate(token, confirmed = true)`. Токен одноразовый, согласие относится к
   одному циклу: выбор канонического шаблона и 14 сравнительных вызовов.
   `false` не отправляет данные. Порог и неиспользованность источников проверяются
   ещё раз под mutex перед первым вызовом.
3. В журнале допускается один выполняющийся цикл генерации. Параллельное согласие
   отклоняется до сети; устаревший preview не использует уже учтённые источники.
   Если ошибка случилась до записи кандидата, можно сделать новый preview и дать
   новое согласие. Если запись произошла, карантин сохраняется, повторного
   автоматического предложения из тех же источников не будет и после restart.
4. Создание сохраняет канонический SKILL.md и манифест в локальном репозитории
   со статусом `QUARANTINED`. Это существующий staging API `repository.install`,
   а не установка в движок. Ни review, ни active, ни project bind не меняются.
5. Четыре фиксированных и три отложенных теста сравнивают baseline с кандидатом.
   Все семь должны дать точное совпадение и не ухудшить baseline. Незавершённая
   оценка или регрессия блокируют activation/project bind даже после review.
6. Успешные оценки оставляют карантин. Нужны review точного checksum и отдельное
   согласие на активацию; подключение проекта и доверенная доставка текста
   по-прежнему требуют собственного consent. Изменение baseline проверяется
   существующим контрактом репозитория.

В этом этапе не добавлены чаты, prompt/output, содержимое или пути рабочих файлов,
названия проектов, секреты и произвольные инструкции в память. В модель идут
закрытые признаки и каталог приложения; UUID в сообщения не попадают.
Новых схем или миграций журнала нет.

## Граница для этапов 6–8

Доставка адаптерам использует прежние `SkillReleaseSnapshot`, project pins,
review, checksum и consent. Новый путь не обходит эти границы. Для приёмки:
три независимо проверенных запуска → предложение → preview → согласие →
карантинный SKILL.md с оценками → review → отдельное согласие на активацию
и подключение. Кандидат с проваленной отложенной проверкой не может попасть
в активный состав или project pins.

Сохраняется ограничение этапа 4: штатный универсальный verifier возвращает
`UNAVAILABLE`, поэтому произвольный coding-run не становится положительным
опытом сам по себе. Нужен прикладной независимый verifier либо явная ручная
отметка пользователя. Автоподбор и полный положительный путь проверяются
тестовым verifier; живые модельные вызовы не заявляются.

## Проверки

Адресный набор: `ExperienceSuggestionsTest` (7 новых тестов),
`LocalSkillExperienceTest`, `SkillRunCompletionTest`, `LocalExperiencePanelTest`.
Проверяются порог, точный паттерн, четыре исхода, UUID replay, restart,
одноразовый consent, карантин, review, activation, запрет project bind при
регрессии, конкурентная генерация, прерванная оценка, удаление, retention,
ограничение до 6 источников и сохранение ручного контура.

Финальная команда (тестовая задача принудительно повторена):

```sh
./gradlew --init-script shared/build/automatic-candidates/reports.init.gradle :shared:jvmTest --rerun --tests '*ExperienceSuggestionsTest' --tests '*LocalSkillExperienceTest' --tests '*SkillRunCompletionTest' --tests '*LocalExperiencePanelTest' :desktopApp:compileKotlin --console=plain
git diff --check
git diff --cached --check
```

Первый запуск ограничен sandbox на локальном сокете Gradle. Штатная эскалация
разрешена; используется установленный toolchain и обычный кеш. Промежуточные
прогоны остановлены на синтаксисе параллельно создаваемого
`SkillAdapterWireIntegrationTest.kt`; этот файл этап 5 не редактировал.

После исправления соседним этапом — **BUILD SUCCESSFUL**. Общий XML был заменён
параллельным прогоном, поэтому финальная проверка повторена с отдельными
каталогами отчётов: **41 тест, 0 failures/errors/skipped** (7 + 23 + 10 + 1).
Desktop-компиляция — PASS. Оба diff-check — PASS.

XML: `shared/build/test-results/automatic-candidates/TEST-*.xml`.
HTML: `shared/build/reports/tests/automatic-candidates/index.html`.
Служебный init script находится в игнорируемом `shared/build/automatic-candidates`
и меняет только пути отчётов и бинарных результатов, не Gradle cache:

```groovy
allprojects {
    tasks.withType(org.gradle.api.tasks.testing.Test).configureEach {
        reports.junitXml.outputLocation = layout.buildDirectory.dir('test-results/automatic-candidates')
        reports.html.outputLocation = layout.buildDirectory.dir('reports/tests/automatic-candidates')
        binaryResultsDirectory = layout.buildDirectory.dir('test-results/automatic-candidates-binary')
    }
}
```

## Файлы этапа

- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/ExperienceSuggestions.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/data/skills/LocalSkillExperience.kt`
- `shared/src/jvmMain/kotlin/io/aequicor/magicpaper/plugins/builtin/LocalExperiencePlugin.kt`
- `shared/src/jvmTest/kotlin/io/aequicor/magicpaper/data/skills/ExperienceSuggestionsTest.kt`
- `docs/skills-project/automatic-candidates.md`

## Повторная проверка пересечений (turn 1)

После уведомления о пересечении с другим планом перечитаны фактические
`LocalSkillExperience.kt`, `LocalExperiencePlugin.kt`, их изменения и отчёт
`run-experience.md`. Несовместимых изменений не обнаружено: проверенные исходы,
автоподбор, уведомления панели, ручной preview/generate, согласие, карантин,
дедупликация и блокировка регрессии согласованы. Повторных правок исходников
не потребовалось; чужие staged/unstaged/untracked изменения сохранены.

Повторена приведённая выше финальная Gradle-команда с `:shared:jvmTest --rerun`
и отдельными каталогами отчётов. **BUILD SUCCESSFUL за 6 секунд: 41 тест,
0 failures/errors/skipped** (7 ExperienceSuggestions, 23 LocalSkillExperience,
10 SkillRunCompletion, 1 LocalExperiencePanel); desktop-компиляция — PASS.
`git diff --check` и `git diff --cached --check` — PASS.
В этом продолжении изменён только данный отчёт.
