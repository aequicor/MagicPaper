package io.aequicor.magicpaper.domain

import kotlinx.serialization.Serializable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Integrity-checked, not trusted. Created only by a platform byte verifier. */
class ValidatedSkillPackage internal constructor(private val manifestJson: String, val checksum: String) {
    // Decode copies so mutable collections supplied by callers cannot alter an installed release.
    val manifest: SkillPackageManifest get() = SkillPackageFormat.json.decodeFromString(manifestJson)
    val key: String get() = "${manifest.id}@${manifest.version}"
}

enum class SkillCandidateStatus { QUARANTINED, VERIFIED }

/** Local review of this exact checksum. Never import these decisions from a package/profile. */
@Serializable
data class SkillPackageReview(
    val checksum: String,
    val reviewer: String,
    val evidence: String,
    val originVerified: Boolean,
    val licenseVerified: Boolean,
    val contentReviewed: Boolean,
)

@Serializable
data class SkillImprovementCheck(val baseline: String?, val suiteHash: String, val passed: Boolean = false)

data class SkillInstalledRelease(
    val pkg: ValidatedSkillPackage,
    val status: SkillCandidateStatus = SkillCandidateStatus.QUARANTINED,
    val review: SkillPackageReview? = null,
    val improvement: SkillImprovementCheck? = null,
)

data class SkillReleaseSnapshot(
    val generation: Long = 0,
    val installed: Map<String, SkillInstalledRelease> = emptyMap(),
    val active: Map<String, String> = emptyMap(),
    val previousActive: Map<String, String>? = null,
)

/** Approval binds the preview to both the current generation and the complete target set. */
data class SkillActivationConsent(
    val generation: Long,
    val targetChecksums: Map<String, String>,
    val reviewedChanges: Boolean,
    val permissions: Set<SkillPermission>,
)

/**
 * Reference atomic state machine for the contract. A durable adapter must commit the entire
 * snapshot with compare-and-swap before publishing it; legacy SkillRepository is not that adapter.
 */
class SkillReleaseStore(
    private val host: SkillPackageHost,
    initial: SkillReleaseSnapshot = SkillReleaseSnapshot(),
    private val persist: (SkillReleaseSnapshot, SkillReleaseSnapshot) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private var state = initial.copy(installed = initial.installed.toMap(), active = initial.active.toMap(), previousActive = initial.previousActive?.toMap())

    private fun commit(next: SkillReleaseSnapshot) {
        persist(copySnapshot(), next)
        state = next
    }

    suspend fun snapshot(): SkillReleaseSnapshot = mutex.withLock { copySnapshot() }

    private fun copySnapshot() = state.copy(
        installed = state.installed.toMap(), active = state.active.toMap(), previousActive = state.previousActive?.toMap(),
    )

    suspend fun install(pkg: ValidatedSkillPackage, improvement: SkillImprovementCheck? = null): SkillReleaseSnapshot = mutex.withLock {
        SkillPackageFormat.validate(pkg.manifest, host)
        val existing = state.installed[pkg.key]
        require(existing == null || existing.pkg.checksum == pkg.checksum) { "Version is immutable; publish a new version" }
        if (existing == null) commit(state.copy(
            generation = state.generation + 1,
            installed = state.installed + (pkg.key to SkillInstalledRelease(pkg, improvement = improvement)),
        ))
        copySnapshot()
    }

    suspend fun recordImprovement(key: String, check: SkillImprovementCheck): SkillReleaseSnapshot = mutex.withLock {
        val release = state.installed.getValue(key)
        val expected = requireNotNull(release.improvement)
        require(check.baseline == expected.baseline && check.suiteHash == expected.suiteHash)
        require(key !in state.active.values)
        commit(state.copy(generation = state.generation + 1,
            installed = state.installed + (key to release.copy(improvement = check))))
        copySnapshot()
    }

    /** Explicit deletion of local experience also removes its releases and rollback references. */
    suspend fun forget(keys: Set<String>): SkillReleaseSnapshot = mutex.withLock {
        val installed = state.installed - keys
        val active = state.active.filterValues { it !in keys }.toMutableMap()
        // Remove dependants of deleted versions rather than retaining an invalid activation graph.
        do {
            val invalid = active.filter { (_, key) -> installed.getValue(key).pkg.manifest.dependencies.any {
                active[it.id] != "${it.id}@${it.version}"
            } }.keys
            invalid.forEach(active::remove)
        } while (invalid.isNotEmpty())
        commit(state.copy(generation = state.generation + 1, installed = installed, active = active, previousActive = null))
        copySnapshot()
    }

    suspend fun review(key: String, review: SkillPackageReview): SkillReleaseSnapshot = mutex.withLock {
        val release = state.installed.getValue(key)
        require(review.checksum == release.pkg.checksum && review.reviewer.isNotBlank() && review.evidence.isNotBlank()) { "Review must identify exact bytes and evidence" }
        val status = if (review.originVerified && review.licenseVerified && review.contentReviewed)
            SkillCandidateStatus.VERIFIED else SkillCandidateStatus.QUARANTINED
        // Revoking review of an active release first requires an explicit activation-set change.
        require(status == SkillCandidateStatus.VERIFIED || key !in state.active.values) { "Deactivate before revoking review" }
        commit(state.copy(generation = state.generation + 1, installed = state.installed + (key to release.copy(status = status, review = review))))
        copySnapshot()
    }

    suspend fun activate(target: Map<String, String>, consent: SkillActivationConsent): SkillReleaseSnapshot = mutex.withLock {
        switchTo(target.toMap(), consent)
    }

    suspend fun rollback(consent: SkillActivationConsent): SkillReleaseSnapshot = mutex.withLock {
        switchTo(state.previousActive ?: error("No rollback snapshot"), consent)
    }

    private fun switchTo(target: Map<String, String>, consent: SkillActivationConsent): SkillReleaseSnapshot {
        require(consent.generation == state.generation && consent.reviewedChanges) { "Stale or missing change approval" }
        val releases = target.map { (id, key) ->
            state.installed.getValue(key).also {
                require(it.pkg.manifest.id == id && it.status == SkillCandidateStatus.VERIFIED) { "Unverified candidate or wrong id" }
            }
        }
        require(consent.targetChecksums == releases.associate { it.pkg.key to it.pkg.checksum }) { "Approval does not match target" }
        releases.filter { it.pkg.key !in state.active.values }.forEach { release ->
            release.improvement?.let { check ->
                require(check.passed) { "Improvement has not passed agreed metrics" }
                // Rolling back to a previously active, checked release remains possible.
                require(state.previousActive?.get(release.pkg.manifest.id) == release.pkg.key ||
                    state.active[release.pkg.manifest.id] == check.baseline) { "Evaluation baseline changed" }
            }
        }
        SkillPackageFormat.validateGraph(releases.map { it.pkg.manifest }, host)
        // Compare permissions per skill, so another skill's grants cannot authorize this one.
        val addedPermissions = releases.flatMap { release ->
            val old = state.active[release.pkg.manifest.id]?.let { state.installed.getValue(it).pkg.manifest.permissions }.orEmpty()
            release.pkg.manifest.permissions - old
        }.toSet()
        require(consent.permissions.containsAll(addedPermissions)) { "New permissions require explicit consent" }
        commit(state.copy(generation = state.generation + 1, active = target.toMap(), previousActive = state.active))
        return copySnapshot()
    }
}
