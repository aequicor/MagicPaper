package io.aequicor.magicpaper.tools.paper

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import io.aequicor.visualization.editor.plugins.*
import io.aequicor.visualization.engine.ir.resolve.ExternalComponent
import kotlinx.coroutines.*
import kotlin.test.*

class PluginRenderSessionTest {
    private class FakePlugin : DesignSystemPlugin {
        override val id = "fake"
        override val name = "Fake"
        override val components = listOf(PluginComponent("test", "Test", "Test", 4, 2))
        var renders = 0
        var disposed = 0
        var failures = 0
        var fail = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun render(request: ComponentRenderRequest): ComponentRaster {
            renders++
            gate?.await()
            if (fail) error("Fixture failure")
            return ComponentRaster(ImageBitmap(request.width, request.height)) { disposed++ }
        }
        @Composable override fun Preview(component: ExternalComponent, modifier: Modifier) {}
        override fun reportFailure(componentId: String, operation: String, cause: Throwable) { failures++ }
    }
    @Test fun boundedCacheReusesExactRequestAndReleasesEvictedAndClosedRasters() = runBlocking {
        val plugin = FakePlugin()
        val session = PluginRenderSession(DesignSystemPlugins(listOf(plugin)), byteLimit = 32)
        val original = ComponentRenderRequest(plugin.components.single().instance("fake"), 4, 2)
        session.prepare(listOf(original))
        session.prepare(listOf(original))
        assertEquals(1, plugin.renders)
        val changed = original.copy(width = 3)
        session.prepare(listOf(changed))
        assertEquals(1, session.cachedCount)
        assertEquals(1, plugin.disposed)
        session.close()
        assertEquals(2, plugin.disposed)
        assertEquals(0, session.cachedCount)
    }
    @Test fun renderFailureIsObservableAndOnlyRetriesExplicitly() = runBlocking {
        val plugin = FakePlugin().apply { fail = true }
        val session = PluginRenderSession(DesignSystemPlugins(listOf(plugin)))
        val request = ComponentRenderRequest(plugin.components.single().instance("fake"), 4, 2)
        session.prepare(listOf(request))
        assertEquals(1, plugin.failures)
        assertNotNull(session.errors[request])
        plugin.fail = false
        session.prepare(listOf(request))
        assertEquals(1, plugin.renders)
        session.retry()
        session.prepare(listOf(request))
        assertEquals(2, plugin.renders)
        assertTrue(session.errors.isEmpty())
        session.close()
    }
    @Test fun completionAfterProjectCloseIsReleasedWithoutPublishing() = runBlocking {
        val plugin = FakePlugin().apply { gate = CompletableDeferred() }
        val session = PluginRenderSession(DesignSystemPlugins(listOf(plugin)))
        val request = ComponentRenderRequest(plugin.components.single().instance("fake"), 4, 2)
        val job = launch { session.prepare(listOf(request)) }
        yield()
        assertEquals(1, plugin.renders)
        session.close()
        plugin.gate!!.complete(Unit)
        job.join()
        assertEquals(0, session.cachedCount)
        assertEquals(1, plugin.disposed)
    }
    @Test fun cancellationIsNotReportedAsRenderFailure() = runBlocking {
        val plugin = FakePlugin().apply { gate = CompletableDeferred() }
        val session = PluginRenderSession(DesignSystemPlugins(listOf(plugin)))
        val job = launch { session.prepare(listOf(ComponentRenderRequest(plugin.components.single().instance("fake"), 4, 2))) }
        yield()
        job.cancelAndJoin()
        assertEquals(0, plugin.failures)
        assertTrue(session.errors.isEmpty())
        session.close()
    }
}
