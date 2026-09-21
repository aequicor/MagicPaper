package io.aequicor.magicpaper.domain

import io.aequicor.magicpaper.machine.Machine
import io.aequicor.magicpaper.machine.MachineId
import io.aequicor.magicpaper.machine.Step
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable data class SkillRef(val id: String, val generation: String, val version: Long)
@Serializable data class SkillEntry(val skill: Skill, val version: Long)
@Serializable data class SkillInstallBasis(val generation: String, val nameKey: String,
    val existing: SkillRef?, val nameVersion: Long?)

/** Catalog and installed skill state have one owner; renderers never replace its snapshot. */
object SkillMachine : Machine<SkillMachine.State, SkillMachine.Input, SkillMachine.Effect> {
    override val id = MachineId("skill")
    override val space get() = SkillSpace
    /** Bridge to the owner's own reducer: [Transition] and [reduce] keep every call site. */
    override fun step(state: State, input: Input) = reduce(state, input).let { Step(it.state, it.effects) }

    @ConsistentCopyVisibility
    data class State internal constructor(
        val initialized: Boolean = false,
        val generation: String = "",
        val catalogRevision: Long = 0,
        val usedGenerations: Set<String> = emptySet(),
        val skills: Map<String, SkillEntry> = emptyMap(),
        val nameVersions: Map<String, Long> = emptyMap(),
        val retiredIds: Set<String> = emptySet(),
        val persistenceUnknown: Boolean = false,
    ) {
        fun ref(id: String) = skills[id]?.let { SkillRef(id, generation, it.version) }
        fun installBasis(nameKey: String) = SkillInstallBasis(generation, nameKey,
            skills.values.singleOrNull { it.skill.nameKey == nameKey }?.let { ref(it.skill.id) }, nameVersions[nameKey])
    }

    @Serializable sealed interface Input
    @Serializable sealed interface Intent : Input {
        @Serializable @SerialName("Install") data class Install(val expected: SkillInstallBasis,
            val skill: Skill, val at: Long) : Intent
        @Serializable @SerialName("SetEnabled") data class SetEnabled(val expected: SkillRef,
            val enabled: Boolean, val at: Long) : Intent
        @Serializable @SerialName("Delete") data class Delete(val expected: SkillRef) : Intent
        @Serializable @SerialName("Import") data class Import(val expectedGeneration: String,
            val expectedRevision: Long, val skills: List<Skill>, val generation: String) : Intent
        @Serializable @SerialName("Clear") data class Clear(val expectedGeneration: String,
            val expectedRevision: Long, val generation: String) : Intent
    }
    @Serializable sealed interface Fact : Input {
        @Serializable @SerialName("Initialized") data class Initialized(val skills: List<Skill>, val generation: String) : Fact
        @Serializable @SerialName("PersistenceUnknown") data object PersistenceUnknown : Fact
    }
    sealed interface Effect { data class Reject(val reason: String) : Effect }
    data class Transition(val state: State, val effects: List<Effect> = emptyList())

    fun initial() = State()

    fun reduce(state: State, input: Input): Transition {
        fun reject(reason: String) = Transition(state, listOf(Effect.Reject(reason)))
        if (input == Fact.PersistenceUnknown) return Transition(state.copy(persistenceUnknown = true))
        if (state.persistenceUnknown) return reject("Требуется повторно загрузить библиотеку навыков")
        if (!state.initialized && input !is Fact.Initialized) return reject("Библиотека навыков ещё не загружена")
        if (state.catalogRevision == Long.MAX_VALUE) return reject("Библиотека достигла предела изменений")
        val nextRevision = state.catalogRevision + 1
        fun replace(values: List<Skill>, generation: String): Transition {
            if (generation.isBlank() || generation in state.usedGenerations || values.any { it.id.isBlank() } ||
                values.map { it.id }.distinct().size != values.size)
                return reject("Некорректные идентификаторы навыков")
            val replacement = values.associate { it.id to SkillEntry(it.copy(tags = it.tags.toList()), nextRevision) }
            val affectedNames = state.skills.values.map { it.skill.nameKey } + values.map { it.nameKey }
            return Transition(state.copy(initialized = true, generation = generation, catalogRevision = nextRevision,
                usedGenerations = state.usedGenerations + generation, skills = replacement,
                nameVersions = state.nameVersions + affectedNames.associateWith { nextRevision },
                retiredIds = state.retiredIds + (state.skills.keys - replacement.keys)))
        }
        return when (input) {
            is Fact.Initialized -> if (state.initialized) reject("Библиотека уже загружена") else replace(input.skills, input.generation)
            is Intent.Install -> {
                val incoming = input.skill
                if (input.expected != state.installBasis(incoming.nameKey)) return reject("Навык изменился; повторите установку")
                if (incoming.id.isBlank() || incoming.name.isBlank() || incoming.instructions.isBlank() || input.at < 0)
                    return reject("У навыка должны быть имя и инструкция")
                val matches = state.skills.values.filter { it.skill.nameKey == incoming.nameKey }
                if (matches.size > 1) return reject("В библиотеке несколько навыков с этим именем; сначала устраните конфликт")
                val existing = matches.singleOrNull()?.skill
                if (existing != null && existing.source != incoming.source)
                    return reject("Имя занято навыком из другого источника")
                if (existing == null && (incoming.id in state.skills || incoming.id in state.retiredIds))
                    return reject("Идентификатор навыка уже использован")
                val installed = incoming.copy(id = existing?.id ?: incoming.id,
                    createdAt = existing?.createdAt ?: input.at, updatedAt = input.at, tags = incoming.tags.toList())
                Transition(state.copy(catalogRevision = nextRevision,
                    skills = state.skills + (installed.id to SkillEntry(installed, nextRevision)),
                    nameVersions = state.nameVersions + (installed.nameKey to nextRevision)))
            }
            is Intent.SetEnabled -> {
                if (state.ref(input.expected.id) != input.expected) return reject("Навык изменился; повторите действие")
                if (input.at < 0) return reject("Некорректное время изменения")
                val existing = state.skills.getValue(input.expected.id)
                if (existing.skill.enabled == input.enabled) Transition(state)
                else Transition(state.copy(catalogRevision = nextRevision,
                    skills = state.skills + (input.expected.id to SkillEntry(
                        existing.skill.copy(enabled = input.enabled, updatedAt = input.at), nextRevision)),
                    nameVersions = state.nameVersions + (existing.skill.nameKey to nextRevision)))
            }
            is Intent.Delete -> {
                if (state.ref(input.expected.id) != input.expected) reject("Навык изменился; повторите действие")
                else Transition(state.copy(catalogRevision = nextRevision, skills = state.skills - input.expected.id,
                    retiredIds = state.retiredIds + input.expected.id,
                    nameVersions = state.nameVersions + (state.skills.getValue(input.expected.id).skill.nameKey to nextRevision)))
            }
            is Intent.Import -> {
                if (input.expectedGeneration != state.generation || input.expectedRevision != state.catalogRevision)
                    return reject("Библиотека изменилась")
                if (input.skills.map { it.id }.distinct().size != input.skills.size) return reject("Повторяющиеся идентификаторы навыков")
                replace((state.skills.mapValues { it.value.skill } + input.skills.associateBy { it.id }).values.toList(), input.generation)
            }
            is Intent.Clear -> if (input.expectedGeneration != state.generation || input.expectedRevision != state.catalogRevision)
                reject("Библиотека изменилась") else replace(emptyList(), input.generation)
            Fact.PersistenceUnknown -> error("Handled above")
        }
    }
}
