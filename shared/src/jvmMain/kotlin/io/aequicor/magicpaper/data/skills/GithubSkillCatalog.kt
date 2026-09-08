package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.net.URI

/** Source-only anonymous requests; no dependency on coding, chats, profiles or experience. */
class GithubSkillCatalog internal constructor(private val fetch: (String) -> SkillPublicDownload.Download) {
    constructor() : this(SkillPublicDownload(ORIGINS)::fetch)

    data class Source(val name: String, val url: String, val description: String)
    data class Discovery(val repository: String, val commit: String, internal val files: List<SkillArchiveEntry>) {
        val packages: List<String> get() = files.filter { it.path == "SKILL.md" || it.path.endsWith("/SKILL.md") }.map { it.path }.sorted()
    }
    data class Preview(val manifest: SkillPackageManifest, val checksum: String, val original: String,
        val licenseEvidence: String?, internal val imported: ValidatedSkillImport) {
        val source: SkillObservedSource get() = imported.source
    }

    fun search(query: String): List<Source> = SOURCES.filter { (it.name + " " + it.description + " " + it.url).contains(query.trim(), true) }

    /** Repository URL or /tree|blob/<single ref>/<path>; slash-containing refs require full SHA. */
    suspend fun discover(link: String, networkConfirmed: Boolean): Discovery = withContext(Dispatchers.IO) {
        require(networkConfirmed) { "Подтвердите анонимную загрузку с GitHub" }
        val uri = URI(link.trim())
        require(uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null) { "Нужна публичная HTTPS-ссылка github.com без токенов и параметров" }
        val parts = uri.path.trim('/').split('/')
        require(parts.size == 2 || parts.size >= 4 && parts[2] in setOf("tree", "blob")) { "Укажите репозиторий или ссылку tree/blob" }
        require(parts.all { it.matches(Regex("[A-Za-z0-9_.-]+")) && it !in setOf(".", "..") })
        val repo = parts.take(2).joinToString("/").removeSuffix(".git").lowercase()
        val ref = if (parts.size > 2) parts[3] else "HEAD"
        currentCoroutineContext().ensureActive()
        val sha = if (ref.matches(Regex("[0-9a-f]{40}"))) ref else {
            val result = fetch("https://api.github.com/repos/$repo/commits/$ref")
            Json.parseToJsonElement(result.bytes.decodeToString()).jsonObject.getValue("sha").jsonPrimitive.content
        }
        require(sha.matches(Regex("[0-9a-f]{40}"))) { "GitHub не вернул полный commit SHA" }
        currentCoroutineContext().ensureActive()
        val response = fetch("https://codeload.github.com/$repo/zip/$sha")
        currentCoroutineContext().ensureActive()
        val files = try { SkillPackageImporter.unzip(response.bytes, stripRoot = true) }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("Архив отклонён: недопустимые пути, ссылки, типы записей или превышение лимитов. Выберите другой фиксированный релиз; проверки не отключаются.", e) }
        Discovery("https://github.com/$repo", sha, files).also { require(it.packages.isNotEmpty()) { "SKILL.md не найден" } }
    }

    fun preview(discovery: Discovery, path: String): Preview {
        require(path in discovery.packages)
        val prefix = path.removeSuffix("SKILL.md")
        val selected = discovery.files.filter { it.path.startsWith(prefix) }.map { it.copy(path = it.path.removePrefix(prefix)) }.toMutableList()
        val original = selected.single { it.path == "SKILL.md" }.bytes.decodeToString(throwOnInvalidSequence = true)
        val license = discovery.files.firstOrNull { it.path in listOf("LICENSE", "LICENSE.md", "LICENSE.txt", "COPYING") }
        val existing = selected.firstOrNull { it.path == SkillPackageFormat.MANIFEST }
        // Preserve evidence, not an inferred SPDX declaration. Review must establish applicability.
        // An authored manifest fixes the payload. Show external license evidence in
        // the preview without injecting an undeclared file into that package.
        if (existing == null && prefix.isNotEmpty() && license != null && selected.none { it.path == "UPSTREAM-LICENSE.txt" }) selected += SkillArchiveEntry("UPSTREAM-LICENSE.txt", license.bytes.copyOf())
        val source = SkillObservedSource(SkillImportKind.GIT, discovery.repository + "/tree/" + discovery.commit + "/" + prefix, discovery.commit)
        val manifest = if (existing != null) SkillPackageFormat.json.decodeFromString<SkillPackageManifest>(existing.bytes.decodeToString()) else {
            val identity = SkillPackageValidator.sha256((discovery.repository + "/" + prefix).encodeToByteArray()).take(24)
            // Deterministic host SemVer adaptation, not an upstream version claim.
            val version = discovery.commit.take(21).chunked(7).joinToString(".") { it.toInt(16).toString() }
            SkillPackageManifest(id = "github.$identity", version = version,
                name = prefix.trimEnd('/').substringAfterLast('/').ifEmpty { discovery.repository.substringAfterLast('/') },
                description = "Внешний SKILL.md: ${discovery.repository}/$path. Назначение и ограничения смотрите в исходном тексте.",
                files = selected.map { SkillPackageFile(it.path, SkillPackageValidator.sha256(it.bytes), it.bytes.size.toLong()) },
                origin = SkillPackageOrigin(SkillImportKind.GIT, discovery.repository, revision = discovery.commit),
                compatibility = SkillCompatibility(minHost = "1.0.0", maxHostExclusive = "2.0.0", platforms = setOf("desktop")))
        }
        if (existing == null) selected += SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray())
        val imported = SkillPackageImporter.validated(HOST, selected, source)
        return Preview(manifest, imported.pkg.checksum, original, license?.bytes?.decodeToString(), imported)
    }

    suspend fun install(repository: LocalSkillRepository, preview: Preview): SkillReleaseSnapshot {
        currentCoroutineContext().ensureActive()
        // Commit is a single existing repository transaction. Never review, bind or opt in here.
        val context = currentCoroutineContext()
        return repository.install(preview.imported, beforeNewInstall = { context.ensureActive() })
    }

    companion object {
        val HOST = SkillPackageHost("1.0.0", "desktop")
        val ORIGINS = setOf("https://github.com", "https://api.github.com", "https://codeload.github.com")
        // Live checked 2026-09-08: 14 SKILL.md at this immutable commit. No fabricated entries.
        val SOURCES = listOf(Source("Superpowers v4.0.0 — obra", "https://github.com/obra/superpowers/tree/95c6e1633630a1462679cf1284b95ec458a27a8e",
            "Разработка: debugging, test-driven-development, review, verification, planning. Выбор из 14 пакетов; лицензия требует review."))
    }
}
