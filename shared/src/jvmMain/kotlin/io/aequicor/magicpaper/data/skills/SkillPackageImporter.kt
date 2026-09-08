package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.serialization.encodeToString
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributeView
import java.util.zip.ZipInputStream

/** Local metadata must have been previewed by the caller; no inferred license or publisher. */
data class SkillLocalMetadata(val id: String, val version: String, val name: String, val description: String, val compatibility: SkillCompatibility)

class SkillPackageImporter internal constructor(
    private val repository: LocalSkillRepository,
    private val host: SkillPackageHost,
    approvedOrigins: Set<String>,
    private val fetch: (String) -> SkillPublicDownload.Download,
) {
    constructor(repository: LocalSkillRepository, host: SkillPackageHost, approvedOrigins: Set<String> = emptySet()) :
        this(repository, host, approvedOrigins, SkillPublicDownload(approvedOrigins)::fetch)
    private val download = SkillPublicDownload(approvedOrigins)

    suspend fun directory(path: Path, metadata: SkillLocalMetadata? = null): SkillReleaseSnapshot {
        val entries = readDirectory(path)
        return repository.install(withManifest(entries, metadata, SkillImportKind.LOCAL_DIRECTORY), SkillObservedSource(SkillImportKind.LOCAL_DIRECTORY, path.toAbsolutePath().normalize().toString()))
    }

    suspend fun zip(path: Path): SkillReleaseSnapshot = repository.install(
        unzip(LocalSkillRepository.readLimited(path, SkillPublicDownload.MAX_DOWNLOAD)),
        SkillObservedSource(SkillImportKind.ZIP, path.toAbsolutePath().normalize().toString()),
    )

    suspend fun https(url: String, manifestSha256: String, networkConfirmed: Boolean): SkillReleaseSnapshot {
        require(networkConfirmed && SkillPackageFormat.validHash(manifestSha256)) { "Confirm network import and exact manifest checksum" }
        download.validateUrl(java.net.URI(url))
        val response = fetch(url)
        return repository.install(unzip(response.bytes), SkillObservedSource(SkillImportKind.HTTPS_PACKAGE, response.finalUrl), manifestSha256)
    }

    suspend fun https(release: SkillCatalogRelease, networkConfirmed: Boolean): SkillReleaseSnapshot {
        require(networkConfirmed) { "Explicit network import required" }
        SkillPackageValidator(host).validateCatalog(SkillPackageCatalog(releases = listOf(release)))
        download.validateUrl(java.net.URI(release.download))
        val response = fetch(release.download)
        val entries = unzip(response.bytes)
        SkillPackageValidator(host).validateRelease(entries, release)
        return repository.install(entries, SkillObservedSource(SkillImportKind.HTTPS_PACKAGE, response.finalUrl), release.manifestSha256)
    }

    /** Public GitHub HTTPS repositories only. No git process, checkout, hooks, credentials or filters.
     * A branch/tag must be resolved and previewed externally; this API accepts only a full commit.
     */
    suspend fun git(repositoryUrl: String, commit: String, networkConfirmed: Boolean, metadata: SkillLocalMetadata? = null): SkillReleaseSnapshot {
        require(networkConfirmed && Regex("[0-9a-f]{40}").matches(commit))
        val uri = java.net.URI(repositoryUrl)
        download.validateUrl(uri)
        require(uri.host == "github.com" && Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(uri.path))
        val repositoryPath = uri.path.removeSuffix(".git")
        val archiveUrl = "https://codeload.github.com$repositoryPath/zip/$commit"
        download.validateUrl(java.net.URI(archiveUrl))
        val response = fetch(archiveUrl)
        val entries = unzip(response.bytes, stripRoot = true)
        return repository.install(withManifest(entries, metadata, SkillImportKind.GIT), SkillObservedSource(SkillImportKind.GIT, "https://github.com$repositoryPath", commit))
    }

    private fun withManifest(entries: List<SkillArchiveEntry>, metadata: SkillLocalMetadata?, kind: SkillImportKind): List<SkillArchiveEntry> {
        if (entries.any { it.path == SkillPackageFormat.MANIFEST }) return entries
        val m = requireNotNull(metadata) { "Preview id, exact version, name, description and compatibility first" }
        val manifest = SkillPackageManifest(id = m.id, version = m.version, name = m.name, description = m.description,
            files = entries.map { SkillPackageFile(it.path, SkillPackageValidator.sha256(it.bytes), it.bytes.size.toLong()) },
            origin = SkillPackageOrigin(kind), compatibility = m.compatibility)
        return entries + SkillArchiveEntry(SkillPackageFormat.MANIFEST, SkillPackageFormat.json.encodeToString(manifest).encodeToByteArray())
    }

    companion object {
        /** Descriptor-relative traversal: fails closed on platforms without SecureDirectoryStream. */
        internal fun readDirectory(root: Path): List<SkillArchiveEntry> {
            require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            if (com.sun.jna.Platform.isMac()) return MacSkillDirectoryReader.read(root)
            val entries = mutableListOf<SkillArchiveEntry>()
            var visited = 0
            var total = 0
            fun read(dir: SecureDirectoryStream<Path>, prefix: String) {
                dir.forEach { path ->
                    require(++visited <= 1024)
                    val name = path.fileName
                    val relative = prefix + name.toString()
                    require(SkillPackageFormat.validPath(relative))
                    val attrs = dir.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
                    if (attrs.isDirectory) dir.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { read(it, "$relative/") }
                    else {
                        require(attrs.isRegularFile && !attrs.isSymbolicLink && entries.size < SkillPackageFormat.MAX_FILES + 1)
                        val limit = if (relative == SkillPackageFormat.MANIFEST) SkillPackageFormat.MAX_MANIFEST_BYTES else SkillPackageFormat.MAX_PAYLOAD_BYTES.toInt()
                        val bytes = dir.newByteChannel(name, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
                            java.nio.channels.Channels.newInputStream(channel).readNBytes(minOf(limit, (SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES - total).toInt()) + 1)
                        }
                        total += bytes.size
                        require(bytes.size <= limit && total <= SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES)
                        entries += SkillArchiveEntry(relative, bytes)
                    }
                }
            }
            val absolute = root.toAbsolutePath().normalize()
            Files.newDirectoryStream(requireNotNull(absolute.parent)).use { parent ->
                require(parent is SecureDirectoryStream<Path>) { "Safe directory import unavailable on this filesystem; use ZIP" }
                parent.newDirectoryStream(absolute.fileName, LinkOption.NOFOLLOW_LINKS).use { read(it, "") }
            }
            return entries
        }

        /** Inspect central directory types before inflating. ZIP64/encryption/special files are unsupported.
         * No archive path is ever extracted into the user tree, nested archives stay inert bytes.
         */
        internal fun unzip(bytes: ByteArray, stripRoot: Boolean = false): List<SkillArchiveEntry> {
            require(bytes.size in 22..SkillPublicDownload.MAX_DOWNLOAD)
            fun u16(p: Int): Int { require(p >= 0 && p + 2 <= bytes.size); return (bytes[p].toInt() and 255) or ((bytes[p + 1].toInt() and 255) shl 8) }
            fun u32(p: Int): Long = u16(p).toLong() or (u16(p + 2).toLong() shl 16)
            val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 65557)).firstOrNull { u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size } ?: error("Missing ZIP directory")
            require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 8) == u16(end + 10))
            val count = u16(end + 10)
            require(count in 1..1024)
            var pos = u32(end + 16).toInt()
            require(pos >= 0 && pos.toLong() + u32(end + 12) == end.toLong())
            val names = mutableListOf<String>()
            repeat(count) {
                require(u32(pos) == 0x02014b50L)
                val flags = u16(pos + 8)
                require(flags and 1 == 0 && flags and 64 == 0 && u16(pos + 10) in setOf(0, 8))
                require(u32(pos + 20) != 0xffffffffL && u32(pos + 24) != 0xffffffffL && u16(pos + 34) == 0)
                val len = u16(pos + 28)
                require(pos + 46 + len <= end)
                val name = bytes.copyOfRange(pos + 46, pos + 46 + len).decodeToString(throwOnInvalidSequence = true)
                require(SkillPackageFormat.validPath(name.removeSuffix("/")))
                val mode = (u32(pos + 38) shr 16).toInt() and 0xf000
                require(mode == 0 || mode == if (name.endsWith('/')) 0x4000 else 0x8000) { "ZIP links and special files forbidden" }
                require(u32(pos + 38).toInt() and 0x400 == 0) { "Reparse entry forbidden" }
                var extra = pos + 46 + len
                val extraEnd = extra + u16(pos + 30)
                require(extraEnd <= end)
                while (extra < extraEnd) {
                    require(extra + 4 <= extraEnd)
                    require(u16(extra) !in setOf(0x0001, 0x000a, 0x000d, 0x756e)) { "ZIP64 and link-capable extra records unsupported" }
                    extra += 4 + u16(extra + 2)
                    require(extra <= extraEnd)
                }
                names += name
                pos += 46 + len + u16(pos + 30) + u16(pos + 32)
            }
            require(pos == end && names.map { it.removeSuffix("/").lowercase() }.toSet().size == names.size)
            val prefix = if (stripRoot) names.first().substringBefore('/') + "/" else ""
            require(!stripRoot || names.all { it.startsWith(prefix) })
            val entries = mutableListOf<SkillArchiveEntry>()
            var total = 0
            var index = 0
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(index < names.size && entry.name == names[index++]) { "ZIP directory mismatch" }
                    val localExtra = entry.extra ?: byteArrayOf()
                    var e = 0
                    while (e < localExtra.size) {
                        require(e + 4 <= localExtra.size)
                        fun field(offset: Int) = (localExtra[offset].toInt() and 255) or ((localExtra[offset + 1].toInt() and 255) shl 8)
                        require(field(e) !in setOf(0x0001, 0x000a, 0x000d, 0x756e))
                        e += 4 + field(e + 2)
                        require(e <= localExtra.size)
                    }
                    val relative = entry.name.removePrefix(prefix)
                    if (entry.isDirectory) { require(zip.read() == -1); continue }
                    require(entries.size < SkillPackageFormat.MAX_FILES + 1)
                    val limit = if (relative == SkillPackageFormat.MANIFEST) SkillPackageFormat.MAX_MANIFEST_BYTES else SkillPackageFormat.MAX_PAYLOAD_BYTES.toInt()
                    val data = zip.readNBytes(minOf(limit, (SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES - total).toInt()) + 1)
                    total += data.size
                    require(data.size <= limit && total <= SkillPackageFormat.MAX_PAYLOAD_BYTES + SkillPackageFormat.MAX_MANIFEST_BYTES)
                    entries += SkillArchiveEntry(relative, data)
                }
            }
            require(index == names.size)
            return entries
        }
    }
}
