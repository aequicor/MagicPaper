package io.aequicor.magicpaper.domain

import kotlinx.coroutines.ensureActive

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Вердикт проверки достижимости мэилстоуна. */
data class Verdict(val passed: Boolean, val note: String, val issue: PlanningIssue? = null)
data class AcceptanceReview(val findings: List<AcceptanceFinding>, val issue: PlanningIssue? = null)

/**
 * Проверяющий достижимости: по отчёту агента и критерию мэилстоуна
 * выносит вердикт — достигнут ли проверяемый результат (порт для исполнителя).
 */


/**
 * Проверка достижимости моделью: судья читает критерий и отчёт агента.
 * Без модели честно ставит прочерк и советует проверить руками;
 * недоступная проверка не принимает результат и не запускает исправление кода.
 */
