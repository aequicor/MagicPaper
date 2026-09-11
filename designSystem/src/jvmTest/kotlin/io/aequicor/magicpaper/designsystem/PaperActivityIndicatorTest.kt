package io.aequicor.magicpaper.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A diamond must never read as a different state than the circle it replaces: the tone's fill,
 * its darker outline and the working pulse are shared, and only the silhouette differs. The
 * interactive variant adds exactly the platform hit area, keyboard activation and a selection
 * that survives the pointer leaving the row.
 *
 * Pulse geometry is measured on a larger glyph than the sidebar's 10 dp, where one pixel is a
 * large share of the shape and the shared easing curve would be hidden by quantization.
 */
@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class PaperActivityIndicatorTest {
    private companion object {
        const val page = 40
        const val mid = page / 2
        const val white = 0xFFFFFF // the raster's page background, without alpha
        const val frame = 16_000_000L

        /** One full Reverse iteration of the pulse: tween(700) forward and back. */
        val cycleTimes = (0L..1_400_000_000L step 100_000_000L).toList()

        fun inked(image: BufferedImage, row: Int, width: Int): List<Boolean> =
            (0 until width).map { column -> image.getRGB(column, row) and 0xFFFFFF != white }

        /** Ink measurements of a raster: only pixels that differ from the page are counted. */
        class Glyph(private val image: BufferedImage) {
            private val frameWidth get() = image.width
            private val frameHeight get() = image.height
            private val rows by lazy { (0 until frameHeight).map { inked(image, it, frameWidth) } }

            val left = (0 until frameWidth).first { column -> rows.any { it[column] } }
            val right = (0 until frameWidth).last { column -> rows.any { it[column] } }
            val top = (0 until frameHeight).first { row -> rows[row].any { it } }
            val bottom = (0 until frameHeight).last { row -> rows[row].any { it } }

            fun rgb(x: Int, y: Int): Int = image.getRGB(x, y) and 0xFFFFFF
            fun widthAt(row: Int): Int = rows[row].count { it }
            val centreRgb get() = rgb(frameWidth / 2, frameHeight / 2)
            val inkCount get() = rows.sumOf { row -> row.count { it } }
            val span get() = right - left
            val height get() = bottom - top

            /**
             * Darkest inked pixel anywhere in the glyph. A diamond's four acute vertices are
             * always an antialiased blend, but its long diagonals carry the full outline, so
             * this reads the outline colour on both silhouettes the same way.
             */
            val darkestRgb: Int
                get() = (top..bottom).flatMap { row ->
                    (left..right).filter { rows[row][it] }.map { rgb(it, row) }
                }.minBy { brightness(it) }
        }

        /** Renders one glyph centred on paper-white and returns the frame at [atNanos]. */
        fun raster(shape: PaperActivityShape, tone: PaperActivityTone, running: Boolean,
            atNanos: Long = 0L, glyph: androidx.compose.ui.unit.Dp = 10.dp): BufferedImage =
            ImageComposeScene(page, page) {
                PaperTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PaperActivityIndicator(tone, "статус", running = running,
                                size = glyph, shape = shape)
                        }
                    }
                }
            }.use { scene ->
                var time = 0L
                while (time < atNanos) {
                    scene.render(time).close()
                    time += frame
                }
                scene.render(atNanos).use { image -> image.png() }
            }

        /** Inked area of one glyph across a whole pulse iteration. */
        fun areasAcrossCycle(shape: PaperActivityShape, tone: PaperActivityTone,
            glyph: androidx.compose.ui.unit.Dp): List<Int> =
            ImageComposeScene(page, page) {
                PaperTheme {
                    Box(Modifier.fillMaxSize().background(Color.White)) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PaperActivityIndicator(tone, "статус", running = true,
                                size = glyph, shape = shape)
                        }
                    }
                }
            }.use { scene -> cycleTimes.map { time -> Glyph(scene.render(time).use { it.png() }).inkCount } }

        private fun org.jetbrains.skia.Image.png(): BufferedImage =
            encodeToData()!!.use { data -> ImageIO.read(ByteArrayInputStream(data.bytes)) }

        fun rgbOf(color: Color): Int {
            fun channel(value: Float) = (value * 255).toInt().coerceIn(0, 255)
            return (channel(color.red) shl 16) or (channel(color.green) shl 8) or channel(color.blue)
        }

        fun brightness(rgb: Int): Int =
            (((rgb shr 16) and 0xFF) * 2126 + ((rgb shr 8) and 0xFF) * 7152 + (rgb and 0xFF) * 722) / 10000

        /** Sum of channel deltas: tolerant of the antialiasing along a small shape's edge. */
        fun distance(actual: Int, expected: Int): Int =
            abs(((actual shr 16) and 0xFF) - ((expected shr 16) and 0xFF)) +
                abs(((actual shr 8) and 0xFF) - ((expected shr 8) and 0xFF)) +
                abs((actual and 0xFF) - (expected and 0xFF))

        fun ImageComposeScene.nodes(): List<SemanticsNode> {
            fun walk(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::walk)
            return semanticsOwners.flatMap { walk(it.unmergedRootSemanticsNode) }
        }
    }

    @Test fun bothSilhouettesShareFillAndOutlineWhileOnlyShapeDiffers() {
        val colors = PaperColors()
        for (tone in PaperActivityTone.values()) {
            val circle = Glyph(raster(PaperActivityShape.CIRCLE, tone, running = false))
            val diamond = Glyph(raster(PaperActivityShape.DIAMOND, tone, running = false))
            val (fill, edge) = colors.activityIndicatorColors(tone)
            assertEquals(circle.centreRgb, diamond.centreRgb, "$tone fill must be identical")
            assertEquals(0, distance(circle.centreRgb, rgbOf(fill)), "$tone centre is the tone fill")
            assertEquals(circle.left, diamond.left, "$tone horizontal extent must match")
            assertEquals(circle.right, diamond.right, "$tone horizontal extent must match")
            assertTrue(abs(circle.height - circle.span) <= 1, "$tone circle stays symmetric")
            assertTrue(abs(diamond.height - diamond.span) <= 1, "$tone diamond stays symmetric")
            assertTrue(brightness(rgbOf(edge)) < brightness(rgbOf(fill)),
                "$tone outline must stay the darker edge")
            // A 1 dp outline never reaches its pure colour on a 10 dp glyph: the circle has
            // vertical tangents and does so closely, while every diamond edge runs at 45° and
            // is therefore always an antialiased blend of edge over fill. The shared contract is
            // that both paint the tone's own outline and keep it readable against the fill.
            assertTrue(distance(circle.darkestRgb, rgbOf(edge)) <= 60,
                "$tone circle outline must resolve to the edge colour: ${circle.darkestRgb}")
            for ((name, glyph) in listOf("circle" to circle, "diamond" to diamond)) {
                assertTrue(brightness(glyph.darkestRgb) < brightness(glyph.centreRgb) - 20,
                    "$name $tone must paint a darker outline than its fill: ${glyph.darkestRgb}")
            }
        }
    }

    @Test fun diamondSilhouetteDiffersFromTheCircleItReplaces() {
        val tone = PaperActivityTone.WORKING
        val circle = Glyph(raster(PaperActivityShape.CIRCLE, tone, running = false))
        val diamond = Glyph(raster(PaperActivityShape.DIAMOND, tone, running = false))
        val nearTop = circle.top + 2
        assertTrue(circle.widthAt(nearTop) > diamond.widthAt(nearTop) + 1,
            "A circle keeps its width near the top while a diamond tapers to its vertex")
        assertTrue(circle.inkCount > diamond.inkCount,
            "The diamond's points hold less ink: ${circle.inkCount} vs ${diamond.inkCount}")
        assertTrue(diamond.widthAt(diamond.top) <= 2, "The diamond starts at its vertex")
    }

    @Test fun pulseIsSharedByBothSilhouettes() {
        val pulseSize = 32.dp
        // The midpoint sample of one Reverse iteration: tween(700) turns around after 700 ms.
        val peak = cycleTimes.size / 2
        for (tone in listOf(PaperActivityTone.WORKING, PaperActivityTone.ATTENTION)) {
            val curves = PaperActivityShape.values().map { shape ->
                val resting = Glyph(raster(shape, tone, running = false, glyph = pulseSize)).inkCount
                shape to areasAcrossCycle(shape, tone, pulseSize).map { it.toFloat() / resting }
            }
            for ((shape, ratios) in curves) {
                assertTrue(ratios.distinct().size >= 4, "$shape $tone must actually pulse: $ratios")
                assertEquals(ratios.first(), ratios.last(), absoluteTolerance = 0.02f,
                    "$shape $tone iteration must loop seamlessly: $ratios")
                assertTrue(ratios.first() <= ratios.min() + 0.02f &&
                    ratios.last() <= ratios.min() + 0.02f,
                    "$shape $tone must open and close at the trough: $ratios")
                assertEquals(1f, ratios[peak], absoluteTolerance = 0.02f,
                    "$shape $tone must reach its resting size at the turning point: $ratios")
                assertTrue(ratios.take(peak + 1).zipWithNext().all { (a, b) -> b >= a - 0.02f },
                    "$shape $tone must grow toward the turning point: $ratios")
                assertTrue(ratios.drop(peak).zipWithNext().all { (a, b) -> b <= a + 0.02f },
                    "$shape $tone must shrink after the turning point: $ratios")
                assertTrue(ratios.min() in 0.58f..0.80f,
                    "$shape $tone must dip to the shared .82 scale, squared for area: $ratios")
            }
            // Quantization plateaus differ per silhouette, so the shared curve is asserted on
            // its depth and its turning point rather than on every sampled frame.
            val (circle, diamond) = curves.map { it.second }
            assertTrue(abs(circle.min() - diamond.min()) <= 0.06f,
                "$tone pulse depth must be shared: $circle vs $diamond")
            assertEquals(circle[peak], diamond[peak], absoluteTolerance = 0.02f,
                "$tone pulse phase must be shared: $circle vs $diamond")
        }
    }

    @Test fun pulseScalesTheGlyphButNeverChangesItsTone() {
        for (shape in PaperActivityShape.values()) {
            val resting = Glyph(raster(shape, PaperActivityTone.WORKING, running = false))
            val midCycle = Glyph(raster(shape, PaperActivityTone.WORKING, running = true,
                atNanos = 350_000_000L))
            assertEquals(resting.centreRgb, midCycle.centreRgb,
                "$shape pulse scales the glyph, never its tone")
        }
    }

    @Test fun indicatorButtonPublishesOneActionAndKeepsTheGlyphDecorative() {
        val focus = FocusRequester()
        var clicks = 0
        ImageComposeScene(140, 60) {
            PaperTheme {
                PaperActivityIndicatorButton(
                    tone = PaperActivityTone.ATTENTION, label = "Иммунитет · ждёт ответа",
                    onClick = { clicks++ }, modifier = Modifier.focusRequester(focus),
                )
            }
        }.use { scene ->
            repeat(4) { scene.render((it + 1) * frame).close() }
            val labelled = scene.nodes().filter {
                it.config.getOrNull(SemanticsProperties.ContentDescription) ==
                    listOf("Иммунитет · ждёт ответа")
            }
            assertEquals(1, labelled.size, "The decorative glyph must not publish a second label")
            val control = labelled.single()
            assertEquals(Role.Button, control.config[SemanticsProperties.Role])
            assertEquals(true, control.config[SemanticsActions.OnClick].action?.invoke())
            assertEquals(1, clicks)
            assertTrue(focus.requestFocus())
            scene.render(64_000_000L).close()
            assertTrue(scene.sendKeyEvent(KeyEvent(Key.Enter, KeyEventType.KeyDown)))
            scene.render(80_000_000L).close()
            assertEquals(2, clicks, "Enter must activate the indicator like any Paper action")
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyUp))
            scene.render(96_000_000L).close()
            assertEquals(3, clicks, "Space must activate the indicator like any Paper action")
        }
    }

    @Test fun indicatorButtonKeepsPlatformHitAreaAndPersistentSelection() {
        for (platform in listOf(PaperPlatform.MACOS, PaperPlatform.WINDOWS)) {
            val controlHeight = if (platform == PaperPlatform.MACOS) 28 else 32
            val policy = PaperPlatformPolicy.Fallback.copy(
                platform = platform,
                density = PaperDensity(controlHeight.dp, controlHeight.dp, (controlHeight + 2).dp, 16.dp),
            )
            val selectedRgb = rgbOf(PaperColors().selected)
            var hit = Rect.Zero
            var ink = Rect.Zero
            for (selected in listOf(false, true)) {
                ImageComposeScene(120, 120) {
                    PaperTheme {
                        CompositionLocalProvider(
                            LocalDensity provides Density(1f),
                            LocalPaperPlatformPolicy provides policy,
                        ) {
                            Box(Modifier.fillMaxSize().background(Color.White),
                                contentAlignment = Alignment.Center) {
                                Box(Modifier.onGloballyPositioned { hit = it.boundsInRoot() }) {
                                    PaperActivityIndicatorButton(
                                        tone = PaperActivityTone.WORKING, label = "Иммунитет",
                                        onClick = {}, selected = selected,
                                    )
                                }
                            }
                        }
                    }
                }.use { scene ->
                    repeat(6) { scene.render((it + 1) * frame).close() }
                    val control = scene.nodes().single {
                        it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("Иммунитет")
                    }
                    assertEquals(selected, control.config.getOrNull(SemanticsProperties.Selected) == true)
                    val image = scene.render(7 * frame).use { bitmap -> bitmap.png() }
                    if (!selected) {
                        val columns = (0 until 120).filter { column ->
                            (0 until 120).any { row -> image.getRGB(column, row) and 0xFFFFFF != white }
                        }
                        val rows = (0 until 120).filter { row ->
                            (0 until 120).any { column -> image.getRGB(column, row) and 0xFFFFFF != white }
                        }
                        ink = Rect(columns.first().toFloat(), rows.first().toFloat(),
                            (columns.last() + 1).toFloat(), (rows.last() + 1).toFloat())
                    }
                    // Inside the hit area yet left of the 10 dp glyph: what the action paints
                    // after the pointer has already left the row.
                    val actual = image.getRGB((hit.left + 2).toInt(), hit.center.y.toInt()) and 0xFFFFFF
                    if (selected) {
                        assertTrue(distance(actual, selectedRgb) <= 24,
                            "$platform selection must stay painted while the pointer is away: $actual")
                    } else {
                        assertEquals(white, actual, "$platform idle button must not paint: $actual")
                    }
                }
            }
            assertTrue(hit.height >= controlHeight, "$platform hit area follows density: $hit")
            assertTrue(hit.width >= controlHeight, "$platform hit area follows density: $hit")
            assertTrue(ink.width in 9f..13f, "The glyph stays small inside its hit area: $ink")
            assertTrue(abs(ink.center.y - hit.center.y) <= 1.5f, "$platform glyph is vertically centred")
            assertTrue(abs(ink.center.x - hit.center.x) <= 1.5f, "$platform glyph is centred: $ink")
        }
    }

    @Test fun disabledIndicatorButtonIgnoresActivation() {
        var clicks = 0
        ImageComposeScene(120, 60) {
            PaperTheme {
                PaperActivityIndicatorButton(PaperActivityTone.QUEUED, "Иммунитет остановлен",
                    onClick = { clicks++ }, enabled = false)
            }
        }.use { scene ->
            repeat(3) { scene.render((it + 1) * frame).close() }
            val control = scene.nodes().single {
                it.config.getOrNull(SemanticsProperties.ContentDescription) ==
                    listOf("Иммунитет остановлен")
            }
            assertNotNull(control.config.getOrNull(SemanticsProperties.Disabled))
            assertEquals(0, clicks)
        }
    }
}
