# Проверка этапа 1

Этап создал только `docs/desktop-ui/*`. Production-файлы, build scripts, ресурсы и skill installation не менялись. Никаких внешних публикаций, stash/reset/checkout, git add или коммитов исполнитель не выполнял.

## Baseline и параллельные изменения

Первое `git status --porcelain=v1` было пустым; HEAD `b0b84ae50bac08f5fb20fb1a6a062c58f5d98314`. Предыдущая диагностика четырёх unstaged-файлов уже устарела: `git show --stat b0b84ae` подтвердил их коммит до начала этапа:

- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/domain/Coding.kt`
- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/UiState.kt`
- `shared/src/commonMain/kotlin/io/aequicor/magicpaper/ui/UserInteractions.kt`
- `shared/src/commonTest/kotlin/io/aequicor/magicpaper/ui/UserInteractionStatusTest.kt`

Все четыре помечены **чужими** в `baseline.json`, снабжены SHA-256 и commit provenance; полный исходный patch — `foreign-session-recovery.patch`. Проверка после исследования подтвердила неизменность всех четырёх.

Между первым осмотром и записью durable baseline появился чужой unstaged `CodingScreen.kt` (две строки, сортировка дочерних сессий по createdAt). В durable snapshot: **staged 0, unstaged 1, untracked 0**; сам snapshot ещё не существовал на момент измерения. Патч — `foreign-unstaged.patch`. Позднее внешний исполнитель закоммитил этот же контент в `1c9e380` (Fix orchestrator child session ordering). Это не коммит текущего этапа. Хеш файла остался равен durable baseline; в финальном status только новые документы этого этапа. Нельзя восстанавливать прежний HEAD или накатывать патчи обратно поверх текущего дерева.

## Выполненные команды и результаты

| Команда / проверка | Результат |
|---|---|
| `git status --porcelain=v1`, `git diff --cached --name-status`, `git diff --name-status`, `git ls-files --others --exclude-standard` | Зафиксированы раздельные состояния в baseline.json; изменение между наблюдениями раскрыто выше. |
| `git show --stat b0b84ae`, `git show --format=fuller --binary b0b84ae -- <four paths>` | Подтверждено происхождение четырёх чужих изменений; сохранён patch. |
| `rg --files ...`, `rg -n ...` по production source sets, DI, App, build files | Проверены entry routes, enum Screen, common/JVM plugin wiring и Material/Markdown. Самостоятельный `rg` без совпадений возвращал 1; это не ошибка проекта. |
| `python3 docs/desktop-ui/audit.py --write` | Созданы inventory.json, MIGRATION.md и verification.json: 85 UI-файлов, 415 функций, 47 файлов Material, 1743 ссылки на символы, 125 Markdown-упоминаний. |
| `python3 docs/desktop-ui/audit.py` | PASS: сохранённая карта соответствует исходникам; unmappedUI=[]; четыре recovery файла неизменны; trackedDriftSinceBaseline=[]. |
| `./gradlew :shared:dependencies --configuration jvmRuntimeClasspath --console=plain` в sandbox | Exit 1: `FileLockContentionHandler` / SocketException Operation not permitted; полный лог сохранён. |
| Та же Gradle-команда через штатный `require_escalated` | Exit 0, **BUILD SUCCESSFUL in 6s**, 1 executed task; лог `gradle-jvm-dependencies-authorized.txt`, FAILED нет. Обычный Gradle cache, без --offline и без переноса кеша в проект. |
| Чтение Apple DocC JSON через Python urllib | Sandbox DNS недоступен; штатный разрешённый повтор успешно прочитал A1–A6. Ссылки и ограничения — SOURCES.md. |
| `git diff --check` и проверка whitespace новых docs | PASS; исходники не менялись. |

Повторить независимую проверку Material-files можно командой `rg -l '^import androidx\.compose\.material' shared/src desktopApp/src androidApp/src webApp/src --glob '*.kt' --glob '!**/*Test*/**'`; тестовые source sets при подсчёте отдельно исключить, как делает audit.py. Inventory различает импортированную ссылку и wildcard-кандидат; 1743 не выдаётся за точное число AST-вызовов. Полнота символов проверяется лексически, разрешение overload/alias окончательно проверит компилятор на этапах переноса.

## Что ещё не является доказанным результатом

Нет нового модуля/скилла/реализованных компонентов — это следующие этапы. Нет заявления об успешной сборке приложения: Gradle запускал только dependency report. Не запускались macOS/Windows UI, screen reader, IME, multi-monitor DPI, Android/web compilation. Эти проверки перечислены в контракте как приёмка реализации. Лицензии кандидатов проверены; reference trees целиком и все версии транзитивных артефактов не лицензированы заново этим исследованием.

## Файлы результата

- `CONTRACT.md` — поведение, бренд, токены, UI/plugin wiring, требования этапов.
- `MIGRATION.md`, `inventory.json`, `audit.py` — полная воспроизводимая карта и проверка покрытия.
- `SOURCES.md` — сравнение скиллов, лицензии, Apple/Microsoft/Compose, неизвестное отдельно.
- `DEPENDENCIES.md`, `markdown-poms.json`, `gradle-jvm-dependencies*.txt` — Markdown/Material и фактический JVM graph.
- `baseline.json`, `foreign-session-recovery.patch`, `foreign-unstaged.patch` — сохранение чужих изменений.
- `verification.json`, `VERIFICATION.md` — результаты критериев и команд.

Дополнительный ручной аудит вызовов в файлах с wildcard-импортами выявил `OutlinedCard`; он включён в symbol dictionary и PaperPanel replacement. После обновления повторная генерация и проверка дали 1743 Material-ссылки.


## Дополнительная проверка ac-research-map

После PARTIAL-приёмки добавлена отдельная карта **SURFACE-MAP.md**: одна строка на каждое UI-объявление, а не общий набор компонентов файла. Источник назначений — **surface-bindings.json**, проверка — **verify-map.py**, результаты — **map-verification.json**. В каждой записи есть путь, символ, строка, конкретные DS API и этап; таблица содержит постоянные taskId этапов. Назначения явные: проверяющий скрипт не генерирует пропущенные связи из имени файла.

Покрыто **183 записи**: 174 composable-функции, 4 composable-getter, 2 локальные композиции и 3 точки входа. По этапам: 3 → 18, 5 → 46, 6 → 91, 7 → 28. Включены все 6 экранов (5 enum routes + Welcome), private-панели, общие/JVM/Android/web адаптеры, plugin Content/SessionPanel и отдельно ProjectSkills.Content.

| Проверяемый пример | Конкретные DS API | Этап |
|---|---|---|
| App.MainArea | PaperNavigation, PaperSplitPane, PaperPluginHost | 5 |
| SettingsScreen.ProfileEditor | PaperSettingsSection, PaperField, PaperChoice | 5 |
| CodingScreen.ProjectsPanel | PaperTreeRow, PaperScroll, PaperButton | 6 |
| CodingScreen.CodingComposer | PaperComposer, PaperField, PaperButton, PaperAttachmentRow | 6 |
| DecisionPlanningPlugin.StageDetailsDialog | PaperDialog, PaperStatus, PaperMarkdown | 6 |
| JVM ProjectSkillsPanel.Content | PaperPanel, PaperListRow, PaperChoice, PaperButton, PaperStatus | 7 |
| JVM SkillCatalogPanel.SkillCatalogPanel | PaperPanel, PaperField, PaperListRow, PaperButton | 7 |
| ChatMarkdown.MarkdownCodePart | PaperCodeBlock, PaperText | 6 |

Повторный просмотр всех Compose-аннотаций обнаружил полное имя аннотации в `domain/ProjectSkills.kt`, дополнительную аннотацию перед `MarkdownCodePart`, composable getters типографики и две локальные композиции CodingScreen. Все включены в явную карту; файловая инвентаризация теперь 85 файлов/415 функций. Уточнено подключение ProjectSkillsPanel: ProjectSkills.Content, а не CodingSessionPanel. Production-код не менялся.

`python3 docs/desktop-ui/verify-map.py --self-test` — PASS: missingBindings=[], staleBindings=[], unknownTargets=[], unknownStages=[]. Проверка отвергает три намеренных дефекта в памяти: удалённую связь, неизвестный DS-компонент, неизвестный этап. `python3 docs/desktop-ui/audit.py` также запускает эту проверку и сохраняет её результат в общем verification.json при --write. Четыре чужих recovery-файла и все tracked хеши baseline по-прежнему совпадают. `git diff --check` — PASS; отдельно проверены ссылки и whitespace новых документов.

Предыдущие доказательства контракта/источников, лицензий, Gradle JVM dependency report и baseline остаются актуальными. Gradle повторно не запускался: изменены только исследовательские документы/скрипты, зависимости и production-код неизменны. Ограничения UI/runtime-проверок остаются прежними.
