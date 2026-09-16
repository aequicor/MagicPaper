package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperFileTransferTest {
    @Test fun nativeTransferContainsOnlyTheExactFileAndNeverFileContents() {
        val file = File("/tmp/Пример с пробелами.app")
        val data = PaperFileTransferable(file.path)
        assertEquals(listOf(DataFlavor.javaFileListFlavor), data.transferDataFlavors.toList())
        assertEquals(listOf(file), data.getTransferData(DataFlavor.javaFileListFlavor))
        assertFailsWith<UnsupportedFlavorException> { data.getTransferData(DataFlavor.stringFlavor) }
        assertFailsWith<IllegalArgumentException> { PaperFileTransferable("relative") }
    }

    @Test fun accessibleActivationRevealsAndDisabledStateIsExposed() {
        for (enabled in listOf(true, false)) {
            var reveals = 0
            val scene = ImageComposeScene(390, 220) { PaperTheme {
                PaperFileTransfer("Перетащить приложение", "/tmp/fixture.app", { reveals++ }, enabled = enabled)
            } }
            try {
                scene.render(0).close()
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val node = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }.single { it.config.contains(SemanticsActions.OnClick) }
                assertEquals(!enabled, node.config.contains(SemanticsProperties.Disabled))
                assertEquals(0, reveals)
                if (enabled) { assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()); assertEquals(1, reveals) }
            } finally { scene.close() }
        }
    }
}
