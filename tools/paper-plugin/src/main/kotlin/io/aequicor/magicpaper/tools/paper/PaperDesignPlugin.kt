package io.aequicor.magicpaper.tools.paper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.aequicor.magicpaper.designsystem.*
import io.aequicor.magicpaper.logging.AppLogger
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.engine.ir.resolve.ExternalComponent
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext

/** Adapter only. Actual visuals, fonts, metrics and interactions stay in :designSystem. */
class PaperDesignPlugin(private val logger: AppLogger = AppLogger()) : DesignSystemPlugin {
    override val id = "paper"
    override val name = "Paper"
    override val components = paperFixtures.map { it.definition }
    private val renderDispatcher = lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "paper-preview").apply { isDaemon = true } }.asCoroutineDispatcher()
    }
    @Volatile private var closed = false

    @OptIn(ExperimentalComposeUiApi::class)
    override suspend fun render(request: ComponentRenderRequest): ComponentRaster {
        // withContext may discard a result on cancellation while returning to its caller.
        // Retain ownership here until the caller has actually received the raster.
        var pending: ComponentRaster? = null
        try {
            val result = withContext(renderDispatcher.value) {
                check(!closed) { "Renderer is closed" }
                val fixture = paperFixtures.single { it.definition.id == request.component.componentId }
                require(fixture.definition.validate(request.component).isEmpty())
                ImageComposeScene(request.width, request.height, density = Density(1f)) {
                    PaperFixturePreview(request.component, Modifier.fillMaxSize(), interactive = false)
                }.use { scene ->
                    repeat(3) { scene.render((it + 1) * 16_000_000L).close() }
                    coroutineContext.ensureActive()
                    val image = scene.render(64_000_000L)
                    ComponentRaster(image.toComposeImageBitmap()) { image.close() }.also { pending = it }
                }
            }
            pending = null
            return result
        } finally {
            try { pending?.dispose?.invoke() }
            catch (cleanupFailure: Exception) { reportFailure(request.component.componentId, "cancel_cleanup", cleanupFailure) }
        }
    }

    @Composable
    override fun Preview(component: ExternalComponent, modifier: Modifier) = PaperFixturePreview(component, modifier)

    override fun reportEvent(event: String, componentId: String) {
        logger.info("paper-editor", event, mapOf("componentId" to componentId))
    }
    override fun reportFailure(componentId: String, operation: String, cause: Throwable) {
        logger.error("paper-editor", "preview_failed", cause, mapOf("componentId" to componentId, "operation" to operation))
    }
    override fun close() {
        if (closed) return
        closed = true
        if (renderDispatcher.isInitialized()) renderDispatcher.value.close()
        logger.info("paper-editor", "renderer_closed")
    }
}

@Composable
fun PaperFixturePreview(component: ExternalComponent, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val fixture = paperFixtures.find { it.definition.id == component.componentId }
    PaperTheme {
        if (fixture == null) { PaperStatus("Компонент недоступен", isError = true); return@PaperTheme }
        val scale = component.variant["textScale"]?.toFloatOrNull() ?: 1f
        val platform = if (component.variant["platform"] == "Windows") PaperPlatform.WINDOWS else PaperPlatform.MACOS
        CompositionLocalProvider(
            LocalPaperPlatformPolicy provides PaperPlatformPolicy.desktop(platform),
            LocalDensity provides Density(LocalDensity.current.density, scale),
        ) {
            val state = remember(component, interactive) { PaperFixtureState(component, interactive) }
            PaperSurface(modifier) { Box(Modifier.fillMaxSize()) { fixture.content(state, Modifier.fillMaxSize()) } }
        }
    }
}
