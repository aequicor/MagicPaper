package io.aequicor.magicpaper.backend

import io.aequicor.magicpaper.domain.CodingEngine

/** Immutable metadata/factories only. Each create call owns distinct native state and must be closed. */
class BackendAgentCatalog constructor(contributions: List<BackendAgentContribution>, private val constructor: BackendAgentConstructor) {
    private val contributions = contributions.toList()
    val descriptors = this.contributions.map { it.descriptor }

    init {
        require(contributions.isNotEmpty()) { "No native adapters are installed" }
        require(descriptors.map { it.engine }.distinct().size == descriptors.size) { "Duplicate native adapter identity" }
        contributions.forEach { contribution ->
            listOf(contribution.paths.home, contribution.paths.processes, contribution.paths.questionnaires).forEach { path ->
                require(path.isNotBlank() && !path.startsWith('/') && path.split('/', '\\').none { it == ".." }) {
                    "Backend namespace must be relative to the application home"
                }
            }
        }
    }

    fun descriptor(engine: CodingEngine): BackendAgentDescriptor = descriptors.single { it.engine == engine }

    fun create(engine: CodingEngine, environment: (BackendAgentDescriptor, NativeBackendPaths) -> NativeBackendEnvironment): BackendAgent =
        BackendAgentCatalog(listOf(contributions.single { it.descriptor.engine == engine }), constructor).create(environment).single()

    fun create(environment: (BackendAgentDescriptor, NativeBackendPaths) -> NativeBackendEnvironment): List<BackendAgent> {
        val created = mutableListOf<BackendAgent>()
        try {
            contributions.forEach { contribution ->
                val agent = constructor.create(contribution, environment(contribution.descriptor, contribution.paths))
                created += agent
                require(agent.descriptor == contribution.descriptor) { "Native adapter changed its declared descriptor" }
                val declared = agent.descriptor.capabilities
                require((BackendAgentCapability.NATIVE_APPROVALS in declared) == (agent.approvals != null)) {
                    "Native approval capability and implementation disagree"
                }
                require((BackendAgentCapability.NATIVE_TOOL_HISTORY in declared) == (agent.history != null)) {
                    "Native history capability and implementation disagree"
                }
                require((BackendAgentCapability.MANAGED_INSTALLATION in declared) == (agent.removal != null)) {
                    "Managed installation capability and implementation disagree"
                }
                require((BackendAgentCapability.NATIVE_MODEL_CATALOG in declared) == (agent.models != null)) {
                    "Native model catalog capability and implementation disagree"
                }
                require((BackendAgentCapability.NATIVE_SIGN_IN in declared) == (agent.signIn != null)) {
                    "Native sign-in capability and implementation disagree"
                }
            }
            return created.toList()
        } catch (failure: Throwable) {
            created.asReversed().forEach { agent -> try { agent.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) } }
            throw failure
        }
    }
}

