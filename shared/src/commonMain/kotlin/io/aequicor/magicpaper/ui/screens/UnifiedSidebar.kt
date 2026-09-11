package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.aequicor.magicpaper.designsystem.LocalPaperColors
import io.aequicor.magicpaper.designsystem.LocalPaperTypography
import io.aequicor.magicpaper.designsystem.PaperButton
import io.aequicor.magicpaper.designsystem.PaperButtonKind
import io.aequicor.magicpaper.designsystem.PaperDivider
import io.aequicor.magicpaper.designsystem.PaperText
import io.aequicor.magicpaper.designsystem.PaperTextRole
import io.aequicor.magicpaper.designsystem.paperClickable
import io.aequicor.magicpaper.domain.ChatSession
import io.aequicor.magicpaper.domain.CodingSessionStatus
import io.aequicor.magicpaper.ui.CodingUi
import io.aequicor.magicpaper.ui.MagicPaperViewModel

/** Элемент единого списка боковой панели. */
internal data class UnifiedSidebarItem(
    val id: String,
    val displayName: String,
    val sortTime: Long,
    val isCoding: Boolean,
    val projectName: String? = null,
    val codingStatus: CodingSessionStatus? = null,
)

/** Элементы единого списка: чаты и кодинг-сессии, отсортированные по обновлению. */
@Composable
internal fun rememberUnifiedItems(
    chatSessions: List<ChatSession>,
    coding: CodingUi,
): List<UnifiedSidebarItem> {
    val chatItems = chatSessions.map { session ->
        UnifiedSidebarItem(
            id = session.id,
            displayName = session.title,
            sortTime = session.updatedAt,
            isCoding = false,
        )
    }
    val codingItems = coding.sessions
        .filter { it.session.parentSessionId == null }
        .map { sessionUi ->
            val project = coding.projects.firstOrNull { it.id == sessionUi.session.projectId }
            UnifiedSidebarItem(
                id = sessionUi.session.id,
                displayName = sessionUi.session.name,
                sortTime = sessionUi.session.createdAt,
                isCoding = true,
                projectName = project?.name,
                codingStatus = sessionUi.status,
            )
        }
    return remember(chatItems, codingItems) {
        (chatItems + codingItems).sortedByDescending { it.sortTime }
    }
}

/** Единая боковая панель: чаты и кодинг-сессии в одном списке. */
@Composable
fun UnifiedSidebar(
    vm: MagicPaperViewModel,
    chatSessions: List<ChatSession>,
    coding: CodingUi,
    selectedId: String?,
    viewingCoding: Boolean,
) {
    val items = rememberUnifiedItems(chatSessions, coding)
    Column(Modifier.width(260.dp).fillMaxHeight()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PaperText("Сессии", role = PaperTextRole.TITLE)
        }
        PaperDivider()
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            items(items, key = { "${if (it.isCoding) "c" else "h"}:${it.id}" }) { item ->
                val selected = item.id == selectedId &&
                    (item.isCoding == viewingCoding)
                UnifiedSessionRow(item, selected, {
                    vm.selectUnifiedSession(item.id, item.isCoding)
                })
            }
        }
        PaperDivider()
        Column(Modifier.padding(8.dp)) {
            PaperButton(
                "✦ Новый чат",
                vm::newSession,
                Modifier.fillMaxWidth(),
                kind = PaperButtonKind.QUIET,
            )
            Spacer(Modifier.height(2.dp))
            PaperButton(
                "📂 Новый проект",
                vm::addCodingProject,
                Modifier.fillMaxWidth(),
                kind = PaperButtonKind.QUIET,
            )
        }
    }
}

@Composable
private fun UnifiedSessionRow(
    item: UnifiedSidebarItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .paperClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.isCoding && item.codingStatus != null) {
            ActivityDot(item.codingStatus, size = 8)
            Spacer(Modifier.width(8.dp))
        } else if (!item.isCoding) {
            PaperText("✦", role = PaperTextRole.LABEL, color = LocalPaperColors.current.secondaryText)
            Spacer(Modifier.width(8.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
            PaperText(
                item.displayName,
                style = LocalPaperTypography.current.body,
                color = if (selected) LocalPaperColors.current.action else LocalPaperColors.current.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.projectName != null) {
                PaperText(
                    item.projectName,
                    style = LocalPaperTypography.current.label,
                    color = LocalPaperColors.current.secondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}


