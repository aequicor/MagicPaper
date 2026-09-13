package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.security.MessageDigest

/** Input supplied by an importer BEFORE extraction. Links and special entries are never payload. */
data class SkillArchiveEntry(val path: String, val bytes: ByteArray, val regularFile: Boolean = true)

/** JVM reference verifier; never executes instructions, scripts or resource files. */
class SkillPackageValidator(private val host: SkillPackageHost) {
    fun validateCatalog(catalog: SkillPackageCatalog) {
        require(catalog.schemaVersion == 1 && catalog.releases.size <= 512) { "Unsupported catalog" }
        require(catalog.releases.map { "${it.manifest.id}@${it.manifest.version}" }.toSet().size == catalog.releases.size) { "Duplicate catalog release" }
        catalog.releases.forEach {
            SkillPackageFormat.validate(it.manifest, host)
            require(SkillPackageFormat.validHash(it.manifestSha256)) { "Invalid catalog checksum" }
            val uri = java.net.URI(it.download)
            require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) { "Catalog download must use HTTPS without credentials or fragment" }
        }
    }

    fun validateRelease(entries: List<SkillArchiveEntry>, release: SkillCatalogRelease): ValidatedSkillPackage {
        validateCatalog(SkillPackageCatalog(releases = listOf(release)))
        return validate(entries, release.manifestSha256).also {
            require(it.manifest == release.manifest) { "Catalog metadata does not match package" }
        }
    }

    fun validate(entries: List<SkillArchiveEntry>, expectedManifestSha256: String? = null): ValidatedSkillPackage {
        require(entries.size in 2..SkillPackageFormat.MAX_FILES + 1) { "Invalid entry count" }
        require(entries.all { it.regularFile && SkillPackageFormat.validPath(it.path) }) { "Unsafe entry" }
        require(entries.map { it.path.lowercase() }.toSet().size == entries.size) { "Duplicate entry" }
        require(entries.sumOf { it.bytes.size.toLong() } <= SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES) { "Package too large" }
        val manifestBytes = entries.singleOrNull { it.path == SkillPackageFormat.MANIFEST }?.bytes
            ?: error("Missing manifest")
        require(manifestBytes.size <= SkillPackageFormat.MAX_MANIFEST_BYTES) { "Manifest too large" }
        val checksum = sha256(manifestBytes)
        require(expectedManifestSha256 == null || SkillPackageFormat.validHash(expectedManifestSha256) && checksum == expectedManifestSha256) { "Manifest checksum mismatch" }
        val manifestJson = manifestBytes.decodeToString(throwOnInvalidSequence = true)
        val manifest = SkillPackageFormat.json.decodeFromString<SkillPackageManifest>(manifestJson)
        SkillPackageFormat.validate(manifest, host)
        require(entries.map { it.path }.toSet() == manifest.files.map { it.path }.toSet() + SkillPackageFormat.MANIFEST) { "Unlisted or missing files" }
        val byPath = entries.associateBy { it.path }
        manifest.files.forEach {
            val bytes = byPath.getValue(it.path).bytes
            require(bytes.size.toLong() == it.size && sha256(bytes) == it.sha256) { "Corrupt file: ${it.path}" }
        }
        require(byPath.getValue(manifest.instructions).bytes.decodeToString(throwOnInvalidSequence = true).isNotBlank()) { "Empty instructions" }
        return ValidatedSkillPackage(manifestJson, checksum)
    }

    fun validateSet(packages: List<ValidatedSkillPackage>): List<ValidatedSkillPackage> {
        SkillPackageFormat.validateGraph(packages.map { it.manifest }, host)
        return packages.toList()
    }

    /** Read a private staging directory. Importers must not allow concurrent modification of it. */
    fun validateDirectory(root: Path, expectedManifestSha256: String? = null): ValidatedSkillPackage {
        require(Files.isDirectory(root, NOFOLLOW_LINKS)) { "Not a regular directory" }
        val base = root.toRealPath()
        val entries = mutableListOf<SkillArchiveEntry>()
        var total = 0L
        var visited = 0
        Files.walk(base).use { paths ->
            paths.filter { it != base }.forEach { path ->
                require(++visited <= SkillPackageFormat.MAX_FILES * 2) { "Too many filesystem entries" }
                val relative = base.relativize(path).joinToString("/")
                require(SkillPackageFormat.validPath(relative) && !Files.isSymbolicLink(path)) { "Unsafe path" }
                require(path.toRealPath().startsWith(base)) { "Path escapes root" }
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Special file" }
                    require(entries.size < SkillPackageFormat.MAX_FILES + 1) { "Too many entries" }
                    val limit = if (relative == SkillPackageFormat.MANIFEST) SkillPackageFormat.MAX_MANIFEST_BYTES.toLong() else SkillPackageFormat.MAX_PAYLOAD_BYTES
                    val bytes = Files.newByteChannel(path, setOf(READ, NOFOLLOW_LINKS)).use { channel ->
                        require(channel.size() <= limit) { "File too large" }
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteBuffer.allocate(8192)
                        while (channel.read(buffer) != -1) {
                            buffer.flip()
                            require(output.size().toLong() + buffer.remaining() <= limit) { "File grew beyond limit" }
                            output.write(buffer.array(), 0, buffer.remaining())
                            buffer.clear()
                        }
                        output.toByteArray()
                    }
                    total += bytes.size
                    require(total <= SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES) { "Package too large" }
                    entries.add(SkillArchiveEntry(relative, bytes))
                }
            }
        }
        return validate(entries, expectedManifestSha256)
    }

    companion object {
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
