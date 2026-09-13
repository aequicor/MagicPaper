package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire format v1. Trust decisions are deliberately absent from publisher-controlled data. */
@Serializable
data class SkillPackageManifest(
    val schemaVersion: Int = 1,
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val instructions: String = "SKILL.md",
    val files: List<SkillPackageFile>,
    val dependencies: List<SkillPackageDependency> = emptyList(),
    val permissions: Set<SkillPermission> = emptySet(),
    val origin: SkillPackageOrigin,
    val license: String? = null,
    val compatibility: SkillCompatibility,
)

@Serializable
data class SkillPackageFile(val path: String, val sha256: String, val size: Long)

@Serializable
data class SkillPackageDependency(val id: String, val version: String)

@Serializable
enum class SkillPermission { READ_PROJECT, WRITE_PROJECT, NETWORK, RUN_PROCESS }

@Serializable
/** Transport kind shared by a publisher-declared origin and the separately stored observed source. */
enum class SkillImportKind { LOCAL_DIRECTORY, ZIP, GIT, HTTPS_PACKAGE, READY_TEXT }

@Serializable
data class SkillPackageOrigin(
    val kind: SkillImportKind,
    val location: String? = null,
    val publisher: String? = null,
    val revision: String? = null,
)

@Serializable
data class SkillCompatibility(val minHost: String, val maxHostExclusive: String, val platforms: Set<String>)

/** Catalog checksum covers the exact UTF-8 manifest bytes, which cover every payload file. */
@Serializable
data class SkillCatalogRelease(val manifest: SkillPackageManifest, val manifestSha256: String, val download: String)

@Serializable
data class SkillPackageCatalog(val schemaVersion: Int = 1, val releases: List<SkillCatalogRelease>)

data class SkillPackageHost(val version: String, val platform: String)

object SkillPackageFormat {
    val json = Json { ignoreUnknownKeys = false; isLenient = false; encodeDefaults = true }
    const val MANIFEST = "skill-package.json"
    const val MAX_MANIFEST_BYTES = 256 * 1024
    const val MAX_PAYLOAD_BYTES = 20L * 1024 * 1024
    const val MAX_FILES = 512
    private val idPattern = Regex("[a-z0-9]+([.-][a-z0-9]+)*")
    private val versionPattern = Regex("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)")
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun validHash(value: String) = hashPattern.matches(value)

    fun version(value: String): List<Int> {
        require(versionPattern.matches(value)) { "Invalid stable version: $value" }
        return value.split('.').map { it.toIntOrNull() ?: error("Version overflow") }
    }

    fun compareVersions(a: String, b: String): Int {
        val av = version(a)
        val bv = version(b)
        return av.zip(bv).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
    }

    /** Portable subset: no aliases, percent escapes, drive names, dot segments or Windows devices. */
    fun validPath(path: String): Boolean = path.length in 1..240 && path.split('/').all { part ->
        part.isNotEmpty() && part != "." && part != ".." && !part.endsWith('.') &&
            Regex("[A-Za-z0-9_.-]+").matches(part) &&
            part.substringBefore('.').uppercase() !in setOf("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9")
    }

    fun validate(manifest: SkillPackageManifest, host: SkillPackageHost) {
        require(manifest.schemaVersion == 1) { "Unsupported package schema" }
        require(manifest.id.length <= 128 && idPattern.matches(manifest.id)) { "Invalid id" }
        version(manifest.version)
        require(manifest.name.isNotBlank() && manifest.description.isNotBlank()) { "Missing metadata" }
        val c = manifest.compatibility
        require(compareVersions(c.minHost, c.maxHostExclusive) < 0) { "Empty compatibility range" }
        require(compareVersions(host.version, c.minHost) >= 0 && compareVersions(host.version, c.maxHostExclusive) < 0 && host.platform in c.platforms) { "Incompatible host" }
        require(manifest.files.size in 1..MAX_FILES) { "Invalid file count" }
        require(manifest.files.map { it.path.lowercase() }.toSet().size == manifest.files.size) { "Duplicate path" }
        val paths = manifest.files.map { it.path.lowercase() }.toSet() + MANIFEST
        paths.forEach { path ->
            val parts = path.split('/')
            require((1 until parts.size).none { parts.take(it).joinToString("/") in paths }) { "File is also a parent directory" }
        }
        var size = 0L
        manifest.files.forEach {
            require(validPath(it.path) && it.path.lowercase() != MANIFEST) { "Unsafe path" }
            require(validHash(it.sha256) && it.size in 0..MAX_PAYLOAD_BYTES) { "Invalid file metadata" }
            size += it.size
        }
        require(size <= MAX_PAYLOAD_BYTES) { "Payload too large" }
        require(manifest.files.any { it.path == manifest.instructions && it.size > 0 }) { "Missing instructions" }
        require(manifest.dependencies.map { it.id }.toSet().size == manifest.dependencies.size) { "Duplicate dependency" }
        manifest.dependencies.forEach {
            require(it.id.length <= 128 && idPattern.matches(it.id)) { "Invalid dependency id" }
            version(it.version)
        }
    }

    /** One exact version per id in a complete activation set. No implicit network resolution. */
    fun validateGraph(manifests: Collection<SkillPackageManifest>, host: SkillPackageHost) {
        require(manifests.size <= 512) { "Too many packages" }
        val byId = manifests.associateBy { it.id }
        require(byId.size == manifests.size) { "Multiple versions of one id" }
        manifests.forEach { validate(it, host) }
        val visiting = mutableSetOf<String>()
        val done = mutableSetOf<String>()
        fun visit(id: String) {
            if (id in done) return
            require(visiting.add(id)) { "Cyclic dependency: $id" }
            byId.getValue(id).dependencies.forEach { dep ->
                val target = byId[dep.id]
                require(target != null && target.version == dep.version) { "Missing or incompatible dependency: ${dep.id}" }
                visit(dep.id)
            }
            visiting.remove(id)
            done.add(id)
        }
        byId.keys.forEach(::visit)
    }
}
