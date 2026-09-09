package io.aequicor.magicpaper.data.storage

import io.aequicor.magicpaper.domain.FilePicker
import io.aequicor.magicpaper.domain.PickedFile
import io.aequicor.magicpaper.domain.mimeFromName
import java.awt.Toolkit
import java.awt.Image
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Десктопный выбор файлов для вложений: нативный диалог, множественный выбор. */
class DesktopFilePicker : FilePicker {

    override val supported: Boolean = true

    /** Десктоп хранит историю в файлах — потолок свободный. */
    override val maxFileBytes: Long = 20L * 1024 * 1024

    override fun clipboardFiles(): (suspend () -> List<PickedFile>)? = runCatching {
        val contents = Toolkit.getDefaultToolkit().systemClipboard.getContents(null)
            ?: return@runCatching null
        clipboardFileReader(contents, maxFileBytes)
    }.getOrNull()

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

/** Захватывает содержимое при нажатии Paste, читает файлы вне UI-потока. */
internal fun clipboardFileReader(
    contents: Transferable,
    maxFileBytes: Long,
): (suspend () -> List<PickedFile>)? {
    val files = contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
    if (!files && !contents.isDataFlavorSupported(DataFlavor.imageFlavor)) return null
    return {
        withContext(Dispatchers.IO) {
            if (files) {
                (contents.getTransferData(DataFlavor.javaFileListFlavor) as? List<*>)
                    .orEmpty().filterIsInstance<File>().mapNotNull { file ->
                        if (!file.isFile) return@mapNotNull null
                        require(file.length() <= maxFileBytes) { "Файл ${file.name} слишком большой для вложения." }
                        val bytes = file.inputStream().use { it.readNBytes((maxFileBytes + 1).toInt()) }
                        require(bytes.size <= maxFileBytes) { "Файл ${file.name} слишком большой для вложения." }
                        PickedFile(file.name, mimeFromName(file.name), bytes)
                    }
            } else {
                val image = contents.getTransferData(DataFlavor.imageFlavor) as Image
                val width = image.getWidth(null)
                val height = image.getHeight(null)
                require(width > 0 && height > 0 && width.toLong() * height <= 40_000_000) {
                    "Изображение слишком большое или недоступно."
                }
                val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                val graphics = bitmap.createGraphics()
                try {
                    check(graphics.drawImage(image, 0, 0, null)) { "Не удалось прочитать изображение." }
                } finally {
                    graphics.dispose()
                }
                val bytes = ByteArrayOutputStream().use { output ->
                    check(ImageIO.write(bitmap, "png", output))
                    output.toByteArray()
                }
                require(bytes.size <= maxFileBytes) { "Изображение слишком большое для вложения." }
                listOf(PickedFile("clipboard.png", "image/png", bytes))
            }
        }
    }
}
