package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperGeneratedMediaTest {
    @Test fun placeholderReplacedByImageKeepsItsReadingSlot() {
        val state = mutableStateOf(PaperMediaState.GENERATING)
        val scene = onUi { ImageComposeScene(360, 400) { PaperTheme {
            PaperGeneratedMedia(PaperMediaKind.IMAGE, state.value, "Иллюстрация", 16f / 9f,
                bitmap = if (state.value == PaperMediaState.READY) ImageBitmap(320, 180) else null, animate = false)
        } } }
        try {
            onUi { scene.render(0).close() }
            val pending = onUi { scene.nodes().single { it.config.getOrNull(SemanticsProperties.StateDescription) == "Создаю изображение…" }.boundsInRoot }
            onUi { state.value = PaperMediaState.READY; scene.render(100_000_000).close() }
            val ready = onUi { scene.nodes().single { it.config.getOrNull(SemanticsProperties.StateDescription) == "Изображение готово" }.boundsInRoot }
            assertEquals(pending, ready)
            assertTrue(ready.right <= 360)
        } finally { onUi { scene.close() } }
    }

    @Test fun galleryRendersAllOperationStatesAtNarrowAndLargeTextSizes() {
        data class Case(val name: String, val state: PaperMediaState, val width: Int = 560, val scale: Float = 1f,
            val kind: PaperMediaKind = PaperMediaKind.IMAGE)
        for (case in listOf(Case("generating", PaperMediaState.GENERATING), Case("ready", PaperMediaState.READY),
            Case("narrow", PaperMediaState.READY, 360), Case("large-text", PaperMediaState.READY, 360, 2f),
            Case("error", PaperMediaState.FAILED, 360), Case("unknown", PaperMediaState.UNKNOWN, 360),
            Case("video-generating", PaperMediaState.GENERATING, kind = PaperMediaKind.VIDEO))) {
            val scene = onUi { ImageComposeScene(case.width, if (case.scale > 1f) 800 else if (case.kind == PaperMediaKind.VIDEO) 740 else 540) {
                CompositionLocalProvider(LocalDensity provides Density(1f, case.scale)) { PaperMediaPreview(case.state, case.kind) }
            } }
            try {
                onUi { repeat(3) { scene.render(it * 32_000_000L).close() } }
                onUi {
                    val media = scene.nodes().single {
                        it.config.contains(SemanticsProperties.StateDescription) &&
                            it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Как соотносятся части целого")
                    }
                    assertTrue(media.boundsInRoot.left >= 0 && media.boundsInRoot.right <= case.width)
                    val directory = File("build/reports/generated-media").apply { mkdirs() }
                    File(directory, "${case.name}.png").writeBytes(scene.render(150_000_000).use { it.encodeToData()!!.use { data -> data.bytes } })
                }
            } finally { onUi { scene.close() } }
        }
    }

    @Test fun videoControlsExposePlaybackSeekingAndSoundWithoutStartingOnRender() {
        var played = 0
        var seek = -1f
        var muted = 0
        val scene = onUi { ImageComposeScene(360, 160) { PaperTheme {
            PaperVideoControls(false, false, 0f, "00:00", "00:05", 1f, { played++ }, { seek = it }, {}, { muted++ })
        } } }
        try {
            onUi { scene.render(0).close() }
            assertEquals(0, played)
            onUi {
                scene.nodes().first { it.config.getOrNull(SemanticsProperties.Text).orEmpty().any { text -> text.text == "Смотреть" } }
                val play = scene.nodes().first { it.config.contains(SemanticsActions.OnClick) &&
                    it.config.getOrNull(SemanticsProperties.Role) == Role.Button &&
                    it.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().none { text -> text.contains("звук") } }
                play.config[SemanticsActions.OnClick].action!!.invoke()
                val slider = scene.nodes().first { it.config.contains(SemanticsActions.SetProgress) }
                slider.config[SemanticsActions.SetProgress].action!!.invoke(500f)
                val sound = scene.nodes().first { it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Выключить звук") }
                sound.config[SemanticsActions.OnClick].action!!.invoke()
            }
            assertEquals(1, played)
            assertEquals(500f, seek)
            assertEquals(1, muted)
        } finally { onUi { scene.close() } }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return semanticsOwners.flatMap { visit(it.unmergedRootSemanticsNode) }
    }
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
