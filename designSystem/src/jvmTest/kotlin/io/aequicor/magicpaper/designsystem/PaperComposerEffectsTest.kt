package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class PaperComposerEffectsTest {
    @Test fun documentComposerUsesCompactVerticalInsets() {
        var regular = Rect.Zero
        var document = Rect.Zero
        ImageComposeScene(300, 240) {
            PaperTheme {
                Column {
                    PaperWorkspaceComposer(Modifier.onGloballyPositioned { regular = it.boundsInRoot() }) {
                        Spacer(Modifier.height(50.dp))
                    }
                    PaperWorkspaceComposer(
                        Modifier.onGloballyPositioned { document = it.boundsInRoot() },
                        document = true,
                    ) {
                        Spacer(Modifier.height(50.dp))
                    }
                }
            }
        }.use { scene ->
            repeat(4) { scene.render(it * 16_000_000L).close() }
            assertEquals(16f, regular.height - document.height, 0.1f)
        }
    }

    @Test fun composerAppearsWithAStationaryFade() {
        val scene = onPaperUi { ImageComposeScene(300, 100) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    PaperWorkspaceComposer { Spacer(Modifier.height(50.dp)) }
                }
            }
        } }
        try {
            fun red(atNanos: Long): Int = onPaperUi { scene.render(atNanos).use { image ->
                image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)).getRGB(150, 40) }
            } }.let { (it shr 16) and 255 }

            val first = red(0L)
            Thread.sleep(10)
            val middle = red(90_000_000L)
            Thread.sleep(10)
            val settled = red(240_000_000L)
            assertTrue(first > middle, "Composer must fade in instead of appearing in the first frame: $first, $middle")
            assertTrue(middle > settled, "Composer fade must progress smoothly to the final surface: $middle, $settled")
        } finally {
            onPaperUi { scene.close() }
        }
    }

    @Test fun questionnairePromptPreservesSecretAndDisabledSemantics() {
        for (enabled in listOf(true, false)) {
            var edits = 0
            ImageComposeScene(300, 180) {
                PaperTheme {
                    PaperQuestionnaire {
                        PaperPromptField("secret", { edits++ }, "Ответ", enabled = enabled,
                            visualTransformation = PasswordVisualTransformation())
                    }
                }
            }.use { scene ->
                repeat(5) { scene.render(it * 16_000_000L).close() }
                fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
                val field = scene.semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
                    .first { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                assertNotNull(field.config.getOrNull(SemanticsProperties.Password))
                assertEquals(!enabled, field.config.getOrNull(SemanticsProperties.Disabled) != null)
                field.config.getOrNull(SemanticsActions.SetText)?.action?.invoke(AnnotatedString("updated"))
                assertEquals(if (enabled) 1 else 0, edits)
            }
        }
    }

    @Test fun repeatedTextFieldPressesDoNotFlashTheSurface() {
        for (composer in listOf(false, true)) {
            ImageComposeScene(300, 180) {
                PaperTheme {
                    if (composer) {
                        PaperWorkspaceComposer { PaperPromptField("Text", {}, "Message") }
                    } else {
                        PaperInput("Text", {})
                    }
                }
            }.use { scene ->
                var frame = 0L
                fun surfacePixel(): Int {
                    repeat(20) { scene.render(++frame * 16_000_000L).close() }
                    return scene.render(++frame * 16_000_000L).use { image ->
                        image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)).getRGB(220, 26) }
                    }
                }
                fun pointer(type: PointerEventType) = scene.sendPointerEvent(
                    type, Offset(220f, 26f), type = PointerType.Mouse,
                )
                surfacePixel()
                pointer(PointerEventType.Move)
                pointer(PointerEventType.Press)
                surfacePixel()
                pointer(PointerEventType.Release)
                val focused = surfacePixel()
                repeat(2) {
                    pointer(PointerEventType.Press)
                    assertEquals(focused, surfacePixel(), "Press flashes field (composer=$composer)")
                    pointer(PointerEventType.Release)
                    assertEquals(focused, surfacePixel(), "Release flashes field (composer=$composer)")
                }
            }
        }
    }

    @Test fun composerCastsShadowAboveMessages() {
        ImageComposeScene(300, 180) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(LocalPaperColors.current.canvas).padding(24.dp)) {
                    PaperWorkspaceComposer(Modifier.width(240.dp)) { Spacer(Modifier.height(80.dp)) }
                }
            }
        }.use { scene ->
            repeat(12) { scene.render(it * 32_000_000L).close() }
            val pixels = scene.render(500_000_000L).use { image ->
                image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
            }
            val background = (pixels.getRGB(0, 0) shr 16) and 255
            
            for ((x, y) in listOf(140 to 31, 30 to 72, 258 to 72, 140 to 130)) {
                val red = (pixels.getRGB(x, y) shr 16) and 255
                assertTrue(red < background - 2, "Composer must cast a shadow at $x,$y")
            }
        }
    }
    @Test fun scrolledTopEdgeShadesSharpMessagesOnlyWithin32Dp() {
        ImageComposeScene(120, 80) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White).paperChatTopShadow(true)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(60.dp).fillMaxHeight().background(Color.Black))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                    }
                }
            }
        }.use { scene ->
            val pixels = scene.render(0L).use { image ->
                image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
            }
            fun red(x: Int, y: Int) = (pixels.getRGB(x, y) shr 16) and 255
            assertTrue(red(59, 0) < 60, "The shadow must not blur the light column into the dark one")
            assertTrue(red(59, 8) < 60, "The shadow must not blur the light column into the dark one")
            assertTrue(red(60, 8) - red(59, 8) > 120, "The column boundary stays crisp below the title bar")
            assertTrue(red(60, 0) < 240, "The light column must be shaded at the top edge")
            assertTrue(red(60, 8) < red(60, 24), "Shade must weaken towards 32 dp")
            assertTrue(red(100, 20) < 255, "Shadow extends beyond the old 12 dp height")
            assertEquals(0, red(59, 33))
            assertEquals(255, red(60, 33))
            assertEquals(255, red(100, 33))
        }
    }

    @Test fun titleBarFrostBlursContentInsideTheStripAndKeepsTheTranscriptSharpBelow() {
        ImageComposeScene(120, 120) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White).paperTitleBarFrost(40.dp)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.width(60.dp).fillMaxHeight().background(Color.Black))
                        Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                    }
                }
            }
        }.use { scene ->
            val bytes = scene.render(0L).use { image -> image.encodeToData()!!.use { it.bytes } }
            java.io.File("build/reports/design-effects").apply { mkdirs() }
                .resolve("titlebar-frost.png").writeBytes(bytes)
            val pixels = ImageIO.read(ByteArrayInputStream(bytes))
            fun red(x: Int, y: Int) = (pixels.getRGB(x, y) shr 16) and 255
            assertTrue(red(59, 20) > 100, "Frost must cover dark content behind the title bar")
            assertTrue(kotlin.math.abs(red(59, 20) - red(60, 20)) < 40, "The strip boundary must be blurred")
            assertTrue(red(60, 39) < 240, "A hairline must cut the band from the transcript")
            assertEquals(0, red(59, 44), "Below the band the transcript stays sharp, never half-blurred")
            assertEquals(255, red(60, 44))
            assertEquals(0, red(59, 62))
            assertEquals(255, red(60, 62))
        }
    }

    @Test fun insetPageChromeDoesNotReplayThePageForAnInvisibleBackdrop() {
        var pageDraws = 0
        ImageComposeScene(120, 160) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White).paperTitleBarFrost(40.dp, blurContent = false)) {
                    Box(Modifier.fillMaxSize().drawBehind { pageDraws++ }.padding(top = 46.dp)) {
                        Row(Modifier.fillMaxSize()) {
                            Box(Modifier.width(60.dp).fillMaxHeight().background(Color.Black))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                        }
                    }
                }
            }
        }.use { scene ->
            val bytes = scene.render(0L).use { image -> image.encodeToData()!!.use { it.bytes } }
            assertEquals(1, pageDraws, "Inset articles must be drawn once, without a second full-window capture")
            java.io.File("build/reports/design-effects").apply { mkdirs() }
                .resolve("titlebar-inset.png").writeBytes(bytes)
            val pixels = ImageIO.read(ByteArrayInputStream(bytes))
            fun red(x: Int, y: Int) = (pixels.getRGB(x, y) shr 16) and 255
            assertTrue(red(60, 39) < 240, "The title bar still has its divider")
            assertEquals(0, red(59, 60), "The article remains sharp")
            assertEquals(255, red(60, 60))
        }
    }

    @Test fun chatTopShadowStartsBelowTheOffset() {
        ImageComposeScene(120, 120) {
            PaperTheme {
                Box(Modifier.fillMaxSize().background(Color.White)
                    .paperChatTopShadow(true, effectHeight = 32.dp, topOffset = 40.dp))
            }
        }.use { scene ->
            val pixels = scene.render(0L).use { image ->
                image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
            }
            fun red(y: Int) = (pixels.getRGB(60, y) shr 16) and 255
            assertEquals(255, red(20), "No shadow above the offset")
            assertTrue(red(44) < 255, "Shadow must start at the offset")
            assertEquals(255, red(80))
        }
    }

    @Test fun shadowStartsAtTopAndReachesBelowUnshadedCardsAtDifferentScales() {
        for (scale in listOf(1f, 2f)) for (cardHeight in listOf(40, 80)) {
            val extent = cardHeight + 12
            ImageComposeScene(240, 300, density = androidx.compose.ui.unit.Density(scale)) {
                PaperTheme {
                    Box(Modifier.fillMaxSize()) {
                        Row(Modifier.fillMaxSize().paperChatTopShadow(true, effectHeight = extent.dp)) {
                            Box(Modifier.width(60.dp).fillMaxHeight().background(Color.Black))
                            Box(Modifier.weight(1f).fillMaxHeight().background(Color.White))
                        }
                        Box(Modifier.padding(start = 20.dp).width(80.dp).height(cardHeight.dp).background(Color.White))
                    }
                }
            }.use { scene ->
                val pixels = scene.render(0L).use { image ->
                    image.encodeToData()!!.use { ImageIO.read(ByteArrayInputStream(it.bytes)) }
                }
                val boundary = (60 * scale).toInt()
                fun red(x: Int, y: Int) = (pixels.getRGB(x, y) shr 16) and 255
                assertTrue(red(0, 0) > 0, "Shadow must touch the left and top boundaries")
                assertTrue(red((110 * scale).toInt(), 0) < 220, "Shadow must start above the card bottom")
                for (y in 0 until (cardHeight * scale).toInt()) {
                    assertEquals(255, red(boundary, y), "Overlay must remain untouched by the shadow")
                }
                assertTrue(red(boundary, ((cardHeight + 1) * scale).toInt()) < 255, "Effect must continue below the card")
                assertEquals(0, red(boundary - 1, ((extent + 1) * scale).toInt()))
                assertEquals(255, red(boundary, ((extent + 1) * scale).toInt()))
            }
        }
    }

}
