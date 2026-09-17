package io.aequicor.magicpaper.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.aequicor.magicpaper.logging.AppLog
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import io.aequicor.magicpaper.ui.window.LocalWindowScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skiko.GraphicsApi
import oshi.SystemInfo

@Composable
internal actual fun rememberPaperEnvironment(active: Boolean): State<PaperEnvironment> {
    val window = LocalWindowScope.current?.window as? ComposeWindow
    val activeState = rememberUpdatedState(active)
    return produceState(PaperEnvironment(), window) {
        // Native hardware discovery can block. Never run it on the UI thread.
        val hardware = withContext(Dispatchers.IO) { runCatching { SystemInfo().hardware }.getOrNull() }
        val capable = withContext(Dispatchers.IO) {
            runCatching {
                hardware != null && isPaperHardwareCapable(
                    Runtime.getRuntime().availableProcessors(), hardware.memory.total, android = false,
                )
            }.getOrDefault(false)
        }
        // The eligibility decision selects between two visible behaviours, so every
        // change of its inputs is recorded once instead of per polling tick.
        var loggedDecision: String? = null
        fun decide(environment: PaperEnvironment, backend: String, reason: String) {
            val decision = "$environment|$backend|$reason"
            if (decision == loggedDecision) return
            loggedDecision = decision
            AppLog.debug("designsystem", "paper_animation_environment", buildMap {
                put("result", if (environment.allowsAnimation) "allowed" else "denied")
                put("capability", environment.capable.toString())
                put("backend", backend)
                put("reason", reason)
                environment.batteryPercent?.let { put("batteryPercent", it.toString()) }
            })
        }
        if (!capable || window == null) {
            decide(PaperEnvironment(), "unknown", "not_capable")
            return@produceState
        }
        while (isActive) {
            // A background window keeps the last environment: polling waits, the value survives.
            if (!activeState.value) {
                delay(1_000)
                continue
            }
            // Read the actual backend on the UI thread, including automatic software fallback.
            val gpu = when (window.renderApi) {
                GraphicsApi.OPENGL, GraphicsApi.DIRECT3D, GraphicsApi.ANGLE,
                GraphicsApi.VULKAN, GraphicsApi.METAL -> true
                else -> false
            }
            if (!gpu) {
                decide(PaperEnvironment(), "software", "no_gpu")
                value = PaperEnvironment()
                delay(5_000)
                continue
            }
            val environment = withContext(Dispatchers.IO) {
                runCatching {
                    val batteries = hardware?.powerSources ?: return@runCatching PaperEnvironment()
                    val levels = batteries.map { it.remainingCapacityPercent }
                    PaperEnvironment(
                        capable = capable && gpu,
                        powerKnown = levels.all { it.isFinite() && it in 0.0..1.0 },
                        // Empty list is a desktop without a battery; monitor all laptop/UPS batteries.
                        batteryPercent = levels.minOrNull()?.let { (it * 100).toInt() },
                    )
                }.getOrElse { failure ->
                    AppLog.error("designsystem", "paper_environment_probe_failed", failure,
                        mapOf("result" to "animation_denied"))
                    PaperEnvironment()
                }
            }
            val reason = when {
                !environment.powerKnown -> "power_unknown"
                environment.powerSave -> "power_save"
                environment.reduceMotion -> "reduce_motion"
                environment.batteryPercent != null && environment.batteryPercent !in 21..100 -> "battery_low"
                else -> "eligible"
            }
            decide(environment, window.renderApi.name.lowercase(), reason)
            value = environment
            delay(5_000)
        }
    }
}

@Composable
internal actual fun rememberPaperRenderer(): PaperRenderer? {
    val renderer = remember {
        runCatching { DesktopPaperRenderer() }.getOrElse { failure ->
            AppLog.error("designsystem", "paper_renderer_unavailable", failure,
                mapOf("result" to "static_background"))
            null
        }
    }
    DisposableEffect(renderer) { onDispose { renderer?.close() } }
    return renderer
}

internal class DesktopPaperRenderer : PaperRenderer, AutoCloseable {
    private val effect = RuntimeEffect.makeForShader(PaperShaderSource)
    private val builder = RuntimeShaderBuilder(effect)
    private val paint = Paint()

    override fun draw(scope: DrawScope, timeSeconds: Float) {
        builder.uniform("resolution", scope.size.width, scope.size.height)
        builder.uniform("time", timeSeconds)
        builder.uniform("density", scope.density)
        // Skia shaders are immutable snapshots of uniforms; release each native snapshot after recording.
        builder.makeShader().use { shader ->
            paint.shader = shader
            try {
                scope.drawIntoCanvas {
                    it.skiaCanvas.drawRect(Rect.makeWH(scope.size.width, scope.size.height), paint)
                }
            } finally {
                paint.shader = null
            }
        }
    }

    override fun close() {
        paint.close()
        builder.close()
        effect.close()
    }
}
