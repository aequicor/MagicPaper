package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.Serializable

@Serializable
enum class ExperienceScenario(val label: String) {
    SUMMARY("Краткое резюме"), CHECKLIST("Список шагов"), CLARIFICATION("Уточнение неполного запроса")
}
@Serializable
enum class ExperienceFeature(val label: String) {
    TOO_LONG("Ответ слишком длинный"), MISSING_STEP("Пропущен шаг"),
    WRONG_FORMAT("Неверный формат"), MISSING_CONTEXT("Не хватило контекста")
}
@Serializable
enum class ExperienceTemplate { CONCISE, STRUCTURED }

/** Public, application-owned vocabulary. No string supplied by a user/package/model is a template. */
object StrictExperienceCatalog {
    const val SUITE_VERSION = 1
    data class Case(val prompt: String, val expected: String, val heldOut: Boolean)
    fun id(scenario: ExperienceScenario) = "local.learned.${scenario.name.lowercase()}"
    fun instruction(scenario: ExperienceScenario, template: ExperienceTemplate): String {
        val task = when (scenario) {
            ExperienceScenario.SUMMARY -> "Составляй краткое резюме только по предоставленным фактам."
            ExperienceScenario.CHECKLIST -> "Превращай задачу в упорядоченный список шагов."
            ExperienceScenario.CLARIFICATION -> "При нехватке данных сначала уточняй недостающий контекст."
        }
        return task + when (template) {
            ExperienceTemplate.CONCISE -> " Соблюдай запрошенный формат. Не выдумывай данные."
            ExperienceTemplate.STRUCTURED -> " Проверь полноту входных данных, выдели необходимые пункты, затем проверь результат и точное соблюдение запрошенного формата. Не выдумывай данные."
        }
    }
    fun cases(scenario: ExperienceScenario): List<Case> {
        val prefix = "Синтетический сценарий: ${scenario.label}. "
        return listOf(
            Case(prefix + "Факты: ALPHA, BETA. Верни только ALPHA BETA.", "ALPHA BETA", false),
            Case(prefix + "Есть только ALPHA. Верни только ALPHA.", "ALPHA", false),
            Case(prefix + "Данных нет. Верни только UNKNOWN.", "UNKNOWN", false),
            Case(prefix + "Два шага ALPHA, BETA. Верни ровно две строки: 1. ALPHA и 2. BETA.", "1. ALPHA\n2. BETA", false),
            Case(prefix + "Факты: BETA, ALPHA. Верни их в исходном порядке через пробел.", "BETA ALPHA", true),
            Case(prefix + "Первый пункт неизвестен, второй BETA. Верни только известный пункт.", "BETA", true),
            Case(prefix + "Входных данных недостаточно для ответа. Верни только UNKNOWN.", "UNKNOWN", true),
        )
    }
    fun manifest(scenario: ExperienceScenario, template: ExperienceTemplate, version: String): SkillPackageManifest {
        val bytes = instruction(scenario, template).encodeToByteArray()
        return SkillPackageManifest(id = id(scenario), version = version, name = scenario.label,
            description = "Когда требуется: ${scenario.label}",
            files = listOf(SkillPackageFile("SKILL.md", SkillPackageValidator.sha256(bytes), bytes.size.toLong())),
            origin = SkillPackageOrigin(SkillImportKind.LOCAL_DIRECTORY, "strict-local-experience-v2", "MagicPaper"),
            compatibility = SkillCompatibility("1.0.0", "2.0.0", setOf("desktop")))
    }
}
