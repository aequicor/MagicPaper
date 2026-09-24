package io.aequicor.magicpaper.data.coding

import io.aequicor.magicpaper.domain.ExecutablePicker
import io.aequicor.magicpaper.logging.AppLog
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Десктопный выбор исполняемого файла движка: нативный диалог возвращает только путь, содержимое не читается. */
class DesktopExecutablePicker : ExecutablePicker {

    override suspend fun pickExecutable(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val chooser = JFileChooser().apply {
                dialogTitle = "Выберите приложение движка"
                approveButtonText = "Выбрать"
                fileSelectionMode = JFileChooser.FILES_ONLY
                // Windows keeps the CLI as an .exe or an npm .cmd shim; other platforms have no extension to filter by.
                if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
                    fileFilter = FileNameExtensionFilter("Приложения (exe, cmd, bat)", "exe", "cmd", "bat")
            }
            if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@runCatching null
            chooser.selectedFile?.absolutePath
        }.getOrElse { failure ->
            AppLog.error("coding", "executable_picker_failed", failure, emptyMap())
            null
        }
    }
}
