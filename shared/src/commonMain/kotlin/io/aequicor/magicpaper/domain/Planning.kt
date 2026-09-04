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
 * подключения), отчётом выполнения и вердиктом проверки достижимости.
 */
@Serializable
data class Milestone(
    val id: String,
    val title: String,
    val description: String = "",
    val status: MilestoneStatus = MilestoneStatus.PENDING,
    /** Идентификатор профиля подключения — закреплённый агент мэилстоуна. */
    val agentProfileId: String = "",
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
    val status: PlanStatus = PlanStatus.DRAFT,
    /**
     * Кодинг-сессия выполнения плана: у плана своя нить диалога с агентом,
     * чтобы шаги делили контекст проекта и журнал был отделён от ручных сессий.
     */
    val sessionId: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val doneCount: Int get() = milestones.count { it.completed }

    /** Прогресс 0..1 для индикатора. */
    val progress: Float get() = if (milestones.isEmpty()) 0f else doneCount.toFloat() / milestones.size

    /** Ближайший мэилстоун, требующий выполнения (включая повтор после сбоя). */
    val nextPending: Milestone? get() = milestones.firstOrNull {
        it.status == MilestoneStatus.PENDING || it.status == MilestoneStatus.FAILED
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
    /** Свободное описание сильных сторон модели. */
    val strengths: String = "",
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

    /** План проекта (на проект — один актуальный план). */
    suspend fun planFor(projectId: String): Plan?

    /** Сохраняет план, заменяя прежний план этого проекта. */
    suspend fun save(plan: Plan)

    suspend fun deletePlan(projectId: String)

    suspend fun dossiers(): List<ModelDossier>
    suspend fun saveDossier(dossier: ModelDossier)
    suspend fun wipe()
}

/** Черновик мэилстоуна от планировщика (до закрепления агента). */
@Serializable
data class MilestoneDraft(
    val title: String = "",
    val description: String = "",
    /** Подсказка модели: имя агента для этого шага (пусто — подобрать). */
    val agent: String = "",
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
        val dossier = dossiers.firstOrNull { it.profileId == profile.id }
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
