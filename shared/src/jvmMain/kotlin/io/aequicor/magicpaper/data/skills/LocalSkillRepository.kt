package io.aequicor.magicpaper.data.skills

import io.aequicor.magicpaper.domain.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.charset.CharacterCodingException
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.util.Base64

@Serializable
data class SkillObservedSource(val kind: SkillImportKind, val location: String, val revision: String? = null)

@Serializable
private data class StoredRelease(val checksum: String, val review: SkillPackageReview? = null, val source: SkillObservedSource, val improvement: SkillImprovementCheck? = null)
@Serializable
private data class StoredSnapshot(
    val schemaVersion: Int = 1,
    val generation: Long = 0,
    val installed: Map<String, StoredRelease> = emptyMap(),
    val active: Map<String, String> = emptyMap(),
    val previousActive: Map<String, String>? = null,
)
@Serializable
private data class SkillBackup(val state: StoredSnapshot, val packages: Map<String, Map<String, String>>)

data class LocalSkillCatalogEntry(val release: SkillInstalledRelease, val source: SkillObservedSource, val active: Boolean)
data class SkillResourceDiff(
    val path: String, val before: SkillPackageFile?, val after: SkillPackageFile?,
    val beforeText: String?, val afterText: String?,
)
data class SkillReleaseDiff(
    val before: SkillPackageManifest?, val after: SkillPackageManifest,
    val oldInstructions: String?, val newInstructions: String,
    val oldSource: SkillObservedSource?, val newSource: SkillObservedSource,
    val addedFiles: Set<String>, val removedFiles: Set<String>, val changedFiles: Set<String>,
    val resources: List<SkillResourceDiff>,
)

/** Exclusive, application-owned directory. No legacy enabled flags or imported trust decisions.
 * Every write uses the common state machine; readers only see a complete durable snapshot.
 */
class LocalSkillRepository(
    private val root: Path,
    private val host: SkillPackageHost,
    private val beforeSnapshotCommit: () -> Unit = {},
    private val allowRecovery: Boolean = false,
) : AutoCloseable, SkillInstructionSource {
    private val validator = SkillPackageValidator(host)
    private val mutex = Mutex()
    private val sources = mutableMapOf<String, SkillObservedSource>()
    private val lockChannel: FileChannel
    private val fileLock: java.nio.channels.FileLock
    private var machine: SkillReleaseStore
    private val stateFile = root.resolve("snapshot.json")
    private var recoveryFailure: Exception? = null

    init {
        Files.createDirectories(root)
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
        lockChannel = FileChannel.open(root.resolve("repository.lock"), CREATE, WRITE, LinkOption.NOFOLLOW_LINKS)
        try {
            fileLock = lockChannel.tryLock() ?: error("Skill repository already open")
            Files.createDirectories(root.resolve("releases"))
            require(!Files.isSymbolicLink(root.resolve("releases")))
            try {
                val initial = if (Files.exists(stateFile, LinkOption.NOFOLLOW_LINKS)) decode(readLimited(stateFile, MAX_STATE)) else StoredSnapshot()
                val restored = validateSnapshot(initial)
                sources.putAll(initial.installed.mapValues { it.value.source })
                machine = newMachine(restored)
            } catch (e: Exception) {
                if (!allowRecovery) throw e
                recoveryFailure = e
                // This placeholder is inaccessible to every operation except explicit recovery.
                machine = newMachine(SkillReleaseSnapshot())
            }
        } catch (e: Throwable) { lockChannel.close(); throw e }
    }

    private fun checkHealthy() {
        check(fileLock.isValid) { "Repository is closed" }
        check(recoveryFailure == null) { "Хранилище повреждено. Восстановите проверенную резервную копию; существующие данные сохранены." }
    }

    private fun newMachine(initial: SkillReleaseSnapshot) = SkillReleaseStore(host, initial) { old, next ->
        check(fileLock.isValid) { "Repository is closed" }
        val diskGeneration = if (Files.exists(stateFile)) decode(readLimited(stateFile, MAX_STATE)).generation else 0
        check(old.generation == diskGeneration) { "Concurrent snapshot change" }
        beforeSnapshotCommit()
        val bytes = SkillPackageFormat.json.encodeToString(encode(next)).encodeToByteArray()
        require(bytes.size <= MAX_STATE && next.installed.size <= 512)
        try { atomicWrite(stateFile, bytes) } catch (e: Throwable) { close(); throw e }
    }

    suspend fun snapshot() = mutex.withLock {
        checkHealthy()
        machine.snapshot()
    }
    suspend fun review(key: String, review: SkillPackageReview) = mutex.withLock {
        checkHealthy()
        machine.review(key, review)
    }
    suspend fun activate(target: Map<String, String>, consent: SkillActivationConsent) = mutex.withLock {
        checkHealthy()
        machine.activate(target, consent)
    }
    suspend fun rollback(consent: SkillActivationConsent) = mutex.withLock {
        checkHealthy()
        machine.rollback(consent)
    }

    suspend fun install(entries: List<SkillArchiveEntry>, source: SkillObservedSource, expectedChecksum: String? = null, improvement: SkillImprovementCheck? = null, beforeNewInstall: () -> Unit = {}): SkillReleaseSnapshot = mutex.withLock {
        checkHealthy()

        val owned = entries.map { it.copy(bytes = it.bytes.copyOf()) }
        val pkg = validator.validate(owned, expectedChecksum)
        val old = machine.snapshot().installed[pkg.key]
        require(old == null || old.pkg.checksum == pkg.checksum) { "Version is immutable" }
        require(improvement == null || old == null) { "Improvement version already exists" }
        beforeNewInstall()
        persistPackage(pkg, owned)
        if (old == null) sources[pkg.key] = source
        machine.install(pkg, improvement)
    }

    internal suspend fun recordImprovement(key: String, check: SkillImprovementCheck) = mutex.withLock {
        checkHealthy()
        machine.recordImprovement(key, check)
    }

    internal suspend fun forgetExperience(keys: Set<String>) = mutex.withLock {
        checkHealthy()
        val next = machine.forget(keys)
        keys.forEach(sources::remove)
        val retained = next.installed.values.map { it.pkg.checksum }.toSet()
        Files.list(root.resolve("releases")).use { paths -> paths.filter {
            SkillPackageFormat.validHash(it.fileName.toString()) && it.fileName.toString() !in retained
        }.forEach { path -> Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } } }
        val damaged = root.resolve("damaged")
        if (Files.exists(damaged, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(damaged, LinkOption.NOFOLLOW_LINKS))
            Files.list(damaged).use { paths -> paths.filter { path -> keys.any { path.fileName.toString().startsWith("$it--") } }
                .forEach { path -> Files.walk(path).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) } } }
        }
    }

    suspend fun catalog(query: String = ""): List<LocalSkillCatalogEntry> = mutex.withLock {
        checkHealthy()

        val state = machine.snapshot()
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        state.installed.values.map { LocalSkillCatalogEntry(it, sources.getValue(it.pkg.key), it.pkg.key in state.active.values) }
            .filter { entry ->
                val m = entry.release.pkg.manifest
                val text = "${m.id} ${m.name} ${m.description} ${m.version} ${m.license.orEmpty()} ${entry.source.location}".lowercase()
                terms.all { it in text }
            }.sortedWith { a, b ->
                a.release.pkg.manifest.id.compareTo(b.release.pkg.manifest.id).takeIf { it != 0 }
                    ?: SkillPackageFormat.compareVersions(b.release.pkg.manifest.version, a.release.pkg.manifest.version)
            }
    }

    suspend fun diff(key: String): SkillReleaseDiff = mutex.withLock {
        checkHealthy()

        val s = machine.snapshot()
        val next = s.installed.getValue(key).pkg
        val old = s.active[next.manifest.id]?.let { s.installed.getValue(it).pkg }
        val a = old?.manifest?.files.orEmpty().associateBy { it.path }
        val b = next.manifest.files.associateBy { it.path }
        SkillReleaseDiff(old?.manifest, next.manifest, old?.let(::instructions), instructions(next),
            old?.let { sources.getValue(it.key) }, sources.getValue(next.key),
            b.keys - a.keys, a.keys - b.keys, (a.keys intersect b.keys).filter { a[it] != b[it] }.toSet(),
            (a.keys + b.keys).filter { a[it] != b[it] && it != next.manifest.instructions }.map { path ->
                SkillResourceDiff(path, a[path], b[path], old?.let { resourceText(it, a[path]) }, resourceText(next, b[path]))
            })
    }

    private fun resourceText(pkg: ValidatedSkillPackage, file: SkillPackageFile?): String? {
        if (file == null || file.size > 64 * 1024) return null
        val bytes = readLimited(releasePath(pkg.checksum).resolve(file.path), 64 * 1024)
        require(SkillPackageValidator.sha256(bytes) == file.sha256)
        return try { bytes.decodeToString(throwOnInvalidSequence = true).takeUnless { '\u0000' in it } }
        catch (_: CharacterCodingException) { null }
    }

    /** Only active, reverified bytes can be supplied to a runtime. */
    override suspend fun active(): List<SkillInstruction> = mutex.withLock {
        checkHealthy()
        val snapshot = machine.snapshot()
        snapshot.active.values.map { key ->
            val release = snapshot.installed.getValue(key)
            check(release.status == SkillCandidateStatus.VERIFIED)
            val pkg = release.pkg
            val m = pkg.manifest
            SkillInstruction(m.id, m.version, pkg.checksum, m.name, m.description, m.permissions.toSet(), instructions(pkg))
        }
    }

    suspend fun activeInstructions(): Map<String, String> = mutex.withLock {
        checkHealthy()

        val s = machine.snapshot()
        s.active.mapValues { instructions(s.installed.getValue(it.value).pkg) }
    }

    private fun instructions(pkg: ValidatedSkillPackage): String {
        validator.validateDirectory(releasePath(pkg.checksum), pkg.checksum)
        return readLimited(releasePath(pkg.checksum).resolve(pkg.manifest.instructions), SkillPackageFormat.MAX_PAYLOAD_BYTES.toInt()).decodeToString(throwOnInvalidSequence = true)
    }

    suspend fun backup(destination: Path): String = mutex.withLock {
        checkHealthy()

        require(!destination.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize())) { "Choose a backup outside the repository" }
        val state = encode(machine.snapshot())
        validateSnapshot(state)
        val estimatedBytes = state.installed.values.sumOf { r ->
            val m = validator.validateDirectory(releasePath(r.checksum), r.checksum).manifest
            m.files.sumOf { ((it.size + 2) / 3) * 4 + it.path.length + 10 } + SkillPackageFormat.MAX_MANIFEST_BYTES * 2L
        }
        require(estimatedBytes < MAX_BACKUP - MAX_STATE) { "Backup exceeds 128 MiB; export fewer releases" }
        val packages = state.installed.values.associate { r ->
            val pkg = validator.validateDirectory(releasePath(r.checksum), r.checksum)
            r.checksum to (pkg.manifest.files.map { it.path } + SkillPackageFormat.MANIFEST).associateWith {
                Base64.getEncoder().encodeToString(readLimited(releasePath(r.checksum).resolve(it), SkillPackageFormat.MAX_PAYLOAD_BYTES.toInt()))
            }
        }
        val bytes = SkillPackageFormat.json.encodeToString(SkillBackup(state, packages)).encodeToByteArray()
        require(bytes.size <= MAX_BACKUP) { "Backup too large" }
        atomicWrite(destination, bytes)
        SkillPackageValidator.sha256(bytes)
    }

    /** Explicit recovery of a locally trusted backup, pinned to the fingerprint shown at backup.
     * Ordinary package/profile import cannot call this implicitly or supply review decisions.
     */
    suspend fun restore(backup: Path, expectedSha256: String, confirmed: Boolean): SkillReleaseSnapshot = mutex.withLock {
        check(fileLock.isValid) { "Repository is closed" }

        require(confirmed && SkillPackageFormat.validHash(expectedSha256)) { "Explicit backup recovery required" }
        val bytes = readLimited(backup, MAX_BACKUP)
        require(SkillPackageValidator.sha256(bytes) == expectedSha256) { "Backup checksum mismatch" }
        val data = SkillPackageFormat.json.decodeFromString<SkillBackup>(bytes.decodeToString(throwOnInvalidSequence = true))
        require(data.packages.keys == data.state.installed.values.map { it.checksum }.toSet())
        require(data.state.installed.size <= 512)
        val checked = data.packages.map { (checksum, files) ->
            val entries = files.map { SkillArchiveEntry(it.key, Base64.getDecoder().decode(it.value)) }
            validator.validate(entries, checksum) to entries
        }
        val byChecksum = checked.associate { it.first.checksum to it.first }
        val restored = validateSnapshot(data.state) { byChecksum.getValue(it) }
        checked.forEach { (pkg, entries) -> persistPackage(pkg, entries, repairCorrupt = true) }
        // Recovery is a new generation, so pre-recovery approvals can never be reused.
        val next = restored.copy(generation = maxOf(machine.snapshot().generation, restored.generation) + 1)
        beforeSnapshotCommit()
        val stateBytes = SkillPackageFormat.json.encodeToString(data.state.copy(generation = next.generation)).encodeToByteArray()
        require(stateBytes.size <= MAX_STATE)
        try { atomicWrite(stateFile, stateBytes) } catch (e: Throwable) { close(); throw e }
        sources.clear()
        sources.putAll(data.state.installed.mapValues { it.value.source })
        recoveryFailure = null
        machine = newMachine(next)
        next
    }

    private fun encode(s: SkillReleaseSnapshot) = StoredSnapshot(generation = s.generation,
        installed = s.installed.mapValues { StoredRelease(it.value.pkg.checksum, it.value.review, sources.getValue(it.key), it.value.improvement) },
        active = s.active, previousActive = s.previousActive)

    private fun decode(bytes: ByteArray) = SkillPackageFormat.json.decodeFromString<StoredSnapshot>(bytes.decodeToString(throwOnInvalidSequence = true))
    private fun validateSnapshot(
        s: StoredSnapshot,
        packageFor: (String) -> ValidatedSkillPackage = { validator.validateDirectory(releasePath(it), it) },
    ): SkillReleaseSnapshot {
        require(s.schemaVersion == 1 && s.generation >= 0 && s.installed.size <= 512)
        val installed = s.installed.mapValues { (key, r) ->
            val pkg = packageFor(r.checksum)
            require(pkg.key == key)
            val review = r.review
            require(review == null || review.checksum == pkg.checksum && review.reviewer.isNotBlank() && review.evidence.isNotBlank())
            SkillInstalledRelease(pkg, if (review != null && review.originVerified && review.licenseVerified && review.contentReviewed) SkillCandidateStatus.VERIFIED else SkillCandidateStatus.QUARANTINED, review, r.improvement)
        }
        listOfNotNull(s.active, s.previousActive).forEach { active ->
            val releases = active.map { (id, key) -> installed.getValue(key).also {
                require(it.pkg.manifest.id == id)
                // A historical rollback target may have had its review revoked since activation.
                if (active === s.active) require(it.status == SkillCandidateStatus.VERIFIED && it.improvement?.passed != false)
            }.pkg.manifest }
            SkillPackageFormat.validateGraph(releases, host)
        }
        return SkillReleaseSnapshot(s.generation, installed, s.active, s.previousActive)
    }

    private fun releasePath(checksum: String): Path {
        require(SkillPackageFormat.validHash(checksum))
        return root.resolve("releases").resolve(checksum)
    }

    private fun persistPackage(pkg: ValidatedSkillPackage, entries: List<SkillArchiveEntry>, repairCorrupt: Boolean = false) {
        check(fileLock.isValid) { "Repository is closed; reopen after recovery" }
        val destination = releasePath(pkg.checksum)
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            try {
                validator.validateDirectory(destination, pkg.checksum)
                return
            } catch (e: Exception) {
                if (!repairCorrupt) throw e
                val damaged = root.resolve("damaged")
                Files.createDirectories(damaged)
                require(!Files.isSymbolicLink(damaged))
                Files.move(destination, damaged.resolve(pkg.key + "--" + java.util.UUID.randomUUID().toString()), StandardCopyOption.ATOMIC_MOVE)
                syncDirectory(damaged)
                syncDirectory(destination.parent)
            }
        }
        val stage = Files.createTempDirectory(root.resolve("releases"), ".stage-")
        try {
            entries.forEach {
                val path = stage.resolve(it.path)
                Files.createDirectories(path.parent)
                FileChannel.open(path, CREATE_NEW, WRITE).use { ch ->
                    val buf = java.nio.ByteBuffer.wrap(it.bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
            }
            validator.validateDirectory(stage, pkg.checksum)
            Files.walk(stage).use { stream -> stream.filter { Files.isDirectory(it) }.sorted(Comparator.reverseOrder()).forEach(::syncDirectory) }
            Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE)
            syncDirectory(destination.parent)
        } finally {
            if (Files.exists(stage)) Files.walk(stage).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
        }
    }

    override fun close() { lockChannel.close() }

    companion object {
        private const val MAX_STATE = 4 * 1024 * 1024
        private const val MAX_BACKUP = 128 * 1024 * 1024
        internal fun readLimited(path: Path, limit: Int): ByteArray = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use {
            it.readNBytes(limit + 1).also { bytes -> require(bytes.size <= limit) { "Input too large" } }
        }
        private fun syncDirectory(path: Path) { FileChannel.open(path, READ).use { it.force(true) } }
        private fun atomicWrite(path: Path, bytes: ByteArray) {
            val parent = path.toAbsolutePath().parent
            val tmp = Files.createTempFile(parent, ".skill-snapshot-", ".tmp")
            try {
                FileChannel.open(tmp, WRITE).use { ch ->
                    val buf = java.nio.ByteBuffer.wrap(bytes)
                    while (buf.hasRemaining()) ch.write(buf)
                    ch.force(true)
                }
                Files.move(tmp, path.toAbsolutePath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                syncDirectory(parent)
            } finally { Files.deleteIfExists(tmp) }
        }
    }
}
