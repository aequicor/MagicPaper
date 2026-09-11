package io.aequicor.magicpaper.ui.screens

import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.domain.CodingInteractionMode
import io.aequicor.magicpaper.domain.ImmunityInterventionState
import io.aequicor.magicpaper.domain.SessionKind
import io.aequicor.magicpaper.domain.aggregateCodingStatus
import io.aequicor.magicpaper.ui.CodingSessionUi
import io.aequicor.magicpaper.ui.CodingUi

/** A sidebar task is a presentation group; immunity remains an independent runtime root. */
internal data class ProjectSessionTask(
    val key: String,
    val title: String,
    val organismId: String?,
    val rootId: String?,
    val sessions: List<CodingSessionUi>,
    val immunityId: String? = null,
    private val parentIds: Map<String, String?> = emptyMap(),
    private val immunityProposalPending: Boolean = false,
) {
    val status: CodingSessionStatus
        get() = if (immunityProposalPending) CodingSessionStatus.WAITING
        else aggregateCodingStatus(sessions.map { it.status })

    fun visibleRows(collapsed: Set<String>): List<ProjectSessionTreeRow> {
        val parents = sessionForestParents(sessions, parentIds, setOfNotNull(rootId, immunityId))
        val children = sessions.groupBy { parents[it.session.id] }
            .mapValues { (_, items) -> items.sortedBy { it.session.createdAt } }
        val roots = children[null].orEmpty().sortedBy {
            when (it.session.id) {
                rootId -> 0
                immunityId -> 2
                else -> 1
            }
        }
        return buildList {
            val pending = ArrayDeque<Pair<CodingSessionUi, Int>>()
            roots.asReversed().forEach { pending.addLast(it to 0) }
            while (pending.isNotEmpty()) {
                val (item, depth) = pending.removeLast()
                val ownChildren = children[item.session.id].orEmpty()
                add(ProjectSessionTreeRow(item, depth, ownChildren.size))
                if (item.session.id !in collapsed) {
                    ownChildren.asReversed().forEach { pending.addLast(it to depth + 1) }
                }
            }
        }
    }
}

internal data class ProjectSessionTreeRow(val item: CodingSessionUi, val depth: Int, val childCount: Int)

/** Resolve saved identity before tree ancestry so similarly named tasks can never be merged. */
internal fun CodingUi.projectSessionTasks(projectId: String): List<ProjectSessionTask> {
    val own = sessionsOf(projectId).distinctBy { it.session.id }
    val visible = own.filterNot { it.session.archived }
    if (visible.isEmpty()) return emptyList()
    val byId = own.associateBy { it.session.id }
    val projectOrganisms = organisms.values.filter { it.projectId == projectId }.associateBy { it.id }
    val aggregateMembership = buildMap<String, MutableSet<String>> {
        projectOrganisms.values.forEach { organism ->
            (organism.sessions.keys + organism.zygoteId + organism.immunityId).forEach { id ->
                getOrPut(id) { mutableSetOf() }.add(organism.id)
            }
        }
    }
    val membership = own.associate { item ->
        item.session.id to (item.session.organismId ?: aggregateMembership[item.session.id]?.singleOrNull())
    }.toMutableMap()
    // Older planner children can have only a parent ID while an organism projection reloads.
    // Archived ancestors still supply identity, but never become visible rows.
    own.forEach { item ->
        val path = mutableSetOf<String>()
        var next: String? = item.session.id
        while (next != null && membership[next] == null && path.add(next)) {
            next = byId[next]?.session?.parentSessionId
        }
        val organismId = next?.let { membership[it] }
        if (organismId != null) path.forEach { membership[it] = organismId }
    }
    val inputOrder = sessions.filter { it.session.projectId == projectId }
        .mapIndexed { index, item -> item.session.id to index }.toMap()
    data class OrderedTask(val task: ProjectSessionTask, val createdAt: Long, val rootOrder: Int)
    val tasks = mutableListOf<OrderedTask>()
    visible.filter { membership[it.session.id] != null }.groupBy { membership.getValue(it.session.id)!! }
        .forEach { (organismId, members) ->
            val organism = projectOrganisms[organismId]
            val allMembers = own.filter { membership[it.session.id] == organismId }
            val allMemberIds = allMembers.map { it.session.id }.toSet()
            val workMembers = allMembers.filter { it.session.sessionKind != SessionKind.IMMUNITY }
            val rootId = organism?.zygoteId
                ?: allMembers.firstOrNull { it.session.sessionKind == SessionKind.ZYGOTE }?.session?.id
                ?: workMembers.filter { it.session.parentSessionId !in allMemberIds }
                    .minByOrNull { it.session.createdAt }?.session?.id
                ?: workMembers.minByOrNull { it.session.createdAt }?.session?.id
            val immunityId = organism?.immunityId
                ?: allMembers.firstOrNull { it.session.sessionKind == SessionKind.IMMUNITY }?.session?.id
            val root = byId[rootId]
            val title = root?.session?.name ?: organism?.sessions?.get(rootId)?.name ?: "Задача"
            val immunity = byId[immunityId]
            // Automatic lifecycle adoption is invisible for a standalone conversation.
            // Keep real plans, delegated work and diagnostic history reachable.
            val standalone = root != null && !root.session.planningMode && root.plan == null &&
                organism?.sessions?.get(rootId)?.mode != CodingInteractionMode.PLANNING &&
                root.session.planId == null && root.session.stageId == null &&
                allMembers.all { it.session.id == rootId || it.session.id == immunityId } &&
                organism?.sessions?.keys.orEmpty().all { it == rootId || it == immunityId } &&
                organism?.signals.orEmpty().isEmpty() && organism?.diagnoses.orEmpty().isEmpty() &&
                organism?.interventions.orEmpty().isEmpty() &&
                (immunity == null || (!immunity.running && !immunity.draft.active && !immunity.awaitingUser &&
                    !immunity.failedRequest && !immunity.interruptedRequest &&
                    immunity.messages.none { !it.systemContext }))
            if (standalone && root.session.archived.not()) {
                tasks += OrderedTask(ProjectSessionTask("session-$rootId", title, null, rootId, listOf(root)),
                    root.session.createdAt, inputOrder[rootId] ?: Int.MAX_VALUE)
                return@forEach
            }
            val task = ProjectSessionTask(
                key = "task-$organismId", title = title, organismId = organismId, rootId = rootId,
                sessions = members, immunityId = immunityId,
                parentIds = members.associate { item ->
                    val node = organism?.sessions?.get(item.session.id)
                    item.session.id to if (node != null) node.originParentId else item.session.parentSessionId
                },
                immunityProposalPending = organism?.let { it.deletedAt == null &&
                    it.interventions.any { proposal -> proposal.state == ImmunityInterventionState.PROPOSED } } == true,
            )
            tasks += OrderedTask(task, root?.session?.createdAt ?: organism?.createdAt
                ?: allMembers.minOf { it.session.createdAt }, inputOrder[rootId] ?: Int.MAX_VALUE)
        }

    // Legacy roots retain their former sidebar order and presentation. Normalize only
    // the visible legacy forest: an archived legacy parent no longer owns a list row.
    val legacy = visible.filter { membership[it.session.id] == null }
    val legacyParents = sessionForestParents(legacy)
    val roots = legacy.filter { legacyParents[it.session.id] == null }
    val legacyChildren = legacy.groupBy { legacyParents[it.session.id] }
    roots.forEach { root ->
        val members = buildList {
            val pending = ArrayDeque<CodingSessionUi>()
            pending.addLast(root)
            while (pending.isNotEmpty()) {
                val item = pending.removeLast()
                add(item)
                legacyChildren[item.session.id].orEmpty().asReversed().forEach { pending.addLast(it) }
            }
        }
        tasks += OrderedTask(ProjectSessionTask("session-${root.session.id}", root.session.name,
            null, root.session.id, members, parentIds = legacyParents), root.session.createdAt,
            inputOrder.getValue(root.session.id))
    }
    return tasks.sortedWith(compareByDescending<OrderedTask> { it.createdAt }
        .thenBy { it.rootOrder }.thenBy { it.task.key }).map { it.task }
}

/** Detach a single deterministic node per malformed cycle; preserve every visible session. */
private fun sessionForestParents(
    sessions: List<CodingSessionUi>,
    overrides: Map<String, String?> = emptyMap(),
    independentRoots: Set<String> = emptySet(),
): Map<String, String?> {
    val byId = sessions.associateBy { it.session.id }
    val parents = sessions.associate { item ->
        val id = item.session.id
        val parent = if (overrides.containsKey(id)) overrides[id] else item.session.parentSessionId
        id to parent?.takeIf { id !in independentRoots && it != id && it in byId }
    }.toMutableMap()
    val settled = mutableSetOf<String>()
    sessions.forEach { item ->
        val path = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        var next: String? = item.session.id
        while (next != null && next !in settled && seen.add(next)) {
            path += next
            next = parents[next]
        }
        if (next != null && next in seen) {
            val cycle = path.dropWhile { it != next }
            val root = cycle.minWith(compareBy<String> { byId.getValue(it).session.createdAt }.thenBy { it })
            parents[root] = null
        }
        settled += path
    }
    return parents
}
