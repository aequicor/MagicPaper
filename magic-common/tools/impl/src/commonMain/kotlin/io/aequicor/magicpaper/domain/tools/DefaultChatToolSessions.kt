package io.aequicor.magicpaper.domain.tools

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.*

/** Receivers are immutable storage/catalog ports. Their feature implementations are wired by the app. */
class DefaultChatToolSessions(
    private val searchEngine: SearchEngine,
    private val documents: suspend () -> List<DocArticle>,
    private val searchDocuments: suspend (String) -> List<DocMatch>,
    private val skills: suspend () -> List<Skill>,
    private val media: MediaGenerationService?,
    override val questions: RuntimeQuestionnaireService,
    private val receipts: ToolReceiptStore,
    private val mediaReceipts: MediaToolReceiptOwner,
    private val readResearchPage: (suspend (String) -> String)? = null,
    private val allowMedia: suspend (ChatSession, MediaKind) -> Boolean = { chat, kind -> chat.mediaTools.enabled(kind) },
) : ChatToolSessions {
    private val json = Json { encodeDefaults = true }
    private val factory = DefaultToolSessionFactory()
    private val questionnaire = DefaultQuestionnaireToolCommands(questions)

    override suspend fun create(session: ChatSession, requestId: String, settings: AppSettings, allowSearch: Boolean): ToolSession {
        require(requestId.isNotBlank())
        questions.start()
        val scope = object : ToolExecutionScope {
            override fun knownSecrets(): Set<String> = emptySet()
            override suspend fun check(context: ToolExecutionContext, historical: Boolean) {
                currentCoroutineContext().ensureActive()
                checkTool(context.ownerSessionId == session.id && context.requestId == requestId) { "Вызов принадлежит другому запросу" }
            }
        }
        val authorization = object : ToolExecutionAuthorization {
            override suspend fun authorizeTool(context: ToolExecutionContext, definition: ToolDefinition) {
                scope.check(context)
                checkTool(definition.allowed(context) && !definition.native) { "Инструмент недоступен в чате" }
                if (definition.id == "web.search") checkTool(allowSearch) { "Для этого запроса используются только добавленные источники" }
                mediaKind(definition.id)?.let { checkTool(allowMedia(session, it)) { "Создание медиа отключено" } }
            }
            override suspend fun authorizeCommand(context: ToolExecutionContext, definition: ToolDefinition, arguments: JsonObject) = authorizeTool(context, definition)
            override suspend fun authorizeReceipt(context: ToolExecutionContext, definition: ToolDefinition) = authorizeTool(context, definition)
        }
        val mediaCommands = DefaultMediaToolCommands(media,
            allowed = { _, kind -> allowMedia(session, kind) }, checkScope = { scope.check(it) },
            authorizeTool = authorization::authorizeTool, receiptOwner = mediaReceipts)
        val context = mediaCommands.prepareContext(ToolExecutionContext(
            projectId = "chat", ownerSessionId = session.id, sessionId = session.id, requestId = requestId,
            role = ToolRole.CHAT, mode = CodingInteractionMode.RESEARCH,
            usageScope = UsageScope("chat:${session.id}", session.researchParentId?.let { "chat:$it" })), session.mediaTools)
        val commands = buildList {
            if (allowSearch) add(JsonToolCommand(ToolCatalog.get("web.search")) { _, _, args ->
                val query = args.getValue("query").jsonPrimitive.content.trim()
                requireTool(query.isNotBlank()) { "Укажите поисковый запрос" }
                val hits = searchEngine.search(query, settings, limit = 5)
                val checked = ResearchSourceAccess(readResearchPage).check(hits.mapNotNull { hit ->
                    researchUrl(hit.url)?.let { ResearchResource(it, hit.title, it) }
                })
                buildJsonObject {
                    put("sources", json.encodeToJsonElement(checked.readableSources().map { SearchHit(it.title, it.url) }))
                    putJsonArray("pages") { checked.readableSources().forEach { source -> add(buildJsonObject {
                        put("url", source.url); put("title", source.title); put("text", source.readableText.orEmpty())
                    }) } }
                    put("unavailable", checked.unavailableSourceContext())
                }
            })
            add(JsonToolCommand(ToolCatalog.get("questionnaire"), questionnaire::ask))
            context.mediaCapabilities.forEach { kind ->
                val id = if (kind == MediaKind.IMAGE) "image.generate" else "video.generate"
                add(JsonToolCommand(ToolCatalog.get(id)) { ctx, operation, args -> mediaCommands.generate(ctx, operation, id, args, researchChat = true) })
            }
            add(JsonToolCommand(contentTool("docs.search", "Найти документацию MagicPaper по запросу", "query")) { _, _, args ->
                val query = args.getValue("query").jsonPrimitive.content.trim()
                requireTool(query.isNotBlank()) { "Укажите поисковый запрос" }
                buildJsonArray { searchDocuments(query).take(8).forEach { match -> add(buildJsonObject {
                    put("id", match.article.id); put("title", match.article.title); put("body", match.article.body)
                }) } }
            })
            add(JsonToolCommand(contentTool("docs.read", "Прочитать статью документации MagicPaper по её id", "id")) { _, _, args ->
                val article = documents().firstOrNull { it.id == args.getValue("id").jsonPrimitive.content }
                requireTool(article != null) { "Статья не найдена" }
                buildJsonObject { put("id", article.id); put("title", article.title); put("body", article.body) }
            })
            add(JsonToolCommand(contentTool("skills.list", "Список включённых навыков и их назначений")) { _, _, _ ->
                buildJsonArray { skills().filter { it.enabled }.forEach { skill -> add(buildJsonObject {
                    put("id", skill.id); put("name", skill.name); put("description", skill.description)
                }) } }
            })
            add(JsonToolCommand(contentTool("skills.read", "Прочитать инструкции включённого навыка по его id", "id")) { _, _, args ->
                val skill = skills().firstOrNull { it.enabled && it.id == args.getValue("id").jsonPrimitive.content }
                requireTool(skill != null) { "Навык не найден или отключён" }
                buildJsonObject { put("id", skill.id); put("name", skill.name); put("instructions", skill.instructions) }
            })
        }
        val recovery = object : ToolExecutionRecovery {
            override suspend fun reconcile(context: ToolExecutionContext, receipt: ToolReceipt) = mediaCommands.reconcile(receipt)
            override suspend fun questionnaire(context: ToolExecutionContext, receipt: ToolReceipt): JsonElement =
                questionnaire.ask(context, receipt.operationId, receipt.arguments)
            override suspend fun unknown(context: ToolExecutionContext, receipt: ToolReceipt) = Unit // Receipt is the durable, observable outcome.
        }
        return factory.create(context, commands, receipts, scope, authorization, recovery, mediaReceipts.lock)
    }

    private fun contentTool(id: String, description: String, argument: String? = null) = ToolDefinition(
        id, description, buildJsonObject {
            put("type", "object"); put("additionalProperties", false)
            putJsonObject("properties") { argument?.let { putJsonObject(it) { put("type", "string") } } }
            if (argument != null) putJsonArray("required") { add(argument) }
        }, ToolCategory.READ, roles = setOf(ToolRole.CHAT))
    private fun mediaKind(id: String): MediaKind? = when (id) { "image.generate" -> MediaKind.IMAGE; "video.generate" -> MediaKind.VIDEO; else -> null }
}
