package io.aequicor.magicpaper.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.PaperTheme
import io.aequicor.magicpaper.designsystem.PaperSurface
import io.aequicor.magicpaper.designsystem.PaperSurfaceKind
import io.aequicor.magicpaper.domain.*
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class MediaSettingsRenderTest {
    private val output = File("build/reports/media-settings").apply { mkdirs() }

    @Test fun settingsStatesRenderWithoutProbesAndRemainReadableAtNarrowLargeText() {
        data class Case(val name: String, val state: MediaSettingsPreviewState, val width: Int = 390, val scale: Float = 1f)
        val cases = listOf(
            Case("default", MediaSettingsPreviewState.DEFAULT, 900),
            Case("checked", MediaSettingsPreviewState.CHECKED, 900),
            Case("checking", MediaSettingsPreviewState.CHECKING),
            Case("error", MediaSettingsPreviewState.ERROR),
            Case("unsupported", MediaSettingsPreviewState.UNSUPPORTED),
            Case("empty", MediaSettingsPreviewState.EMPTY),
            Case("narrow", MediaSettingsPreviewState.DEFAULT),
            Case("large-text", MediaSettingsPreviewState.DEFAULT, scale = 2f),
        )
        for (case in cases) ImageComposeScene(case.width, 1600, density = Density(1f, case.scale)) {
            PaperTheme { PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                MediaSettingsPreviewContent(case.state, onCheck = { error("Rendering started a paid probe") },
                    onDisable = { error("Rendering disabled a connection") }, onSelectionChange = { _, _ -> error("Rendering changed a draft") })
            } }
        }.use { scene ->
            scene.settle()
            val nodes = scene.nodes()
            assertTrue(nodes.any { "Создание изображений" in it.texts() }, case.name)
            assertTrue(nodes.any { "Создание видео" in it.texts() }, case.name)
            val label = if (case.state == MediaSettingsPreviewState.CHECKING) "Проверяем…" else "Сохранить и проверить"
            val probes = scene.actions(label)
            assertEquals(2, probes.size, case.name)
            val disabled = case.state in listOf(MediaSettingsPreviewState.CHECKING, MediaSettingsPreviewState.EMPTY, MediaSettingsPreviewState.UNSUPPORTED)
            assertTrue(probes.all { it.config.contains(SemanticsProperties.Disabled) == disabled }, case.name)
            if (case.state == MediaSettingsPreviewState.CHECKED) assertEquals(2, nodes.count { it.ownText() == "Подключение работает" })
            if (case.state == MediaSettingsPreviewState.ERROR) assertEquals(2, nodes.count { it.ownText().startsWith("Подключение недоступно.") })
            scene.assertReadable(case.width, case.name)
            scene.capture(File(output, "${case.name}.png"))
            if (case.name == "large-text") {
                val scroll = scene.nodes().single { it.config.contains(SemanticsActions.ScrollBy) && it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
                assertTrue(scroll.config[SemanticsProperties.VerticalScrollAxisRange].maxValue() > 0f)
                assertTrue(scroll.config[SemanticsActions.ScrollBy].action!!.invoke(0f, 10_000f))
                scene.settle()
                val videoProbe = scene.actions(label).last()
                assertTrue(videoProbe.boundsInRoot.top >= 0 && videoProbe.boundsInRoot.bottom <= 1600)
                scene.assertReadable(case.width, case.name)
                scene.capture(File(output, "large-text-bottom.png"))
            }
        }
    }

    @Test fun explicitActionsKeepImageAndVideoSettingsIndependent() {
        val checked = mutableListOf<MediaKind>()
        val disabled = mutableListOf<MediaKind>()
        var selections by mutableStateOf(MediaKind.entries.associateWith(::mediaPreviewSelection))
        val saved = selections
        ImageComposeScene(900, 1800) {
            PaperTheme { PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MediaKind.entries.forEach { kind ->
                        MediaSettingsForm(kind, selections.getValue(kind), saved.getValue(kind), mediaPreviewProfiles,
                            mediaPreviewStatus(kind, if (kind == MediaKind.IMAGE) MediaSettingsPreviewState.CHECKING else MediaSettingsPreviewState.CHECKED),
                            supported = true, saving = false, onSelectionChange = { selections = selections + (kind to it) },
                            onCheck = { checked += kind }, onDisable = { disabled += kind })
                    }
                }
            } }
        }.use { scene ->
            scene.settle()
            assertTrue(checked.isEmpty()); assertTrue(disabled.isEmpty()); assertEquals(saved, selections)
            assertTrue(scene.actions("Проверяем…").single().config.contains(SemanticsProperties.Disabled))
            scene.click(scene.actions("Сохранить и проверить").single())
            assertEquals(listOf(MediaKind.VIDEO), checked)
            scene.click(scene.actions("Отключить").first())
            assertEquals(listOf(MediaKind.IMAGE), disabled)

            // A new image draft must remain usable while an old saved image probe is in flight.
            val imageModel = scene.nodes().first { it.config.contains(SemanticsActions.SetText) }
            assertTrue(imageModel.config[SemanticsActions.SetText].action!!.invoke(androidx.compose.ui.text.AnnotatedString("image-replacement")))
            scene.settle()
            assertEquals("image-replacement", selections.getValue(MediaKind.IMAGE).modelId)
            assertEquals(saved.getValue(MediaKind.VIDEO), selections.getValue(MediaKind.VIDEO))
            assertEquals(2, scene.actions("Сохранить и проверить").size)
            assertTrue(scene.actions("Проверяем…").isEmpty())
            scene.click(scene.actions("Сохранить и проверить").first())
            assertEquals(listOf(MediaKind.VIDEO, MediaKind.IMAGE), checked)
        }
    }

    @Test fun unknownProbeOnlyRecoversAfterExplicitActionAndDisablesDuplicateRecovery() {
        val result = CompletableDeferred<GeneratedMedia?>()
        val media = GeneratedMedia("known-probe", MediaKind.IMAGE, MediaPhase.UNKNOWN, "Пробный результат")
        var recoveries = 0
        ImageComposeScene(390, 700) {
            PaperTheme { PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                MediaConnectionPreview(media, false, { error("Unknown probe has no file to read") },
                    { error("Unknown probe has no player") }, { id ->
                        assertEquals(media.id, id)
                        recoveries++
                        result.await()
                    })
            } }
        }.use { scene ->
            scene.settle()
            assertEquals(0, recoveries)
            scene.click(scene.actions("Проверить результат").single())
            assertEquals(1, recoveries)
            assertTrue(scene.actions("Проверить результат").isEmpty())
            result.complete(media.copy(phase = MediaPhase.CANCELLED))
            scene.settle()
            assertEquals(1, recoveries)
            assertTrue(scene.nodes().any { it.ownText() == "Создание остановлено" })
            scene.capture(File(output, "recovered-probe.png"))
        }
    }

    @Test fun failedPreviewReadRetriesOnlyTheSavedFile() {
        val asset = MediaAsset("fixture", "image/png", 8)
        val media = GeneratedMedia("saved-probe", MediaKind.IMAGE, MediaPhase.READY, asset = asset)
        var reads = 0
        ImageComposeScene(390, 700) {
            PaperTheme { PaperSurface(Modifier.fillMaxSize(), kind = PaperSurfaceKind.CANVAS) {
                MediaConnectionPreview(media, false, { reads++; error("Fixture read failed") },
                    { error("Image has no video player") }, { error("Reading an existing image must not recover or generate") })
            } }
        }.use { scene ->
            scene.settle()
            assertEquals(1, reads)
            scene.click(scene.actions("Загрузить снова").single())
            assertEquals(2, reads)
            assertTrue(scene.nodes().any { it.ownText().startsWith("Не удалось открыть пробный результат.") })
            scene.capture(File(output, "preview-read-error.png"))
        }
    }

    private var frameTime = 0L
    private fun nextFrame(): Long { frameTime += 32_000_000L; return frameTime }
    private fun ImageComposeScene.settle() { repeat(8) { render(nextFrame()).close() } }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
        return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
    }
    private fun SemanticsNode.ownText() = config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
    private fun SemanticsNode.texts(): List<String> = listOf(ownText()) + children.flatMap { it.texts() }
    private fun ImageComposeScene.actions(label: String) = nodes().filter {
        it.config.contains(SemanticsActions.OnClick) && label in it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }
    private fun ImageComposeScene.click(node: SemanticsNode) {
        assertFalse(node.config.contains(SemanticsProperties.Disabled))
        sendPointerEvent(PointerEventType.Press, node.boundsInRoot.center)
        sendPointerEvent(PointerEventType.Release, node.boundsInRoot.center)
        settle()
    }
    private fun ImageComposeScene.assertReadable(width: Int, name: String) {
        nodes().filter { it.config.contains(SemanticsActions.OnClick) && it.boundsInRoot.height > 0f }.forEach {
            assertTrue(it.boundsInRoot.left >= 0 && it.boundsInRoot.right <= width, "$name: control outside viewport")
        }
        nodes().filter { it.config.contains(SemanticsProperties.Text) }.forEach { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
            results.forEach { assertFalse(it.hasVisualOverflow, "$name: clipped text '${node.ownText()}'") }
        }
    }
    private fun ImageComposeScene.capture(file: File) = render(nextFrame()).use { image ->
        file.writeBytes(image.encodeToData()!!.use { it.bytes })
    }
}
