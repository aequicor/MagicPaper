package io.aequicor.magicpaper.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

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
 *  - пользователь — якорь прокрутки уехал *назад*. Мы докручиваем только вперёд,
 *    так что назад якорь мог уйти только с руки пользователя;
 *  - пока читатель держит ленту (`isScrollInProgress`: перетаскивание, инерция,
 *    колесо мыши) — с докруткой не спорим.
 *
 * Решения принимаются по свежему снимку состояния, а не по значению из потока:
 * `snapshotFlow` conflates, и «прорешивать» устаревший снимок нельзя — можно
 * принять собственную докрутку за жест читателя.
 */
@Composable
fun stickToBottom(listState: LazyListState, resetKey: Any? = Unit) {
    LaunchedEffect(listState, resetKey) {
        var following = true
        // Открыли чат — сразу на дно, не дожидаясь изменений ленты.
        listState.pinToEnd()
        var anchor = listState.anchor()
        snapshotFlow { listState.wakeUp() }
            .distinctUntilChanged()
            .collect {
                // Размер ленты может обновиться внутри layout: прокручиваем после его завершения.
                withFrameNanos { }
                val atEnd = !listState.canScrollForward
                val now = listState.anchor()
                when {
                    atEnd -> following = true
                    now.before(anchor) -> following = false
                }
                anchor = now
                if (following && !atEnd && !listState.isScrollInProgress) {
                    listState.pinToEnd()
                    anchor = listState.anchor()
                }
            }
    }
}

/**
 * Поставить ленту на конец журнала. Просим низ последнего элемента встать к
 * верхнему краю вьюпорта: за пределы конца лента не выходит и останавливается
 * ровно на дне (с учётом нижнего `contentPadding` и отступов между элементами).
 * Нулевой offset — это как раз «прыгнуть в начало сообщения».
 */
private suspend fun LazyListState.pinToEnd() {
    repeat(MAX_PIN_PASSES) {
        val info = layoutInfo
        val lastIndex = info.totalItemsCount - 1
        if (lastIndex < 0) return
        val lastSize = info.visibleItemsInfo.lastOrNull { it.index == lastIndex }?.size ?: 0
        scrollToItem(lastIndex, lastSize)
        if (!canScrollForward) return
    }
}

/** Якорь прокрутки: первый видимый элемент и его смещение. При движении вперёд не убывает. */
private data class Anchor(val index: Int, val offset: Int) {
    fun before(other: Anchor): Boolean =
        index < other.index || (index == other.index && offset < other.offset)
}

private fun LazyListState.anchor(): Anchor = Anchor(firstVisibleItemIndex, firstVisibleItemScrollOffset)

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
