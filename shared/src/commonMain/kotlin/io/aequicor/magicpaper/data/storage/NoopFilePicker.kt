package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.PickedFile

/** Платформы без выбора файлов (пока Android): интерфейс честно сообщает об этом. */
object NoopFilePicker : FilePicker {
    override val supported: Boolean = false
    override val maxFileBytes: Long = 0
    override suspend fun pickFiles(): List<PickedFile> = emptyList()
}
