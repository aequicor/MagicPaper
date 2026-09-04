package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.ProjectDirPicker
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Десктопный выбор папки проекта: нативный диалог в режиме «только каталоги». */
class DesktopProjectDirPicker : ProjectDirPicker {

    override suspend fun pickDirectory(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите папку проекта"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                approveButtonText = "Выбрать"
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.absolutePath
            } else {
                null
            }
        }.getOrNull()
    }
}
