package io.aequicor.magicpaper.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.aequicor.magicpaper.domain.*
import io.aequicor.magicpaper.ui.CodingUi

/** Элементы единого списка: чаты и кодинг-сессии, отсортированные по обновлению. */
@Composable
internal fun rememberNativeSidebarItems(
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
    recencyTracker: SessionRecencyTracker,
): List<UnifiedSidebarItem> {
    // Иммунитет-сессии исключены из списка — они доступны через ромбик на зиготе.
    // Сопоставляем зиготу с иммунитетом через organisms (надёжная привязка).
    val immunityByZygote = remember(coding.organisms, coding.sessions, selectedId, viewingCoding) {
        val result = mutableMapOf<String, ImmunityInfo>()
        coding.organisms.values.forEach { organism ->
            val immId = organism.immunityId ?: return@forEach
            val immSession = coding.sessions.firstOrNull { it.session.id == immId } ?: return@forEach
            result[organism.zygoteId] = ImmunityInfo(
                sessionId = immId,
                status = immSession.status,
                selected = viewingCoding && immId == selectedId,
            )
        }
        result
    }
    val codingItems = remember(coding.sessions, coding.projects, coding.organisms, immunityByZygote, recencyTracker) {
        // Все сессии (включая архивные) для построения дерева; видимые фильтруются ниже.
        // A repeated session would repeat its lazy-list key, which Compose rejects.
        val allSessions = coding.sessions.distinctBy { it.session.id }
        val sessionById = allSessions.associateBy { it.session.id }
        // Группируем участников по организмам.
        val organismMemberIds = mutableMapOf<String, MutableSet<String>>()
        coding.organisms.values.forEach { organism ->
            (organism.sessions.keys + organism.zygoteId + organism.immunityId?.let { listOf(it) }.orEmpty())
                .forEach { id -> organismMemberIds.getOrPut(id) { mutableSetOf() }.add(organism.id) }
        }
        val membership = allSessions.associate { item ->
            item.session.id to (item.session.organismId ?: organismMemberIds[item.session.id]?.singleOrNull())
        }
        // Мапа родительских связей внутри организма (originParentId из узла).
        val organismParents = mutableMapOf<String, String?>()
        coding.organisms.values.forEach { organism ->
            organism.sessions.forEach { (id, node) ->
                if (id != organism.zygoteId && id != organism.immunityId) {
                    organismParents[id] = node.originParentId
                }
            }
        }
        // Прямые потомки сессии: из дерева организма (если есть) или по parentSessionId.
        fun childIdsOf(parentId: String): List<String> {
            val organismId = membership[parentId]
            return if (organismId != null) {
                organismParents.filterValues { it == parentId }.keys.toList()
            } else {
                allSessions.filter { it.session.parentSessionId == parentId }.map { it.session.id }
            }
        }
        // Рекурсивный сбор видимых потомков: архивные скрыты, но их дети показаны.
        fun collectVisibleChildren(parentId: String, excludeIds: Set<String>): List<UnifiedSidebarItem> {
            val result = mutableListOf<UnifiedSidebarItem>()
            for (childId in childIdsOf(parentId).filter { it !in excludeIds }) {
                val childUi = sessionById[childId] ?: continue
                val children = collectVisibleChildren(childId, excludeIds + parentId + childId)
                if (childUi.session.archived || childUi.session.sessionKind == SessionKind.IMMUNITY) {
                    // Архивный родитель скрыт, но его дети показаны.
                    result.addAll(children)
                    continue
                }
                val childItem = UnifiedSidebarItem(
                    id = childUi.session.id,
                    displayName = childUi.session.sidebarTitle(),
                    sortTime = recencyTracker.observe(
                        childUi.session.id,
                        childUi.status,
                        childUi.session.createdAt,
                        childUi.session.lastStatus,
                        childUi.session.statusChangedAt,
                    ),
                    isCoding = true,
                    projectId = childUi.session.projectId,
                    codingStatus = childUi.status,
                    unread = childUi.unread,
                    children = children,
                )
                result.add(childItem)
            }
            return result.sortedWith(unifiedSidebarItemComparator)
        }
        // Корневые элементы: зиготы организмов и автономные сессии без родителя.
        val visible = allSessions.filter { !it.session.archived && it.session.sessionKind != SessionKind.IMMUNITY }
        fun hasVisibleAncestor(id: String): Boolean {
            fun parentOf(childId: String): String? = if (membership[childId] != null) organismParents[childId]
                else sessionById[childId]?.session?.parentSessionId
            val visited = mutableSetOf(id)
            var parent = parentOf(id)
            while (parent != null && visited.add(parent)) {
                val session = sessionById[parent]?.session ?: return false
                if (!session.archived && session.sessionKind != SessionKind.IMMUNITY) return true
                parent = parentOf(parent)
            }
            return false
        }
        visible
            .filter { item ->
                val organismId = membership[item.session.id]
                if (organismId != null) {
                    val organism = coding.organisms[organismId]
                    // Зигота организма — корневой элемент; остальные участники — дети.
                    organism?.zygoteId == item.session.id ||
                        (sessionById[organism?.zygoteId]?.session?.archived != false && !hasVisibleAncestor(item.session.id))
                } else {
                    !hasVisibleAncestor(item.session.id)
                }
            }
            .map { sessionUi ->
                val organismId = membership[sessionUi.session.id]
                val organism = organismId?.let { coding.organisms[it] }
                if (organism != null && sessionUi.session.id == organism.zygoteId) {
                    val excludeIds = setOfNotNull(organism.zygoteId, organism.immunityId)
                    val children = collectVisibleChildren(organism.zygoteId, excludeIds)
                        .sortedWith(unifiedSidebarItemComparator)
                    val memberUis = allSessions.filter { membership[it.session.id] == organismId &&
                        it.session.id !in excludeIds && !it.session.archived }
                    val status = aggregateCodingStatus(
                        (memberUis + sessionUi).map { it.status }
                    )
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.sidebarTitle(),
                        sortTime = recencyTracker.observe(
                            sessionUi.session.id,
                            status,
                            sessionUi.session.createdAt,
                            sessionUi.session.lastStatus,
                            sessionUi.session.statusChangedAt,
                        ),
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = status,
                        immunity = immunityByZygote[sessionUi.session.id],
                        children = children,
                        isOrganism = true,
                        unread = sessionUi.unread,
                    )
                } else {
                    UnifiedSidebarItem(
                        id = sessionUi.session.id,
                        displayName = sessionUi.session.sidebarTitle(),
                        sortTime = recencyTracker.observe(
                            sessionUi.session.id,
                            sessionUi.status,
                            sessionUi.session.createdAt,
                            sessionUi.session.lastStatus,
                            sessionUi.session.statusChangedAt,
                        ),
                        isCoding = true,
                        projectName = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }?.name,
                        projectId = sessionUi.session.projectId,
                        codingStatus = sessionUi.status,
                        unread = sessionUi.unread,
                        children = collectVisibleChildren(sessionUi.session.id, setOf(sessionUi.session.id)),
                    )
                }
            }
    }
    return codingItems
}

