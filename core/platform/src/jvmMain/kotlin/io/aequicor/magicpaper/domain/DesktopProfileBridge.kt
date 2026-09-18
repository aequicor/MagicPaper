package io.aequicor.magicpaper.domain

import java.io.File
import javax.swing.JFileChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.aequicor.magicpaper.logging.AppLog

/**
 * Десктопный мост профиля: диалог выбора файла (Swing).
 * Если диалог недоступен (например, headless) — фолбэк на ~/.MagicPaper.
 */
class DesktopProfileBridge : ProfileBridge {

    override val supportsFilePicker = true

    override suspend fun export(json: String): Boolean = runCatching {
        val chooser = JFileChooser().apply {
            dialogTitle = "Экспорт профиля MagicPaper"
            selectedFile = File("magicpaper-profile.json")
        }
        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
            val target = chooser.selectedFile.let {
                if (it.name.endsWith(".json")) it else File(it.parentFile, it.name + ".json")
            }
            withContext(Dispatchers.IO) { target.writeText(json) }
            true
        } else {
            false
        }
    }.getOrElse { failure ->
        if (failure is CancellationException) throw failure
        if (failure is java.awt.HeadlessException) {
            AppLog.info("ProfileBridge", "export.headless_fallback")
            fallbackExport(json)
        } else throw failure
    }

    override suspend fun import(): String? {
        val chooser = JFileChooser().apply { dialogTitle = "Импорт профиля MagicPaper" }
        return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            val source = chooser.selectedFile
            withContext(Dispatchers.IO) { source.readText() }
        } else {
            null
        }
    }

    private suspend fun fallbackExport(json: String): Boolean = withContext(Dispatchers.IO) {
        val dir = File(System.getProperty("user.home"), ".MagicPaper")
        dir.mkdirs()
        File(dir, "profile-export.json").writeText(json)
        true
    }
}
