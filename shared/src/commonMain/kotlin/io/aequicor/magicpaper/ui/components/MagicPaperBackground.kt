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

/** Shared AGSL/SkSL: fixed paper fibres and relief, lit by travelling ripples and grazing light. */
internal val PaperShaderSource = """
    uniform float2 resolution;
    uniform float time;
    uniform float density;

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
    // Value noise with analytic derivatives: relief normals without extra texture samples.
    float3 relief(float2 p) {
        float2 i = floor(p);
        float2 f = fract(p);
        float a = hash(i);
        float b = hash(i + float2(1.0, 0.0));
        float c = hash(i + float2(0.0, 1.0));
        float d = hash(i + float2(1.0));
        float2 u = f * f * (3.0 - 2.0 * f);
        float2 du = 6.0 * f * (1.0 - f);
        return float3(mix(mix(a, b, u.x), mix(c, d, u.x), u.y),
                      du.x * mix(b - a, d - c, u.y),
                      du.y * mix(c - a, d - b, u.x));
    }
    half4 main(float2 fragCoord) {
        float2 uv = fragCoord / max(resolution, float2(1.0));
        float2 p = fragCoord / max(min(resolution.x, resolution.y), 1.0);
        float2 q = fragCoord / max(density, 1.0);

        // The material stays attached to the sheet. Fibre size is consistent in dp.
        float2 paperCoords = float2(q.x * 0.866 + q.y * 0.5, -q.x * 0.5 + q.y * 0.866);
        float3 pulp = relief(paperCoords * 0.045);
        float3 tooth = relief(q * 0.19 + float2(17.0));
        // Short, randomly oriented cellulose strands; no regular woven/horizontal pattern.
        float2 cell = floor(q / 7.0);
        float2 local = (fract(q / 7.0) - 0.5) * 7.0;
        local -= (float2(hash(cell + 5.0), hash(cell + 11.0)) - 0.5) * 2.0;
        float2 axis = normalize(float2(hash(cell + 19.0), hash(cell + 29.0)) - 0.499);
        float along = dot(local, axis);
        float across = dot(local, float2(-axis.y, axis.x));
        float length = 0.7 + hash(cell + 41.0) * 1.3;
        float taper = 1.0 - smoothstep(length * 0.45, length, abs(along));
        float strand = (1.0 - smoothstep(0.12, 0.55, abs(across))) * taper;
        float fleck = noise(q * 0.8);
        float grain = hash(floor(q * 1.5)) - 0.5;
        float3 paper = mix(float3(0.978, 0.953, 0.897), float3(0.953, 0.915, 0.838), uv.y);
        paper += (pulp.x - 0.5) * 0.035 + (tooth.x - 0.5) * 0.022 + grain * 0.014;
        paper -= float3(0.041, 0.036, 0.026) * strand;
        paper += float3(0.021, 0.019, 0.014) * (fleck - 0.5);

        // A shallow bend travels diagonally across the sheet; its paired light/shadow
        // advances through the image instead of expanding around a stationary centre.
        float phase = dot(p, float2(0.86, 0.51)) * 7.5 - time * 0.52;
        float bend = sin(phase + sin(p.y * 3.0 - time * 0.12) * 0.35);
        float secondary = sin(dot(p, float2(-0.4, 0.92)) * 10.0 - time * 0.31);
        float2 slope = float2(pulp.y * 0.866 - pulp.z * 0.5, pulp.y * 0.5 + pulp.z * 0.866) * 0.12;
        slope += tooth.yz * 0.065;
        slope += float2(0.86, 0.51) * bend * 0.24;
        slope += float2(-0.4, 0.92) * secondary * 0.065;
        float3 normal = normalize(float3(-slope, 1.0));
        float3 light = normalize(float3(-0.65 + sin(time * 0.13) * 0.25, -0.55, 0.75));
        float lighting = dot(normal, light) - light.z;
        paper += lighting * 0.24;

        // Warm raking light picks out raised fibres as it sweeps over the paper.
        float sweep = pow(max(0.0, cos(phase * 0.5 - 0.7)), 10.0);
        paper += float3(0.030, 0.025, 0.014) * sweep * (0.35 + strand + tooth.x * 0.4);
        float edge = pow(abs(uv.x * 2.0 - 1.0), 6.0) + pow(abs(uv.y * 2.0 - 1.0), 6.0);
        paper -= float3(0.016, 0.019, 0.022) * edge;
        return half4(half3(clamp(paper, 0.0, 1.0)), 1.0);
    }
""".trimIndent()
