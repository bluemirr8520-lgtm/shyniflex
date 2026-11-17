package io.mirr.shynigsy.ui

/** The two authorized shelf items visible during one mobile poster transition. */
internal data class ContinueBackdropMotion(
    val fromKey: String,
    val toKey: String?,
    val fraction: Float,
)

/** Follow the actual poster stride without wrapping a finite shelf or inventing an item. */
internal fun continueBackdropMotion(
    keys: List<String>,
    firstVisibleIndex: Int,
    firstVisibleOffset: Int,
    cardSize: Int,
    itemSpacing: Int,
): ContinueBackdropMotion? {
    if (firstVisibleIndex !in keys.indices || firstVisibleOffset < 0 || cardSize <= 0 || itemSpacing < 0) return null
    val from = keys[firstVisibleIndex]
    val to = keys.getOrNull(firstVisibleIndex + 1)
    if (to == null) return ContinueBackdropMotion(from, null, 0f)
    // Sum in Long so even unusually large measured bounds cannot overflow into a negative stride.
    val stride = cardSize.toLong() + itemSpacing.toLong()
    val fraction = (firstVisibleOffset.toDouble() / stride).coerceIn(0.0, 1.0).toFloat()
    return ContinueBackdropMotion(from, to, fraction)
}
