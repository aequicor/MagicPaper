package io.aequicor.magicpaper.ui.components

import io.aequicor.magicpaper.domain.RequestPin
import io.aequicor.magicpaper.domain.RequestPinGroup
import kotlin.test.*

class RequestPinSelectionTest {
    private fun pin(id: String) = RequestPin(id, id, "Пользователь")
    private val a = RequestPinGroup(pin("a"), listOf(pin("a1"), pin("a2"), pin("a3")))
    private val b = RequestPinGroup(pin("b"), listOf(pin("b1")))
    private val order = listOf("a", "a1", "a2", "a3", "b", "b1")
    private fun at(index: Int) = visibleRequestPins(listOf(a, b)) { order.indexOf(it) < index }

    @Test fun rootAndOnlyNearestPassedClarificationAppear() {
        assertNull(at(0))
        assertEquals(VisibleRequestPins(a), at(1))
        assertEquals(VisibleRequestPins(a, 0), at(2))
        assertEquals(VisibleRequestPins(a, 1), at(3))
        assertEquals(VisibleRequestPins(a, 2), at(4))
        assertEquals(VisibleRequestPins(b), at(5))
        assertEquals(VisibleRequestPins(b, 0), at(6))
    }

    @Test fun jumpingToSourceRevealsEarlierClarificationOrGroupAndScrollingRestoresIt() {
        assertEquals(b, at(6)?.group)
        assertEquals(a, at(order.indexOf("b"))?.group)
        assertEquals("a2", at(order.indexOf("a3"))?.clarification?.messageId)
        assertNull(at(order.indexOf("a")))
        assertEquals(VisibleRequestPins(b, 0), at(6))
    }

    @Test fun indicatorWindowIncludesCurrentOrdinalAndStaysBounded() {
        assertEquals(0..2, pinIndicatorWindow(1, 3))
        assertEquals(0..4, pinIndicatorWindow(0, 100))
        assertEquals(48..52, pinIndicatorWindow(50, 100))
        assertEquals(95..99, pinIndicatorWindow(99, 100))
    }
}
