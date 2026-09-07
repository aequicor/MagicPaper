package io.aequicor.magicpaper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.graphics.drawscope.DrawScope
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
internal actual fun rememberPaperEnvironment(): State<PaperEnvironment> {
    val window = LocalWindowScope.current?.window as? ComposeWindow
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
        if (!capable || window == null) return@produceState
        while (isActive) {
            // Read the actual backend on the UI thread, including automatic software fallback.
            val gpu = when (window.renderApi) {
                GraphicsApi.OPENGL, GraphicsApi.DIRECT3D, GraphicsApi.ANGLE,
                GraphicsApi.VULKAN, GraphicsApi.METAL -> true
                else -> false
            }
            if (!gpu) {
                value = PaperEnvironment()
                delay(5_000)
                continue
            }
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val batteries = hardware?.powerSources ?: return@runCatching PaperEnvironment()
                    val levels = batteries.map { it.remainingCapacityPercent }
                    PaperEnvironment(
                        capable = capable && gpu,
                        powerKnown = levels.all { it.isFinite() && it in 0.0..1.0 },
                        // Empty list is a desktop without a battery; monitor all laptop/UPS batteries.
                        batteryPercent = levels.minOrNull()?.let { (it * 100).toInt() },
                    )
                }.getOrDefault(PaperEnvironment())
            }
            delay(5_000)
        }
    }
}

@Composable
internal actual fun rememberPaperRenderer(): PaperRenderer? {
    val renderer = remember { runCatching { DesktopPaperRenderer() }.getOrNull() }
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
