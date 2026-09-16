package io.aequicor.magicpaper.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

class VisitPresentationTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun autosaveCoalescesScrollAndFlushKeepsTheLatestDraftBeforeItsTimer() = runTest {
        var snapshot: String? = null
        var writes = 0
        var reads = 0
        val owner = VisitPresentationState(null) { snapshot = it; writes++ }
        val registry = owner.registry()
        val scroll = mutableStateOf(0)
        val draft = mutableStateOf("draft")
        registry.registerProvider("scroll") { reads++; scroll.value }
        registry.registerProvider("draft") { draft }
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { registry.trackChanges() }
        assertEquals(1, writes)
        repeat(100) {
            scroll.value = it + 1
            draft.value = "draft $it"
            Snapshot.sendApplyNotifications()
            advanceTimeBy(1)
        }
        assertEquals(1, writes, "A burst of scroll offsets must not serialize every offset")
        assertEquals(1, reads, "Observation must not collect every saver on each frame")
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, writes)
        assertEquals(100, PresentationCodec.decode(snapshot).getValue("scroll").single())
        scroll.value = 101
        draft.value = "latest unsent draft"
        Snapshot.sendApplyNotifications()
        owner.flush()
        val restored = PresentationCodec.decode(snapshot)
        assertEquals(101, restored.getValue("scroll").single())
        assertEquals("latest unsent draft", (restored.getValue("draft").single() as MutableState<*>).value)
    }
    @Test fun floatingPointBoundariesAndSignedZeroRoundTripExactly() {
        val doubles = listOf(1.2345678901234567, 2147483648.0, 9007199254740991.0,
            Double.MIN_VALUE, Double.MAX_VALUE, -0.0, 0.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        val floats = listOf(1.25f, Float.MIN_VALUE, Float.MAX_VALUE, -0.0f, Float.NaN, Float.POSITIVE_INFINITY)
        val restored = PresentationCodec.decode(PresentationCodec.encode(mapOf("double" to doubles, "float" to floats)))
        doubles.zip(restored.getValue("double")).forEach { (original, value) ->
            assertEquals(original.toBits(), (value as Number).toDouble().toBits())
        }
        floats.zip(restored.getValue("float")).forEach { (original, value) ->
            assertEquals(original.toBits(), (value as Number).toFloat().toBits())
        }
        val arrays = PresentationCodec.decode(PresentationCodec.encode(mapOf("arrays" to listOf(doubles.toDoubleArray(), floats.toFloatArray()))))
            .getValue("arrays")
        doubles.zip((arrays[0] as DoubleArray).toList()).forEach { (original, value) -> assertEquals(original.toBits(), value.toBits()) }
        floats.zip((arrays[1] as FloatArray).toList()).forEach { (original, value) -> assertEquals(original.toBits(), value.toBits()) }
    }

    @Test fun safePrimitiveAndNestedSaverValuesRoundTripWithoutLosingNumericTypes() {
        val values = mapOf("scroll" to listOf(28, 91), "expanded" to listOf(listOf("a", "b")),
            "dialogs" to listOf("provider" to "model", null, true),
            "stateHolder" to listOf(mapOf("visit" to mapOf("value" to listOf(3, Long.MAX_VALUE, 1.25f, 2.75, 'a')))))
        assertEquals(values, PresentationCodec.decode(PresentationCodec.encode(values)))
        val arrays = PresentationCodec.decode(PresentationCodec.encode(mapOf("array" to listOf(intArrayOf(1, 2)))))["array"]!!
        assertContentEquals(intArrayOf(1, 2), arrays.single() as IntArray)
        assertFalse(PresentationCodec.canSave(object { val credential = "must not be serialized" }))
        assertFalse(PresentationCodec.canSave(TestDomain.SECRET))
        assertFailsWith<io.aequicor.magicpaper.data.storage.StorageException> { PresentationCodec.decode("{corrupt") }
    }

    @Test fun stateSurvivesProviderDisposalAndVisitRecreation() {
        var snapshot = ""
        val owner = VisitPresentationState(null) { snapshot = it }
        val registry = owner.registry()
        val scroll = mutableStateOf(2)
        val expanded = mutableStateOf(listOf("first"))
        val scrollEntry = registry.registerProvider("scroll") { scroll.value }
        val expandedEntry = registry.registerProvider("expanded") { expanded.value }
        scroll.value = 42
        expanded.value = listOf("first", "last")
        scrollEntry.unregister()
        expandedEntry.unregister()
        owner.flush()
        val revisited = owner.registry()
        assertEquals(42, revisited.consumeRestored("scroll"))
        assertEquals(listOf("first", "last"), revisited.consumeRestored("expanded"))
        val restarted = VisitPresentationState(snapshot) {}.registry()
        assertEquals(42, restarted.consumeRestored("scroll"))
        assertEquals(listOf("first", "last"), restarted.consumeRestored("expanded"))
    }
    @Test fun composeMutableStateSaversRestoreTheirPolicyAndSafeValue() {
        val value = mutableStateOf(listOf("a"))
        val first = PresentationCodec.encode(mapOf("state" to listOf(value)))
        value.value = listOf("a", "b")
        val second = PresentationCodec.encode(mapOf("state" to listOf(value)))
        assertNotEquals(first, second)
        val restored = PresentationCodec.decode(second).getValue("state").single() as MutableState<*>
        assertEquals(listOf("a", "b"), restored.value)
        assertFalse(PresentationCodec.canSave(mutableStateOf(TestDomain.SECRET)))
    }

    @Test fun repeatedProvidersKeepTheirOrderedSlotsDuringDisposal() {
        val registry = TrackingSaveableRegistry(emptyMap()) {}
        val first = registry.registerProvider("repeated") { 1 }
        val second = registry.registerProvider("repeated") { 2 }
        first.unregister()
        second.unregister()
        assertEquals(listOf(1, 2), registry.performSave()["repeated"])
        assertEquals(1, registry.consumeRestored("repeated"))
        assertEquals(2, registry.consumeRestored("repeated"))
    }

    @Test fun visitsKeepIndependentPresentation() {
        var a = ""
        var b = ""
        val firstOwner = VisitPresentationState(null) { a = it }
        val secondOwner = VisitPresentationState(null) { b = it }
        val first = firstOwner.registry()
        val second = secondOwner.registry()
        first.registerProvider("scroll") { 20 }.unregister()
        second.registerProvider("scroll") { 70 }.unregister()
        firstOwner.flush()
        secondOwner.flush()
        assertEquals(20, VisitPresentationState(a) {}.registry().consumeRestored("scroll"))
        assertEquals(70, VisitPresentationState(b) {}.registry().consumeRestored("scroll"))
    }
    @Test fun corruptSnapshotNeverGetsOverwrittenByDefaultUiState() {
        var writes = 0
        val owner = VisitPresentationState("{broken") { writes++ }
        val registry = owner.registry()
        assertNotNull(owner.restoreError)
        registry.registerProvider("default") { true }.unregister()
        registry.save(registry.performSave())
        assertEquals(0, writes)
    }
    private enum class TestDomain { SECRET }

    @Test fun disposingOneLazyItemDoesNotReadOrSaveUnrelatedProviders() {
        var unrelatedReads = 0
        var writes = 0
        val registry = TrackingSaveableRegistry(emptyMap()) { writes++ }
        registry.registerProvider("composer") { unrelatedReads++; "unsent draft" }
        val item = registry.registerProvider("offscreen") { "expanded" }
        item.unregister()
        assertEquals(0, unrelatedReads)
        assertEquals(0, writes)
        assertEquals("expanded", registry.consumeRestored("offscreen"))
        registry.save(registry.performSave())
        assertEquals(1, writes)
        assertEquals(1, unrelatedReads)
    }

    @Test fun detachedNullSlotsAndReusedKeysKeepRestorationOrder() {
        val registry = TrackingSaveableRegistry(emptyMap()) {}
        val first = registry.registerProvider("row") { null }
        val second = registry.registerProvider("row") { "second" }
        second.unregister()
        first.unregister()
        assertNull(registry.consumeRestored("row"))
        assertEquals("second", registry.consumeRestored("row"))
        val next = registry.registerProvider("row") { "new value" }
        next.unregister()
        assertEquals("new value", registry.consumeRestored("row"))
    }
}
