package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.*
import kotlinx.serialization.descriptors.*
import kotlinx.serialization.json.*

@Serializable data class PlanToolProposal(val reply: String, val tree: List<DecisionNode> = emptyList(),
    val milestones: List<PlanToolMilestone> = emptyList(), val questions: List<PlanningQuestion> = emptyList(),
    val questionStageIds: List<String> = emptyList(), val isolatedWorkspace: Boolean? = null)
/** Only editable fields cross the model boundary; attempts and runtime checkpoints never enter a tool schema. */
@Serializable data class PlanToolAssignment(val profileId: String, val modelId: String,
    val effort: EffortSelection = EffortSelection.Default, val explanation: String = "")
@Serializable data class PlanToolMilestone(val id: String, val title: String, val description: String = "",
    val acceptance: String, val dependsOn: List<String> = emptyList(), val assessment: StageAssessment = StageAssessment(),
    val assignment: PlanToolAssignment? = null, val durationHours: Double? = null, val complexityPoints: Double? = null,
    val continuationOf: String? = null, val acceptanceCriteria: List<AcceptanceCriterion> = emptyList())
@Serializable data class ToolMessage(val message: String, val revision: Long? = null, val requiresConfirmation: Boolean = true)
@Serializable data class ToolRecalculate(val nodeId: String, val revision: Long)
@Serializable data class ToolPlanControl(val action: String, val revision: Long, val proposalId: String? = null)
@Serializable data class ToolStagePause(val stageIds: List<String>, val reason: String)
@Serializable data class ToolStageSend(val stageId: String, val message: String)
@Serializable data class ToolStageResolve(val action: CoordinatorResultAction, val reason: String = "")
@Serializable data class ToolSessionManage(val kind: SessionCommandKind, val stageId: String, val name: String = "")
@Serializable data class ToolScheduleManage(val commands: List<ScheduleCommand>)
@Serializable data class ToolQuestions(val questions: List<PlanningQuestion>)
@Serializable data class ToolSearch(val query: String)

@OptIn(ExperimentalSerializationApi::class)
fun toolSchema(descriptor: SerialDescriptor): JsonObject {
    val schema = buildJsonObject {
    when (descriptor.kind) {
        PrimitiveKind.STRING, PrimitiveKind.CHAR -> put("type", "string")
        PrimitiveKind.BOOLEAN -> put("type", "boolean")
        PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> put("type", "integer")
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> put("type", "number")
        SerialKind.ENUM -> { put("type", "string"); put("enum", JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) })) }
        StructureKind.LIST -> { put("type", "array"); put("items", toolSchema(descriptor.getElementDescriptor(0))) }
        StructureKind.MAP -> { put("type", "object"); put("additionalProperties", toolSchema(descriptor.getElementDescriptor(1))) }
        else -> {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") { repeat(descriptor.elementsCount) { put(descriptor.getElementName(it), toolSchema(descriptor.getElementDescriptor(it))) } }
            put("required", JsonArray((0 until descriptor.elementsCount).filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }.map { JsonPrimitive(descriptor.getElementName(it)) }))
        }
    }
    }
    return if (!descriptor.isNullable) schema else buildJsonObject {
        put("anyOf", buildJsonArray { add(schema); add(buildJsonObject { put("type", "null") }) })
    }
}

object ToolCatalog {
    private val all = ToolRole.entries.toSet()
    private val coordinator = setOf(ToolRole.ORCHESTRATOR)
    private val workers = setOf(ToolRole.WORKER, ToolRole.CHAT)
    private val empty = buildJsonObject { put("type", "object"); putJsonObject("properties") {}; put("additionalProperties", false) }
    private inline fun <reified T> app(id: String, description: String, roles: Set<ToolRole> = all,
        mutating: Boolean = false, plan: Boolean = false, category: ToolCategory = ToolCategory.ACTION) =
        ToolDefinition(id, description, toolSchema(serializer<T>().descriptor), category, roles, mutating, needsPlan = plan)
    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition("file.read", "Чтение файла", empty, ToolCategory.READ, native = true),
        ToolDefinition("file.search", "Поиск в проекте", empty, ToolCategory.SEARCH, native = true),
        ToolDefinition("file.list", "Структура проекта", empty, ToolCategory.READ, native = true),
        ToolDefinition("git.inspect", "Просмотр Git", empty, ToolCategory.READ, native = true),
        ToolDefinition("file.edit", "Редактирование файла", empty, ToolCategory.EDIT, workers, true, true),
        ToolDefinition("file.write", "Запись файла", empty, ToolCategory.EDIT, workers, true, true),
        ToolDefinition("shell.exec", "Выполнение команды", empty, ToolCategory.EXEC, workers, true, true),
        ToolDefinition("computer", "Управление компьютером", empty, ToolCategory.ACTION, workers, true, true),
        ToolDefinition("research_check", "Защищённая проверка", empty, ToolCategory.EXEC, workers, native = true),
        ToolDefinition("context.get", "Актуальное состояние проекта, плана и сессий", empty, ToolCategory.READ),
        app<ToolSearch>("web.search", "Поиск источников в интернете", category = ToolCategory.SEARCH),
        app<ToolQuestions>("questionnaire", "Уточнение у пользователя. Ожидает подтверждённых ответов; не выдаёт разрешений"),
        app<PlanToolProposal>("plan.propose", "Передать проект плана с объяснением, деревом решений и этапами. Не запускает исполнителей", setOf(ToolRole.PLANNER), true, true),
        app<ToolMessage>("plan.refine", "Разработать или доработать план по явному поручению пользователя", coordinator, true, true),
        app<ToolRecalculate>("plan.recalculate", "Пересчитать часть плана по идентификатору узла", coordinator, true, true),
        app<ToolPlanControl>("plan.control", "Управление планом: pause, stop, resume, retry, confirm. Подтверждение требует действия пользователя", coordinator, true, true),
        app<ToolStagePause>("stage.pause", "Приостановить только затронутые этапы до уточнения требований", coordinator, true, true),
        app<ToolStageSend>("stage.send", "Передать задание или информацию этапу своего плана", coordinator, true, true),
        app<ToolStageResolve>("stage.resolve", "Решение по текущему результату исполнителя: VERIFY или CONTINUE с конкретной причиной", coordinator, true, true),
        app<ToolSessionManage>("session.manage", "Создать, переименовать, архивировать или восстановить сессию этапа", coordinator, true, true),
        app<ToolScheduleManage>("schedule.manage", "Создать, изменить или отменить отложенные сообщения", coordinator, true, true),
        app<StageReply>("stage.handoff", "Передать результат RESULT, вопрос QUESTION, блокировку BLOCKED или запрос ожидания WAIT. После успеха завершить ответ; статус этапа определяет приложение", setOf(ToolRole.WORKER), true, true),
    ) + SessionToolCatalog.definitions
    fun get(id: String) = definitions.first { it.id == id }
    fun nativeId(name: String, isExec: Boolean = false): String = when (name.lowercase()) {
        "read", "read_file" -> "file.read"
        "grep", "find", "search" -> "file.search"
        "ls", "list_directory" -> "file.list"
        "planning_git" -> "git.inspect"
        "web_search" -> "web.search"
        "magicpaper_research:research_check" -> "research_check"
        "magicpaper_questionnaire:questionnaire" -> "questionnaire"
        "edit", "apply_patch" -> "file.edit"
        "write", "write_file" -> "file.write"
        "bash", "shell", "exec", "command", "exec_command" -> "shell.exec"
        else -> if (isExec) "shell.exec" else definitions.firstOrNull { name.endsWith(it.wireName) }?.id ?: name
    }
}
