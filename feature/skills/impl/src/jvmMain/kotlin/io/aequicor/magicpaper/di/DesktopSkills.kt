package io.aequicor.magicpaper.di

import io.aequicor.magicpaper.data.skills.*
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.plugins.MagicPlugin
import io.aequicor.magicpaper.data.storage.DraftRepository
import io.aequicor.magicpaper.plugins.builtin.*
import kotlinx.coroutines.*
import java.nio.file.Path

/** Owns native skill package and learning resources; their concrete repositories stay private. */
class DesktopSkills(private val draftRepository: DraftRepository, root: Path = Path.of(System.getProperty("user.home"), ".MagicPaper")) {
    private val formScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val packages = LocalSkillsPlugin(root.resolve("skill-packages"), draftRepository, formScope)
    private val experienceRoot = root.resolve("skill-experience")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var experience: LocalSkillExperience? = null
    private var experiencePanel: LocalExperiencePlugin? = null
    private var maintenance: Job? = null
    val plugin: MagicPlugin get() = packages
    val projectSkills: ProjectSkills = ProjectSkillsPanel(packages.forms) { packages.repo() }
    val instructions: SkillInstructionSource get() = packages.instructionSource
    val runObserver: CodingRunObserver = skillExperienceObserver({ experience })
    suspend fun selection(projectId: String) = packages.repo().projectCodingSelection(projectId)
    suspend fun recordRun(record: CodingSkillRunRecord) = packages.repo().recordCodingRun(record)

    fun experiencePlugin(gateway: LlmGateway, profiles: LlmProfileRepository): MagicPlugin {
        packages.beforeInstructions = { checkNotNull(experience) { "Experience journal unavailable" }.retentionDays() }
        return try {
            val journal = LocalSkillExperience(experienceRoot, packages.repo(), gateway, { profiles.load().map { it.apiKey } })
            experience = journal
            LocalExperiencePlugin(journal, profiles, draftRepository, formScope).also { experiencePanel = it }
        } catch (failure: Exception) {
            io.aequicor.magicpaper.logging.AppLog.error("DesktopSkills", "experience_open_failed", failure)
            UnavailableExperiencePlugin
        }
    }

    fun start() {
        if (maintenance != null) return
        val journal = experience ?: return
        maintenance = scope.launch {
            while (isActive) {
                try {
                    journal.search()
                    experiencePanel?.forms?.clearMaintenanceFailure()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val forms = experiencePanel?.forms
                    if (forms != null) forms.reportMaintenanceFailure(failure)
                    else io.aequicor.magicpaper.logging.AppLog.error("DesktopSkills", "experience_maintenance_failed", failure)
                }
                delay(60_000)
            }
        }
    }
    suspend fun close() = withContext(NonCancellable) {
        var failure: Throwable? = null
        suspend fun attempt(block: suspend () -> Unit) {
            try { block() }
            catch (next: Throwable) { if (failure == null) failure = next else failure?.addSuppressed(next) }
        }
        attempt { maintenance?.cancelAndJoin() }
        scope.cancel()
        attempt { experiencePanel?.prepareForReset() }
        attempt { packages.prepareForReset() }
        // Even a failed draft flush cannot leave a command using a closing native repository.
        formScope.coroutineContext[Job]?.let { it.cancelAndJoin() }
        attempt { experience?.close() }
        attempt { packages.close() }
        failure?.let { throw it }
    }
}
