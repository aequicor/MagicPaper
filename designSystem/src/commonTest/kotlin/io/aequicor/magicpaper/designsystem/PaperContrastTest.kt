package io.aequicor.magicpaper.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class PaperContrastTest {
    private fun luminance(color: Color): Double {
        fun linear(value: Float) = if (value <= .04045f) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
    private fun check(text: Color, background: Color, label: String, minimum: Double = 4.5) {
        val a = luminance(text); val b = luminance(background)
        val ratio = (maxOf(a,b) + .05) / (minOf(a,b) + .05)
        assertTrue(ratio >= minimum, "$label contrast is $ratio")
    }
    @Test fun textRemainsReadableOnPastelSurfacesAndInteractionOverlays() {
        val c = PaperColors()
        for (surface in listOf(c.canvas,c.surface,c.raisedSurface,c.selected,c.successSurface,c.accentSurface,c.errorSurface,c.composerSurface,c.composerHighlight,c.composerFocused,c.agentMessageSurface,c.userMessageSurface)) {
            for (overlay in listOf(Color.Transparent,c.hover,c.pressed)) {
                check(overlay.compositeOver(c.text), overlay.compositeOver(surface), "ink")
            }
        }
        check(c.tooltipText,c.tooltipSurface,"tooltip")
        check(c.secondaryText,c.surface,"secondary text")
        check(c.success,c.successSurface,"success")
        check(c.error,c.errorSurface,"error")
        for (background in listOf(c.action,c.error)) {
            for (overlay in listOf(Color.Transparent,c.hover,c.pressed)) {
                check(overlay.compositeOver(c.actionOn),overlay.compositeOver(background),"action")
            }
        }
    }
    @Test fun activityEdgesRemainVisibleAgainstPastelFillAndPaper() {
        val c = PaperColors()
        for ((fill, edge) in listOf(c.activityRed to c.error, c.activityYellow to c.activityYellowEdge,
            c.activityGreen to c.success, c.raisedSurface to c.secondaryText)) {
            for (background in listOf(fill, c.canvas, c.surface, c.raisedSurface)) {
                check(edge, background, "activity indicator", minimum = 3.0)
            }
        }
    }

}
