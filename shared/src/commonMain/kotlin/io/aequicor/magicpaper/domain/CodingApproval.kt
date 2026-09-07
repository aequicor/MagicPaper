package io.aequicor.magicpaper.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CodingApprovalDecision { ALLOW_ONCE, DENY }
enum class CodingApprovalKind { COMMAND, NETWORK, FILE_CHANGE, PERMISSIONS }

/** A live engine request. Approval is never restored from saved conversation history. */
data class CodingApproval(
    val id: String,
    val sessionId: String,
    val projectId: String,
    val sessionName: String,
    val kind: CodingApprovalKind,
    val reason: String,
    val details: String,
    val canAllow: Boolean = true,
    val submitting: Boolean = false,
    val error: String? = null,
) {
    val title: String get() = when (kind) {
        CodingApprovalKind.COMMAND -> "Подтвердите выполнение команды"
        CodingApprovalKind.NETWORK -> "Подтвердите доступ к сети"
        CodingApprovalKind.FILE_CHANGE -> "Подтвердите изменение файлов"
        CodingApprovalKind.PERMISSIONS -> "Подтвердите дополнительный доступ"
    }
    val allowLabel: String get() = if (kind == CodingApprovalKind.PERMISSIONS) "Разрешить на этот запрос" else "Разрешить один раз"
}

internal val noCodingApprovals: StateFlow<List<CodingApproval>> =
    MutableStateFlow<List<CodingApproval>>(emptyList()).asStateFlow()
