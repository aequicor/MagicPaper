package io.aequicor.magicpaper.domain

/** Нативный диалог выбора исполняемого файла движка; null — человек отменил выбор. */
interface ExecutablePicker {
    suspend fun pickExecutable(): String?
}
