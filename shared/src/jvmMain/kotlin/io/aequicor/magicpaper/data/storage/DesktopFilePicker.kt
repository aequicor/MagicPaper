package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.PickedFile
import io.aequicor.magicpaper.domain.mimeFromName
import java.nio.file.Files
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Десктопный выбор файлов для вложений: нативный диалог, множественный выбор. */
class DesktopFilePicker : FilePicker {

    override val supported: Boolean = true

    /** Десктоп хранит историю в файлах — потолок свободный. */
    override val maxFileBytes: Long = 20L * 1024 * 1024

    override suspend fun pickFiles(): List<PickedFile> = withContext(Dispatchers.IO) {
        runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите файлы для вложения"
                isMultiSelectionEnabled = true
                approveButtonText = "Прикрепить"
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                return@runCatching emptyList<PickedFile>()
            }
            chooser.selectedFiles.mapNotNull { file ->
                runCatching {
                    if (file.length() > maxFileBytes) return@runCatching null
                    val mime = runCatching { Files.probeContentType(file.toPath()) }
                        .getOrNull() ?: mimeFromName(file.name)
                    PickedFile(file.name, mime, file.readBytes())
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }
}
