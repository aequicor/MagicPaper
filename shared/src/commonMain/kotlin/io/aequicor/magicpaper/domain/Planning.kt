package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.util.TextSimilarity
import kotlinx.serialization.Serializable

/** Статус мэилстоуна плана. */
@Serializable
enum class MilestoneStatus {
    /** Ещё не выполнялся. */
    PENDING,

    /** Выполняется прямо сейчас. */
    ACTIVE,

    /** Выполнен и прошёл проверку достижимости. */
    DONE,

    /** Не выполнен (сбой агента или проверка не подтвердила результат). */
    FAILED,

    /** Пропущен пользователем — считается завершённым без выполнения. */
    SKIPPED,
}

/**
 * Мэилстоун плана: шаг с закреплённым «оптимальным агентом» (профилем
 * подключения и моделью из его избранного), отчётом выполнения и вердиктом
 * проверки достижимости. dependsOn рисует график: шаги без взаимных
 * зависимостей — параллельные ветви, зависимые ждут своих предшественников.
 */
@Serializable
data class Milestone(
    val id: String,
    val title: String,
    val description: String = "",
    val status: MilestoneStatus = MilestoneStatus.PENDING,
    /** Идентификатор профиля подключения — закреплённый агент мэилстоуна. */
    val agentProfileId: String = "",
    /** Избранная модель закреплённого агента; пусто — дефолтная модель профиля. */
    val agentModelId: String = "",
    val assignment: StageAssignment? = null,
    val assessment: StageAssessment = StageAssessment(),
    val acceptance: String = "",
    val attempts: List<StageAttempt> = emptyList(),
    /** Идентификаторы шагов-предшественников: шаг ждёт их завершения. */
    val dependsOn: List<String> = emptyList(),
    /** Отчёт агента о выполнении (итоговый текст прогона). */
    val report: String = "",
    /** Вердикт проверки достижимости цели мэилстоуна. */
    val checkNote: String = "",
    val updatedAt: Long = 0,
) {
    /** Завершён ли мэилстоун (выполнен или осознанно пропущен). */
    val completed: Boolean get() = status == MilestoneStatus.DONE || status == MilestoneStatus.SKIPPED
}

/** Статус плана в целом. */
@Serializable
enum class PlanStatus {
    /** Составлен, ещё не запускался. */
    DRAFT,

    /** Выполняется прямо сейчас. */
    RUNNING,

    /** Все мэилстоуны завершены и проверены. */
    DONE,

    /** Остановлен: один из мэилстоунов не прошёл проверку. */
    FAILED,

    /** Остановлен пользователем. */
    STOPPED,
}

/**
 * Визуальный план кодинг-задачи: цель и цепочка мэилстоунов.
 * Порядок в списке — порядок выполнения (линейный график).
 */
@Serializable
data class Plan(
    val id: String,
    val projectId: String,
    val goal: String,
    val milestones: List<Milestone> = emptyList(),
    val plannerSelection: ModelSelection? = null,
    val searchProvider: SearchProvider = SearchProvider.AUTO,
    val wizardStep: PlanningStep? = null,
    val parentSessionId: String = "",
    val sharedWorkspace: Boolean = false,
    val confirmedRevision: Long? = null,
    val versions: List<PlanVersion> = emptyList(),
    val deliveries: List<PlanDelivery> = emptyList(),
    val pendingRequest: String = "",
    val requestId: String = "",
    val coordination: List<CoordinationRecord> = emptyList(),

    val status: PlanStatus = PlanStatus.DRAFT,
    /**
     * Кодинг-сессия выполнения плана: у плана своя нить диалога с агентом,
     * чтобы шаги делили контекст проекта и журнал был отделён от ручных сессий.
     */
    val sessionId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val schemaVersion: Int = 2,
    val revision: Long = 0,
    val tree: List<DecisionNode> = emptyList(),
    val dialogue: List<PlanningMessage> = emptyList(),
    val priorities: PlanningPriorities = PlanningPriorities(),
    val intent: ExecutionIntent = ExecutionIntent.STOP,
    val phase: ExecutionPhase = ExecutionPhase.IDLE,
    val parallelism: Int = 2,
    val runId: String = "",
    val workspace: PlanWorkspace? = null,
    val finalAttempt: StageAttempt? = null,
    val issue: PlanningIssue? = null,
    val journal: List<PlanJournalEntry> = emptyList(),
    /** Transport failures outside a stage attempt (preflight and workspace preparation). */
    val transportRetries: Int = 0,
) {
    val selectedMilestones: List<Milestone> get() = if (tree.isEmpty()) milestones else {
        val selected = DecisionCompiler.compile(this).stageIds.toSet()
        milestones.filter { it.id in selected }
    }
    val doneCount: Int get() = selectedMilestones.count { it.completed }

    /** Прогресс 0..1 для индикатора. */
    val progress: Float get() = if (selectedMilestones.isEmpty()) 0f else doneCount.toFloat() / selectedMilestones.size

    /** Ближайший мэилстоун, требующий выполнения (включая повтор после сбоя). */
    val nextPending: Milestone? get() = milestones.firstOrNull {
        (it.status == MilestoneStatus.PENDING || it.status == MilestoneStatus.FAILED) &&
            it.dependsOn.all { dep -> milestones.firstOrNull { m -> m.id == dep }?.completed == true }
    }

    /** Незавершённые шаги с проваленным непосредственным предшественником: график встал. */
    val blockedPending: List<Milestone> get() = milestones.filter { m ->
        !m.completed && m.dependsOn.any { dep ->
            milestones.firstOrNull { it.id == dep }?.status == MilestoneStatus.FAILED
        }
    }

    /** Удовлетворены ли шаги-предшественники (или их не было). */
    fun depsSatisfied(milestone: Milestone): Boolean = milestone.dependsOn.all { dep ->
        milestones.firstOrNull { it.id == dep }?.completed == true
    }
}

/** Происхождение досье модели. */
@Serializable
enum class DossierSource {
    /** Описание заполнил человек. */
    USER,

    /** Найдено один раз в публичных источниках. */
    WEB,

    /** Черновик по имени модели (без сети/модели) — требует правки человеком. */
    HEURISTIC,
}

/**
 * Досье модели: в какой области она хороша и насколько сильна —
 * для сравнения моделей и выбора оптимального агента на мэилстоун.
 */
@Serializable
data class ModelDossier(
    val id: String,
    /** Профиль подключения, к которому относится досье (1:1). */
    val profileId: String,
    val modelId: String = "",
    val assessment: StageAssessment = StageAssessment(),
    /** Свободное описание сильных сторон модели. */
    val strengths: String = "",
    val limitations: String = "",
    /** Оценка силы/универсальности 0..5; 0 = не оценивалась. */
    val rating: Int = 0,
    val source: DossierSource = DossierSource.USER,
    /** Публичные источники (URL), если досье найдено в сети. */
    val references: List<String> = emptyList(),
    /** Пояснение о способе получения (например, «без модели»). */
    val note: String = "",
    val updatedAt: Long = 0,
)

/** Хранилище планов и досье моделей. */
interface PlanningRepository {
    suspend fun plans(): List<Plan>

    /** Lookup by plan ID. Legacy project IDs are accepted only when unambiguous. */
    suspend fun planFor(projectId: String): Plan?

    /** Upsert by plan ID; other plans in the project are preserved. */
    suspend fun save(plan: Plan)

    suspend fun deletePlan(projectId: String)

    suspend fun dossiers(): List<ModelDossier>
    suspend fun saveDossier(dossier: ModelDossier)
    suspend fun wipe()
}

/**
 * Черновик мэилстоуна от планировщика (до закрепления агента).
 * [depends] — номера предшествующих шагов этого же черновика (считаются с 1,
 * как их нумерует планировщик в промпте); пустой список — шаг стартует сразу,
 * ветви без взаимных зависимостей считаются параллельными.
 */
@Serializable
data class MilestoneDraft(
    val title: String = "",
    val description: String = "",
    /** Подсказка модели: имя агента для этого шага (пусто — подобрать). */
    val agent: String = "",
    /** Подсказка модели: имя оптимальной агента (ищется среди избранных моделей профиля). */
    val model: String = "",
    val depends: List<Int> = emptyList(),
)

/** Черновик плана: цепочка шагов и пометка о способе создания. */
data class PlanDraft(
    val milestones: List<MilestoneDraft>,
    /** Например, «план составлен без модели — подправьте шаги». */
    val note: String = "",
)

/**
 * Подбор оптимального агента: косинусная близость текста мэилстоуна
 * к досье моделей плюс вес оценки. Чистая функция — тестируется без сети.
 */
object AgentMatcher {
    private const val SIMILARITY_WEIGHT = 0.7
    private const val RATING_WEIGHT = 0.3

    /** Общая длина префикса токена: грубый стемминг спасает от падежей. */
    private const val STEM_PREFIX = 6

    /** Лучший кандидат для мэилстоуна; null, если настроенных нет. */
    fun best(milestoneText: String, dossiers: List<ModelDossier>, candidates: List<LlmProfile>): LlmProfile? =
        candidates.filter { it.configured }.maxByOrNull { score(it, milestoneText, dossiers) }

    /** Оценка пригодности кандидата: 0..1. */
    fun score(profile: LlmProfile, milestoneText: String, dossiers: List<ModelDossier>): Double {
        val dossier = dossiers.forModel(profile, profile.modelId)
        val similarity = if (dossier != null && dossier.strengths.isNotBlank()) {
            stemmedScore(milestoneText, dossier.strengths)
        } else {
            0.0
        }
        val rating = (dossier?.rating ?: 0).coerceIn(0, 5) / 5.0
        return similarity * SIMILARITY_WEIGHT + rating * RATING_WEIGHT
    }

    /**
     * Косинус по префиксам токенов: «документацию» и «документация» считаются
     * одним словом. Русский без стеммера теряет падежные окончания — префикс
     * длины [STEM_PREFIX] это компенсирует без внешних зависимостей.
     */
    private fun stemmedScore(a: String, b: String): Double {
        val aTokens = TextSimilarity.tokenize(a).map { it.take(STEM_PREFIX) }
        val bTokens = TextSimilarity.tokenize(b).map { it.take(STEM_PREFIX) }
        return TextSimilarity.cosine(aTokens, bTokens)
    }
}

@Serializable
enum class PlanningStep { GOAL, CLARIFY, REVIEW, STATUS }

val Plan.currentPlanningStep: PlanningStep
    get() = if (intent != ExecutionIntent.STOP || phase != ExecutionPhase.IDLE || milestones.any { it.attempts.isNotEmpty() }) PlanningStep.STATUS
        else wizardStep ?: if (milestones.isNotEmpty()) PlanningStep.REVIEW else PlanningStep.CLARIFY
