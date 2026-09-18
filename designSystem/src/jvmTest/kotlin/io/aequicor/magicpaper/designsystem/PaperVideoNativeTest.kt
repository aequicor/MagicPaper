package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.use
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.createVideoPlayerState
import java.awt.EventQueue
import java.io.File
import kotlin.test.*

/** Offline opt-in test of the installed platform codec, separate from ordinary Compose render acceptance. */
@OptIn(ExperimentalComposeUiApi::class)
class PaperVideoNativeTest {
    @Test fun localMp4OpensPausedPlaysSeeksAndDisposes() {
        if (System.getProperty("magicpaper.media.native") != "true") return
        val os = System.getProperty("os.name")
        if (!os.startsWith("Mac") && !os.startsWith("Windows")) return
        val directory = kotlin.io.path.createTempDirectory("paper-video-native-").toFile()
        val file = File(directory, "fixture.mp4")
        val resource = checkNotNull(javaClass.getResourceAsStream("/media/paper-media.mp4"))
        resource.use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        try {
            repeat(2) { cycle ->
                val player = onUi { createVideoPlayerState() }
                try {
                    onUi { player.volume = 0f; player.openUri(file.toURI().toString(), InitialPlayerState.PAUSE) }
                    await(player, "open, cycle $cycle") { player.hasMedia && player.duration > 0.0 }
                    onUi {
                        assertNull(player.error)
                        assertFalse(player.isPlaying, "Opening stored media must not autoplay")
                        player.play()
                    }
                    await(player, "play, cycle $cycle") { player.isPlaying && player.currentTime > 0.0 }
                    onUi { player.pause() }
                    // The pinned Mac player schedules pause and seek on separate coroutines.
                    // Observe pause completion before submitting the next user interaction.
                    await(player, "pause, cycle $cycle") { !player.isPlaying }
                    onUi { player.seekStart(500f); player.seekFinished() }
                    await(player, "seek, cycle $cycle") { player.sliderPos >= 400f && player.currentTime >= player.duration * .4 }
                    onUi {
                        assertFalse(player.isPlaying); assertEquals(0f, player.volume)
                        player.play()
                    }
                    await(player, "play after seek, cycle $cycle") { player.isPlaying && player.currentTime > player.duration * .5 }
                } finally { onUi {
                    player.dispose()
                    assertFalse(player.isPlaying, "Disposal stops active playback")
                    assertFalse(player.hasMedia, "Disposal releases the opened media")
                } }
            }
            val failures = mutableListOf<Throwable?>()
            val scene = onUi { ImageComposeScene(560, 460) { PaperTheme {
                PaperSurface(Modifier.fillMaxSize()) {
                    PaperVideo(file.absolutePath, "Видео при открытии истории", onError = { failures += it })
                }
            } } }
            try {
                repeat(40) { frame -> onUi { scene.render(frame * 32_000_000L).close() }; Thread.sleep(25) }
                assertTrue(failures.isEmpty(), "Native playback reported ${failures.size} failures")
                onUi {
                    val report = File("build/reports/generated-media").apply { mkdirs() }
                    File(report, "native-video-paused.png").writeBytes(scene.render(1_500_000_000L).use { it.encodeToData()!!.use { data -> data.bytes } })
                    scene.action("Смотреть").config[SemanticsActions.OnClick].action!!.invoke()
                }
                val deadline = System.nanoTime() + 12_000_000_000L
                var frame = 48L
                while (!onUi { scene.hasAction("Пауза") } && System.nanoTime() < deadline) {
                    onUi { scene.render(frame++ * 32_000_000L).close() }; Thread.sleep(25)
                }
                onUi { assertTrue(scene.hasAction("Пауза"), "The actual Paper control starts playback before removal") }
            } finally { onUi { scene.close() } }
            assertTrue(failures.isEmpty(), "Opening or disposing the playing PaperVideo reported a failure")
        } finally { directory.deleteRecursively() }
    }

    private fun await(player: VideoPlayerState, operation: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 12_000_000_000L
        while (!onUi { assertNull(player.error, operation); condition() } && System.nanoTime() < deadline) Thread.sleep(25)
        onUi { assertTrue(condition(), "Native $operation did not settle: playing=${player.isPlaying}, loading=${player.isLoading}, " +
            "time=${player.currentTime}, duration=${player.duration}, slider=${player.sliderPos}") }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun visit(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::visit)
        return semanticsOwners.flatMap { visit(it.rootSemanticsNode) }
    }
    private fun ImageComposeScene.hasAction(label: String) = nodes().any { it.matchesAction(label) }
    private fun ImageComposeScene.action(label: String) = nodes().single { it.matchesAction(label) }
    private fun SemanticsNode.matchesAction(label: String) = config.contains(SemanticsActions.OnClick) &&
        (config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains(label) ||
            config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label })
    private fun <T> onUi(block: () -> T): T {
        if (EventQueue.isDispatchThread()) return block()
        var result: Result<T>? = null
        EventQueue.invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }
}
