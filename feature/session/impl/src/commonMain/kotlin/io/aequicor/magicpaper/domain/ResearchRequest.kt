package io.aequicor.magicpaper.domain

/** Conservative routing for explicit source tasks; the model still interprets the question.
 * Inspect the user's request, never page bodies, generated notices or the research prompt. */
internal data class ResearchRequest(
    val urls: List<String>,
    val sourceTask: Boolean,
    val asksForSearch: Boolean,
) {
    fun autoSearch(hasHistory: Boolean, hasSources: Boolean, hasAttachments: Boolean): Boolean =
        !sourceTask && (asksForSearch || (!hasHistory && !hasSources && !hasAttachments))

    fun sources(available: List<ResearchResource>, attachments: List<Attachment>, includeNewLinks: Boolean = true): List<ResearchResource> {
        val linked = urls.mapNotNull { url -> available.firstOrNull { it.url == url }
            ?: if (includeNewLinks) ResearchResource("url:$url", url, url) else null }
        val files = attachments.map { ResearchResource(it.id, it.name, attachment = it) }
        return (if (sourceTask && (urls.isNotEmpty() || files.isNotEmpty())) linked + files
            else available + linked + files).distinctBy { it.key }
    }
}

internal fun researchRequest(text: String): ResearchRequest {
    val urls = researchReferences(text, searchResult = true).map { it.url }.distinct()
    val instruction = text.replace(Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE), " ")
        .substringBefore("```").lineSequence().filterNot { it.trimStart().startsWith('>') }.joinToString(" ")
        .take(2000).lowercase()
    val noSearch = requestWords("(без (веб[ -]?)?поиска|не (ищи|искать|ищите)|не (используй|используйте) интернет|только (по|из) (этим|этого|выбранным|загруженным|моим) (источник|материал)[а-яёa-z0-9_]*|do not search|don't search|no web search)")
        .containsMatchIn(instruction)
    val transformation = requestWords("(пересказ[а-яёa-z0-9_]*|перескаж[а-яёa-z0-9_]*|резюм[а-яёa-z0-9_]*|суммариз[а-яёa-z0-9_]*|переведи[а-яёa-z0-9_]*|перевод[а-яёa-z0-9_]*|сократи[а-яёa-z0-9_]*|переформулир[а-яёa-z0-9_]*|выжимк[а-яёa-z0-9_]*|summari[sz][а-яёa-z0-9_]*|summary|recap|translat[а-яёa-z0-9_]*|rewrite|shorten)")
        .containsMatchIn(instruction)
    val searchVerb = requestWords("(найди|найдите|поищи|поищите|ищи|ищите|search|find)").containsMatchIn(instruction)
    val externalTarget = requestWords("(источник[а-яёa-z0-9_]*|исследован[а-яёa-z0-9_]*|публикаци[а-яёa-z0-9_]*|другие статьи|новые статьи|в интернете|в сети|sources|studies|papers|the web|online)")
        .containsMatchIn(instruction)
    val asksForSearch = !noSearch && searchVerb && (externalTarget || (!transformation && urls.isEmpty()))
    return ResearchRequest(urls, noSearch || (!asksForSearch && (transformation || urls.isNotEmpty())), asksForSearch)
}

private fun requestWords(pattern: String): Regex =
    Regex("(?:^|[^а-яёa-z0-9_])(?:$pattern)(?=$|[^а-яёa-z0-9_])")
