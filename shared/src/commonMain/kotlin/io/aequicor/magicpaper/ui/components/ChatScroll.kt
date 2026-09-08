package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

/** Disclosure changes are reader actions, so they must take precedence over following output. */
internal class ChatScrollState(private val listState: LazyListState) {
    var disclosureRevision by mutableIntStateOf(0)
        private set

    var highlightedKey by mutableStateOf<Any?>(null)
        private set

    var requestPinsBounds by mutableStateOf<Rect?>(null)

    var navigating by mutableStateOf(false)
        private set
    private var navigationId = 0

    val canScrollToEnd: Boolean get() = listState.canScrollForward

    suspend fun navigateToEnd() {
        val navigation = ++navigationId
        navigating = true
        highlightedKey = null
        try {
            listState.pinToEnd { navigationId == navigation }
        } finally {
            if (navigationId == navigation) navigating = false
        }
    }

    fun interruptNavigation() {
        if (!navigating) return
        navigationId++
        navigating = false
        highlightedKey = null
        disclosureRevision++
    }

    fun onUserScroll(deltaY: Float) {
        interruptNavigation()
        // A real upward gesture also wins when new rows arrive in the same frame.
        if (deltaY > 0f) disclosureRevision++
    }

    suspend fun navigateToMessage(key: () -> Any, index: () -> Int?, topInset: () -> Int) {
        if (index() == null) return
        val navigation = ++navigationId
        disclosureRevision++ // Explicit navigation must win over streaming/bottom following.
        navigating = true
        highlightedKey = key()
        try {
            listState.scrollToItem(index() ?: return, -topInset())
            // The preceding panel and streamed Markdown can finish measuring after the jump.
            val started = withFrameNanos { it }
            withFrameNanos { }
            var clearance = topInset()
            var stableFrames = 0
            for (pass in 0 until 60) {
                val frame = withFrameNanos { it }
                if (navigationId != navigation || listState.isScrollInProgress) return
                // After the first layout, only grow clearance to avoid short adjacent requests
                // repeatedly showing/hiding their panel as its height changes the scroll position.
                clearance = maxOf(clearance, topInset())
                val currentKey = key()
                highlightedKey = currentKey
                val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == currentKey }
                if (target == null) {
                    listState.scrollToItem(index() ?: return, -clearance)
                    stableFrames = 0
                } else {
                    // Verify the measured source key: row indices can change as a draft is committed.
                    val delta = target.offset - clearance
                    val moved = if (abs(delta) > 1) listState.scrollBy(delta.toFloat()) else 0f
                    stableFrames = if (abs(moved) > 1f) 0 else stableFrames + 1
                }
                if (stableFrames >= 3 && frame - started >= 500_000_000L) break
            }
            navigating = false
            delay(1400)
        } finally {
            if (navigationId == navigation) {
                navigating = false
                highlightedKey = null
            }
        }
    }

    fun preserveDisclosure(itemKey: Any, item: LayoutCoordinates?, header: LayoutCoordinates?) {
        interruptNavigation()
        disclosureRevision++
        if (item?.isAttached != true || header?.isAttached != true) return
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey } ?: return
        val headerInItem = item.localPositionOf(header, Offset.Zero).y
        // A multiline command's top may already be offscreen when its middle is clicked.
        // Keep a visible header where it is, otherwise bring its collapsed row to the top.
        val headerInViewport = info.offset + headerInItem
        val target = headerInViewport.coerceAtLeast(0f)
        // Apply with the height change, before LazyColumn can skip the shrunken message.
        listState.requestScrollToItem(info.index, (headerInItem - target).roundToInt())
    }
}

/** Wheel/drag input immediately hands control back to the reader during a pin jump. */
internal fun Modifier.chatScrollInput(scroll: ChatScrollState): Modifier = composed {
    nestedScroll(remember(scroll) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available != Offset.Zero) scroll.onUserScroll(available.y)
                return Offset.Zero
            }
        }
    })
}

private val LocalChatDisclosure = staticCompositionLocalOf<(LayoutCoordinates?) -> Unit> { {} }

// Coordinates are only read by click handlers. Publishing them as Compose state
// needlessly invalidates the whole message after layout and during list reuse.
private class ChatCoordinates { var value: LayoutCoordinates? = null }

@Composable
internal fun ChatScrollItem(scroll: ChatScrollState, key: Any, content: @Composable () -> Unit) {
    val coordinates = remember { ChatCoordinates() }
    val highlighted by remember(scroll, key) { derivedStateOf { scroll.highlightedKey == key } }
    val highlight = if (highlighted) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp)) else Modifier
    val preserve = remember(scroll, key) { { header: LayoutCoordinates? -> scroll.preserveDisclosure(key, coordinates.value, header) } }
    Box(Modifier.then(highlight).onGloballyPositioned { coordinates.value = it }) {
        CompositionLocalProvider(LocalChatDisclosure provides preserve) { content() }
    }
}

/** Apply before header padding; a shared source lets the enclosing card draw the indication. */
internal fun Modifier.chatDisclosure(
    interactionSource: MutableInteractionSource? = null,
    onToggle: () -> Unit,
): Modifier = composed {
    val preserve = LocalChatDisclosure.current
    val coordinates = remember { ChatCoordinates() }
    onGloballyPositioned { coordinates.value = it }.clickable(
        interactionSource = interactionSource,
        indication = if (interactionSource == null) LocalIndication.current else null,
        role = Role.Button,
    ) {
        preserve(coordinates.value)
        onToggle()
    }
}

/**
 * Прокрутка ленты чата «за дно», а не «за начало последнего сообщения».
 *
 * [LazyListState.scrollToItem] с нулевым смещением ставит **верх** элемента к
 * верху вьюпорта. Для длинного ответа агента — а тем более для черновика
 * прогона, который дорастает на каждом шаге (вызов команды, правка, вывод
 * инструмента), — это значит, что каждое обновление бросает взгляд в начало
 * сообщения, а свежий текст уходит за нижний край. Здесь лента докручивается так,
 * чтобы был виден *низ* последнего элемента, и докручивается несколько раз, пока
 * лента действительно не упрётся в дно: элемент ниже вьюпорта ещё не измерен,
 * его высота известна только после первого прохода.
 *
 * [resetKey] — id чата или сессии: при открытии и переключении сразу показываем
 * последнее сообщение, а не то место, где пользователь остановил ленту в другом
 * разговоре (позиция [LazyListState] переживает выход с экрана).
 *
 * Жест читателя отличается от роста контента по состояниям самой ленты, а не по
 * эвристикам с размерами сообщений:
 *  - дно — `canScrollForward == false`, это считает сам движок прокрутки, а не мы
 *    пикселями; на дне слежение **всегда** включается, поэтому ошибочно
 *    «отпущенная» лента не остаётся без слежения навсегда (именно так и терялось
 *    автоскролл, когда черновик прогона заменялся сообщением журнала);
 *  - пользователь — явный жест вверх или смещение якоря назад при неизменной
 *    структуре ленты. Удаление строк и изменение высоты самого якоря не являются жестами;
 *  - пока читатель держит ленту (`isScrollInProgress`: перетаскивание, инерция,
 *    колесо мыши) — с докруткой не спорим.
 *
 * Решения принимаются по свежему снимку состояния, а не по значению из потока:
 * `snapshotFlow` conflates, и «прорешивать» устаревший снимок нельзя — можно
 * принять собственную докрутку за жест читателя.
 */
@Composable
internal fun stickToBottom(listState: LazyListState, resetKey: Any? = Unit): ChatScrollState {
    val scroll = remember(listState, resetKey) { ChatScrollState(listState) }
    LaunchedEffect(listState, scroll) {
        var following = true
        var disclosureRevision = scroll.disclosureRevision
        // Открыли чат — сразу на дно, не дожидаясь изменений ленты.
        listState.pinToEnd { scroll.disclosureRevision == disclosureRevision && !scroll.navigating }
        var anchor = listState.anchor()
        snapshotFlow { Triple(listState.wakeUp(), scroll.disclosureRevision, scroll.navigating) }
            .distinctUntilChanged()
            .collect {
                // Размер ленты может обновиться внутри layout: прокручиваем после его завершения.
                withFrameNanos { }
                val atEnd = !listState.canScrollForward
                val now = listState.anchor()
                when {
                    scroll.navigating -> following = false
                    atEnd -> following = true
                    scroll.disclosureRevision != disclosureRevision || now.before(anchor) -> following = false
                }
                disclosureRevision = scroll.disclosureRevision
                anchor = now
                if (following && !atEnd && !listState.isScrollInProgress) {
                    listState.pinToEnd { scroll.disclosureRevision == disclosureRevision && !scroll.navigating }
                    anchor = listState.anchor()
                }
            }
    }
    return scroll
}

/**
 * Поставить ленту на конец журнала. Просим низ последнего элемента встать к
 * верхнему краю вьюпорта: за пределы конца лента не выходит и останавливается
 * ровно на дне (с учётом нижнего `contentPadding` и отступов между элементами).
 * Нулевой offset — это как раз «прыгнуть в начало сообщения».
 */
private suspend fun LazyListState.pinToEnd(keepFollowing: () -> Boolean) {
    repeat(MAX_PIN_PASSES) {
        if (!keepFollowing()) return
        val info = layoutInfo
        val lastIndex = info.totalItemsCount - 1
        if (lastIndex < 0) return
        val lastSize = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }?.size ?: 0
        scrollToItem(lastIndex, lastSize)
        if (!canScrollForward) return
    }
}

/** Якорь прокрутки: первый видимый элемент и его смещение. При движении вперёд не убывает. */
private data class Anchor(val index: Int, val offset: Int, val count: Int, val key: Any?, val size: Int?) {
    fun before(other: Anchor): Boolean {
        // Key retention can change the index without scrolling. A resized first row can
        // also clamp its offset. Neither should stop following a concurrently growing tail.
        if (count != other.count || (key == other.key && (index != other.index || size != other.size))) return false
        return index < other.index || (index == other.index && offset < other.offset)
    }
}

private fun LazyListState.anchor(): Anchor {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == firstVisibleItemIndex }
    return Anchor(firstVisibleItemIndex, firstVisibleItemScrollOffset, info.totalItemsCount, item?.key, item?.size)
}

/**
 * Будильник: любое изменение ленты — новый элемент, доросший ответ, прокрутка,
 * жест, размер окна. Значение сравнивается целиком, поэтому лишние кадры с
 * той же картиной не будят.
 */
private fun LazyListState.wakeUp(): ListSnapshot {
    val info = layoutInfo
    return ListSnapshot(
        anchor = anchor(),
        items = info.totalItemsCount,
        measured = info.visibleItemsInfo.sumOf { it.size },
        viewport = info.viewportSize.height,
        atEnd = !canScrollForward,
        scrolling = isScrollInProgress,
    )
}

private data class ListSnapshot(
    val anchor: Anchor,
    val items: Int,
    val measured: Int,
    val viewport: Int,
    val atEnd: Boolean,
    val scrolling: Boolean,
)

/** Потолок докруток за один проход: больше нужно лишь ленте из тысяч шагов. */
private const val MAX_PIN_PASSES = 8
