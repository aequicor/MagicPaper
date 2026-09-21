package io.aequicor.magicpaper.data.computer

/** Construct only from fixed local validation copy, never a native exception or response body. */
internal class ComputerValidationException(message: String) : IllegalArgumentException(message)
internal inline fun computerRequire(value: Boolean, message: () -> String) {
    if (!value) throw ComputerValidationException(message())
}

internal fun safeComputerFailure(failure: Throwable, fallback: String): String = when (failure) {
    is ComputerValidationException, is ApplicationAdapterException, is ComputerAuthorityRejected,
    is ComputerAuthorityUnavailable, is ComputerPermissionException -> failure.message.orEmpty().ifBlank { fallback }
    else -> fallback
}

internal enum class ComputerPermissionFailure { UNSUPPORTED, SCREEN_RECORDING, ACCESSIBILITY }
internal class ComputerPermissionException(reason: ComputerPermissionFailure) : IllegalStateException(when (reason) {
    ComputerPermissionFailure.UNSUPPORTED -> "Управление экраном доступно в desktop: macOS, Windows и Linux X11. Wayland не поддерживается."
    ComputerPermissionFailure.SCREEN_RECORDING -> "Разрешите MagicPaper запись экрана в системных настройках конфиденциальности, затем перезапустите приложение."
    ComputerPermissionFailure.ACCESSIBILITY -> "Разрешите MagicPaper Универсальный доступ в системных настройках конфиденциальности."
})

/** With no operation failure yet, cancellation takes precedence over ordinary cleanup errors. */
internal fun combineComputerCleanup(first: Throwable?, next: Throwable): Throwable = when {
    first == null -> next
    first === next -> first
    first is kotlinx.coroutines.CancellationException -> first.also { it.addSuppressed(next) }
    next is kotlinx.coroutines.CancellationException -> next.also { it.addSuppressed(first) }
    else -> first.also { it.addSuppressed(next) }
}
