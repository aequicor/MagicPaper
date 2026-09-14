# Лучшие практики параллельных задач агента в одном репозитории

> Исследование: июль 2025. Источники — GitHub Blog, MindStudio, Augment Code, DEV Community,
> Claude Codex, Red Hat, документация pi/MagicPaper.

---

## 1. Почему параллелизация агентов требует особой архитектуры

Агенты генерируют код быстрее человека и работают без «переключения контекста». Стандартная
разработческая инфраструктура (один рабочий каталог, одна БД, один порт dev-сервера) рассчитана
на одного разработчика и ломается при параллельном запуске:

| Проблема | Проявление |
|----------|------------|
| **Конфликты файлов** | Два агента читают один файл, генерируют правки независимо — вторая запись затирает первую |
| **Загрязнение контекста** | Агент читает незакоммиченные изменения другого агента и строит ложные предположения |
| **Конфликты БД** | Миграции и сиды одного агента ломают тестовые данные другого |
| **Конфликты портов** | Два dev-сервера не могут занять один порт |
| **Блокировки инструментов** | Language server, тестовый раннер, сборщик удерживают файловые локи |

**Вывод:** параллелизация без изоляции создаёт больше работы, чем экономит.

---

## 2. Шесть ключевых паттернов

### 2.1. Изоляция через Git Worktree

**Суть:** каждый агент получает отдельный рабочий каталог (worktree) с собственной веткой.
Все worktree разделяют один `.git` — общую историю и объекты, но файлы физически разделены.

```
my-project/             ← main worktree (main)
my-project-feat-auth/   ← worktree агента 1 (feature/auth)
my-project-feat-api/    ← worktree агента 2 (feature/api-refactor)
my-project-fix-pay/     ← worktree агента 3 (fix/payments)
```

**Правила:**
- Создавать worktree от стабильной базы (`main`), не от другой feature-ветки
- Каждый worktree требует отдельного `npm install` / `gradle build`
- `.env` файлы копируются в каждый worktree
- По завершении: merge → `git worktree remove` → удаление ветки

**Практика MagicPaper:** приложение уже создаёт изолированные worktree для каждого потока
изменений (`codex/magicpaper/<назначение>-<хеш>`), с отдельным index и арендой писателя.
Механизм `session_create` + `workspace` обеспечивает эту изоляцию автоматически.

### 2.2. Декомпозиция по домену, а не по файлу

**Правильно:**
- Агент 1: модуль аутентификации
- Агент 2: платёжная логика
- Агент 3: UI дашборда

**Неправильно:**
- Агент 1: добавить валидацию в `utils.ts`
- Агент 2: рефакторинг обработки ошибок в `utils.ts`

Даже с worktree, второй вариант создаст merge-конфликты и семантические противоречия.

**Критерий:** задача параллелизуема, если агенты не пересекаются по файлам и интерфейсам.
Hotspot-файлы (роутинг, конфиги, DI-регистрации) — маркер того, что задачи нельзя разделить.

### 2.3. Ролевое разделение: Coordinator / Specialist / Verifier

Паттерн из GitHub Squad и Augment Code:

| Роль | Ответственность |
|------|-----------------|
| **Coordinator** | Декомпозиция, маршрутизация, мониторинг прогресса. Не делает работу. |
| **Specialist** | Реализация в своей области. Собственный контекст, собственный scope. |
| **Verifier** | Независимая проверка. Не может исправлять отклонённую работу — только другой агент. |

**Ключевой принцип:** автор не рецензирует собственную работу. Верификатор работает с
свежим контекстом, без памяти о решениях реализации.

**В MagicPaper:** родительская сессия (coordinator) создаёт дочерние (specialists),
передаёт контекст через `session_send`, принимает результат через `session_result_review`.
Интеграция через `session_results_integrate` выполняет итоговую проверку конфликтов.

### 2.4. Spec-Driven декомпозиция задач

Каждая задача получает спецификацию с:
- **Границами файлов** — какие файлы можно менять
- **Контрактами** — какие интерфейсы сохранять
- **Критериями приёмки** — тесты, покрытие, проверки
- **Ограничениями** — что нельзя трогать

```
Роль: Backend API Developer
Задача: Реализовать GET /weather
- Маршрут: /weather
- Валидация: zod-схема для city
- Таймаут: 5 секунд на внешний вызов
Приёмка:
- Unit-тесты > 80% покрытия
- Интеграционный тест с mock-сервисом
```

**Без спецификации** агент расширяет scope: добавляет кеширование, рефакторит логирование,
меняет соседние модули. Спецификация удерживает задачу в пределах working set агента.

**Метрики:** multi-file задачи ~19% accuracy vs. single-function ~87%. Меньшие задачи
умещаются в эффективный working set агента.

### 2.5. Fan-out / Fan-in

Паттерн параллельного выполнения с интеграцией:

```
Fan-out: 3 агента параллельно
├── Агент A: фикс auth
├── Агент B: фикс billing  
└── Агент C: фикс search

Fan-in: интеграционный агент
└── Объединяет результаты, разрешает конфликты
```

**В MagicPaper:** `session_create` с `failurePolicy: ISOLATE` или `CANCEL_SIBLINGS`,
затем `session_wait` для ожидания всех детей, затем `session_results_integrate` для
объединения CODE-результатов с проверками.

### 2.6. Последовательная цепочка с накоплением (Stacked Branches)

Альтернатива fan-out для зависимых задач:

```
Issue 1 → PR 1 (ветка A)
Issue 2 → PR 2 (ветка A → ветка B, target = ветка A)
Issue 3 → PR 3 (ветка B → ветка C, target = ветка B)
```

Каждый PR проходит независимое ревью в свежем контексте. Стек мерджится последовательно.

**Практика из Manasight (80K строк за 51 день):** автор обнаружил, что параллельная
работа агентов *замедляет* его, потому что:
- Агенты работают от устаревшего контекста
- Merge устаревших изменений сложнее, чем последовательное ревью
- Узкое место — человеческое ревью, а не скорость кода

**Вывод:** оптимизируйте под пропускную способность человека, а не под скорость агента.

---

## 3. Координация без общего состояния

### 3.1. Drop-box паттерн (Squad / GitHub)

Вместо real-time синхронизации между агентами — версионируемый файл `decisions.md`:

- Каждое архитектурное решение дописывается как структурированный блок
- Все агенты читают файл при старте
- Асинхронный обмен знаниями масштабируется лучше, чем live-синхронизация
- Markdown-файл = shared brain с историей и аудит-трейлом

### 3.2. Shared Task List

Общий список задач (файл, доска), который все агенты читают и обновляют:
- Агент берёт задачу, помечает in-progress
- Другие агенты видят и не дублируют
- Завершённые задачи помечаются с результатами

**В MagicPaper:** родительская сессия распределяет задачи через `task` при создании
дочерних сессий. Зависимости задаются через `dependencies`.

### 3.3. Context Replication > Context Splitting

Не делите один контекст между агентами — реплицируйте нужный контекст в каждого:

- Каждый агент получает собственный контекстный окно (до 200K токенов)
- Coordinator — тонкий роутер, не делает работу
- Каждый специалист «видит» свой участок репозитория без конкуренции за место

---

## 4. Инфраструктурная изоляция

### 4.1. База данных

| Подход | Когда применять |
|--------|-----------------|
| Отдельный SQLite-файл | Простейший случай, SQLite |
| Отдельная Postgres-БД | Shared сервер |
| Database branching (Neon) | Облачная БД, нужны реалистичные данные |

### 4.2. Порты и ресурсы

Каждый worktree/агент получает свой порт, свой Redis-префикс, свои ключи API.

### 4.3. Переменные окружения

`.env` не шарится через git. Копировать в каждый worktree при создании, или использовать
`.env.shared` с симлинками.

---

## 5. Проблема больших проектов: стоимость worktree

Git worktree **не дублирует `.git`** (историю и объекты), но создаёт полную копию **рабочих файлов**.
Для крупного проекта это:

| Проблема | Масштаб |
|----------|----------|
| **Дисковое пространство** | 2 ГБ код × 4 worktree = ~8 ГБ + build-артефакты |
| **Зависимости** | `node_modules` 750K файлов → 10+ минут на `yarn install` |
| **Gradle sync** | KMP-проект с десятками модулей → минуты на конфигурацию |
| **IDE-индексация** | Каждый worktree = отдельный проект для IDE |
| **Build-кэш** | Каждый worktree строит свой кэш с нуля |

**Реальный пример** (Dave Schumaker, Yarn workspaces monorepo):
> «Каждый раз при создании worktree: 10+ минут на `yarn install --immutable`. Для задачи, которая иногда занимает 5 минут. Это часть, о которой не пишут в статьях про worktree для AI-агентов».

### 5.1. Решение: Recycled Worktrees (Пул фиксированных слотов)

Вместо создания/удаления worktree по требованию — **пул заранее подготовленных слотов**:

```
.worktrees/
├── tree-1/  ← slot с установленными зависимостями
├── tree-2/  ← slot с установленными зависимостями
├── tree-3/  ← slot с установленными зависимостями
└── ...
```

**Как работает:**
1. При инициализации создаётся N слотов (например, 6) с `yarn install`
2. Когда нужен worktree — **активируем** свободный слот:
   - `git checkout -b feature/new` в слоте
   - Проверяем, изменился ли `yarn.lock` / `build.gradle`
   - Если нет — **пропускаем install** (секунды вместо минут)
3. После завершения — слот очищается и возвращается в пул

**Ключевая оптимизация:** большинство веток основаны на свежем `main`, где зависимости не меняются.
Переключение занимает секунды, а не минуты.

### 5.2. Решение: Shared Dependencies через Sym-links

Инструмент **workz** (Rust CLI) автоматически симлинкает тяжёлые директории:

```bash
# Автоматическое обнаружение и симлинкация
workz create feature/login --ai

# Симлинкает:
# - node_modules (если package.json не изменился)
# - target/ (Rust/Java build output)
# - .venv (Python virtualenv)
# - .gradle/caches
# - и другие (22+ типа)
```

**Ограничения:**
- Симлинки `node_modules` ломают некоторые инструменты (Vitest, Vite)
- Для Gradle/KMP — симлинкаем `.gradle/caches`, но не `build/`
- Каждый worktree всё равно нуждается в собственном `build/` для изоляции

### 5.3. Решение: Shared Build Cache

**Bazel for worktrees** — паттерн для shared disk cache:

```bash
# ~/.bazelrc (глобально для всех worktree)
build --disk_cache=~/.cache/bazel/shared-disk
common --repo_contents_cache=~/.cache/bazel/shared-repos
```

**Для Gradle (MagicPaper):**
```properties
# ~/.gradle/gradle.properties
org.gradle.caching=true
# Все worktree используют один кэш
```

**Результат:** 70–95% hit rate для cross-worktree сборок при разных SHA.

### 5.4. Решение: File Locking вместо Worktree

Для задач, которые **естественно не пересекаются по файлам**, worktree избыточен:

```python
class HybridCoordinator:
    def assign_task(self, task):
        if self._has_file_overlap(task.files):
            # Пересечение → нужен worktree
            return self._create_worktree(task)
        else:
            # Нет пересечения → достаточно файловой блокировки
            return self._create_locked_context(task)
```

**File Locking:**
- Каждый агент резервирует файлы перед редактированием
- Другие агенты ждут или выбирают другие файлы
- Работает для naturally file-disjoint задач

**Когда использовать:**
- 2–3 агента с чёткими границами
- Простота важнее полного параллелизма
- Избегание merge-конфликтов

### 5.5. Решение: Sparse Checkout + Worktree

Для монорепо можно комбинировать worktree со **sparse checkout**:

```bash
# Создаём worktree только с нужными директориями
git worktree add --sparse ../agent-auth feature/auth
cd ../agent-auth
git sparse-checkout set src/auth/ shared/types/ tests/auth/
```

**Экономия:** вместо 2 ГБ копируем только 200 МБ нужных файлов.

---

## 6. Применение в MagicPaper/pi

### 5.1. Встроенные механизмы pi

| Механизм | Назначение |
|----------|------------|
| `session_create` | Создать дочернюю сессию (специалиста) с собственной задачей |
| `session_send` | Передать контекст между сессиями по разрешённому маршруту |
| `session_wait` | Дождаться завершения детей |
| `session_results_integrate` | Объединить CODE-результаты в Git-копии с проверками |
| `session_result_review` | Принять/отклонить результат ребёнка с обоснованием |
| `failurePolicy: ISOLATE` | Ошибка одного ребёнка не отменяет других |
| `failurePolicy: CANCEL_SIBLINGS` | Остановка всех при провале одного |

### 5.2. Worktree-стратегия MagicPaper

Из `SESSION-PLANNING-RULES.md`:
- `prepare()` создаёт интеграционную feature-ветку через механизм Git worktree
- `stage()` создаёт отдельную ветку и worktree для потока изменений
- Имена `codex/magicpaper/<назначение>-<хеш>` не конфликтуют
- Detached worktree открываются без изменения
- Перенос в исходную папку сохраняет манифест и проверяет каждый файл

### 5.3. Рекомендуемый workflow для MagicPaper

```
1. Родитель (coordinator):
   - Анализ задачи
   - Декомпозиция по доменам модулей (api/impl)
   - Определение зависимостей между подзадачами

2. Создание дочерних сессий (specialists):
   - Каждая сессия → свой worktree, своя ветка
   - Каждая задача → spec с границами файлов и критериями
   - dependencies отражают порядок (если есть)

3. Параллельное выполнение:
   - failurePolicy: ISOLATE для независимых задач
   - Каждый специалист работает в своём контексте

4. Интеграция:
   - session_wait — дождаться всех
   - session_results_integrate — объединить с проверками
   - session_result_review — принять/отклонить каждый результат

5. Финализация:
   - Осмысленный коммит с SHA и результатами проверок
   - Передача результата родителю через session_send
```

---

## 7. Антипаттерны

| Антипаттерн | Проблема |
|-------------|----------|
| Два агента в одном каталоге | Конфликты файлов, загрязнение контекста |
| Один агент делает всё | Контекстное окно переполняется, качество падает |
| Агент рецензирует свою работу | Нет свежих глаз, пропускает ошибки |
| Параллелизация без спецификации | Scope creep, дублирование, семантические противоречия |
| Merge без интеграционных тестов | Компилируется, но ломается в runtime |
| Оптимизация под скорость агента | Человек — узкое место; параллелизация увеличивает нагрузку на ревью |

---

## 8. Практические рекомендации

1. **Начинайте с 2–3 параллельных агентов**, масштабируйте после отработки процесса
2. **Размер задачи — до 200 строк кода** на PR (median из практики Manasight)
3. **Тесты > код**: соотношение тестового и продуктивного кода 1.4:1 и выше
4. **Каждый PR — независимое ревью** в свежем контексте (Implementer/Reviewer)
5. **Итерации ревью**: 1–3 раунда типично, до 10 максимум
6. **Последовательная цепочка** для зависимых задач предпочтительнее параллелизма
7. **Автоматизация ревью** (5 параллельных агентов-рецензентов с фильтрацией по confidence)
8. **Оптимизируйте под человека**: скорость агента не имеет значения, если человек не
   успевает ревьюировать

---

## 9. Источники

- [GitHub Blog: How Squad runs coordinated AI agents](https://github.blog/ai-and-ml/github-copilot/how-squad-runs-coordinated-ai-agents-inside-your-repository/)
- [GitHub Blog: Agentic Workflows](https://github.blog/ai-and-ml/automate-repository-tasks-with-github-agentic-workflows/)
- [MindStudio: Parallel AI Coding Agents With Git Worktrees](https://www.mindstudio.ai/blog/parallel-ai-coding-agents-git-worktrees)
- [MindStudio: Claude Code Agent Teams](https://www.mindstudio.ai/blog/claude-code-agent-teams-parallel-workflows)
- [Augment Code: Multi-Agent AI System for Code Development](https://www.augmentcode.com/guides/multi-agent-ai-system-code-development)
- [Claude Codex: Background Agents with Git Worktree](https://claude-codex.fr/en/agents/background-agents/)
- [DEV Community: How I Run Multiple Coding Agents in Parallel](https://dev.to/mahmood_khordoo_20b3f5980/how-i-run-multiple-coding-agents-in-parallel-using-git-worktrees-342d)
- [DEV Community: 80,000 Lines of Code in 51 Days](https://dev.to/manasightgg/80000-lines-of-code-in-51-days-2a3)
- [Red Hat: Taming the Agent Beast](https://www.redhat.com/ja/blog/taming-agent-beast-monolithic-prompt-modular-agentic-workflow)
- [CodeRabbit: Agentic Code Review vs RAG](https://www.coderabbit.ai/blog/agentic-code-review-vs-rag-multi-repo-analysis)
- [Dave Schumaker: Recycled Worktrees for Large Monorepos](https://daveschumaker.net/2026/03/)
- [workz: Zoxide for Git Worktrees](https://dev.to/rohansx/i-built-workz-the-zoxide-for-git-worktrees-that-finally-fixes-env-nodemodules-hell-in-2026-2dpj)
- [BSWEN: File Locking vs Git Worktrees](https://docs.bswen.com/blog/2026-03-12-prevent-file-conflicts-ai-agents/)
- [GitHub Blog: FSMonitor for Monorepo Performance](https://github.blog/2022-06-29-improve-git-monorepo-performance-with-a-file-system-monitor/)
- [Bazel for Worktrees: Shared Disk Cache](https://gist.github.com/rc-glean/9802ab44c6d505e3cdd451b1b47a1369)
