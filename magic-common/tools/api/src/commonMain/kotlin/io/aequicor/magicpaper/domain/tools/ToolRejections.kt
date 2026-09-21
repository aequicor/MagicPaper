package io.aequicor.magicpaper.domain.tools

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

/** Only use at a boundary known to precede effects (or after confirmed safe compensation). */
class ToolArgumentRejection(message: String) : IllegalArgumentException(message), RejectedToolCall
class ToolStateRejection(message: String) : IllegalStateException(message), RejectedToolCall

@OptIn(ExperimentalContracts::class)
inline fun requireTool(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }
    if (!condition) throw ToolArgumentRejection(message())
}

@OptIn(ExperimentalContracts::class)
inline fun checkTool(condition: Boolean, message: () -> String) {
    contract { returns() implies condition }
    if (!condition) throw ToolStateRejection(message())
}
