package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File

@OptIn(ExperimentalComposeUiApi::class)
internal actual fun Modifier.paperFileDrag(path: String, enabled: Boolean): Modifier = if (!enabled) this else dragAndDropSource {
    DragAndDropTransferData(DragAndDropTransferable(PaperFileTransferable(path)), listOf(DragAndDropTransferAction.Copy))
}

internal class PaperFileTransferable(path: String) : Transferable {
    private val file = File(path).also { require(it.isAbsolute) }
    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(DataFlavor.javaFileListFlavor)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == DataFlavor.javaFileListFlavor
    override fun getTransferData(flavor: DataFlavor): Any {
        if (!isDataFlavorSupported(flavor)) throw UnsupportedFlavorException(flavor)
        return listOf(file)
    }
}
