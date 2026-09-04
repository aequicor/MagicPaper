package io.aequicor.magicpaper.domain

import java.io.File
import javax.swing.JFileChooser

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
            target.writeText(json)
            true
        } else {
            false
        }
    }.getOrElse { fallbackExport(json) }

    override suspend fun import(): String? = runCatching {
        val chooser = JFileChooser().apply { dialogTitle = "Импорт профиля MagicPaper" }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.readText()
        } else {
            null
        }
    }.getOrNull()

    private fun fallbackExport(json: String): Boolean = runCatching {
        val dir = File(System.getProperty("user.home"), ".MagicPaper")
        dir.mkdirs()
        File(dir, "profile-export.json").writeText(json)
        true
    }.getOrDefault(false)
}
