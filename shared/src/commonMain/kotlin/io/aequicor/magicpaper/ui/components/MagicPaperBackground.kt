package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.isActive

/** One isolated drawing layer. No frame state is read by chat/project composition or layout. */
@Composable
fun MagicPaperBackground(enabled: Boolean, modifier: Modifier = Modifier) {
    val parchment = remember {
        Brush.verticalGradient(listOf(Color(0xFFFBF7EE), Color(0xFFF5EFE3), Color(0xFFF0E8D9)))
    }
    Box(modifier.background(parchment)) {
        val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
        val foreground = LocalWindowInfo.current.isWindowFocused && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
        // Removing these composables cancels polling, receivers and frame callbacks entirely.
        if (enabled && foreground) {
            val environment by rememberPaperEnvironment()
            if (environment.allowsAnimation) AnimatedPaper()
        }
    }
}

@Composable
private fun AnimatedPaper() {
    val renderer = rememberPaperRenderer() ?: return
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(renderer) {
        var previous = withFrameNanos { it }
        var elapsed = 0L
        var lastDraw = 0L
        while (isActive) {
            withFrameNanos { now ->
                // Clamp stalls so waking/recovering never jumps the pattern forward.
                elapsed += (now - previous).coerceIn(0L, 100_000_000L)
                previous = now
                if (elapsed - lastDraw >= 33_333_333L) {
                    time.floatValue = elapsed / 1_000_000_000f
                    lastDraw = elapsed
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer()) {
        renderer.draw(this, time.floatValue)
    }
}

internal interface PaperRenderer {
    fun draw(scope: DrawScope, timeSeconds: Float)
}

@Composable
internal expect fun rememberPaperRenderer(): PaperRenderer?

@Composable
internal expect fun rememberPaperEnvironment(): State<PaperEnvironment>

/** Shared AGSL/SkSL source. Stationary grain, slow ink clouds and faint gilded contours. */
internal val PaperShaderSource = """
    uniform float2 resolution;
    uniform float time;

    float hash(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return fract(p.x * p.y);
    }
    float noise(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        f = f * f * (3.0 - 2.0 * f);
        return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), f.x),
                   mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0)), f.x), f.y);
    }
    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(resolution, float2(1.0));
        float2 p = fragCoord / max(min(resolution.x, resolution.y), 1.0);
        float t = time * 0.035;
        float warp = noise(p * 2.0 + float2(t, -t * 0.7));
        float cloud = noise(p * 2.7 + float2(warp, t * 0.5));
        float glow = noise(p * 1.8 + float2(-t * 0.8, warp));
        float3 paper = mix(float3(0.984, 0.969, 0.933), float3(0.941, 0.910, 0.851), uv.y);
        paper = mix(paper, float3(0.77, 0.72, 0.85), smoothstep(0.38, 0.85, cloud) * 0.19);
        paper = mix(paper, float3(0.70, 0.78, 0.66), smoothstep(0.45, 0.92, glow) * 0.10);
        float contour = 1.0 - smoothstep(0.012, 0.04, abs(sin((cloud + p.y * 0.15) * 16.0)));
        paper = mix(paper, float3(0.76, 0.63, 0.37), contour * 0.055);
        float grain = hash(floor(fragCoord)) - 0.5;
        paper += grain * 0.018;
        return half4(half3(paper), 1.0);
    }
""".trimIndent()
