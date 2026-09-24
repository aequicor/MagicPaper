package io.aequicor.magicpaper.ui

import io.aequicor.magicpaper.domain.CodingRecovery

/** Button copy for a failure's recovery, shared by the session transcript and the agent dock. */
val CodingRecovery.actionLabel: String
    get() = when (this) {
        is CodingRecovery.SignIn -> "Войти в ${engine.title}"
    }

/** What the user does elsewhere while the recovery runs. */
val CodingRecovery.pendingLabel: String
    get() = when (this) {
        is CodingRecovery.SignIn -> "Подтвердите вход в браузере"
    }
