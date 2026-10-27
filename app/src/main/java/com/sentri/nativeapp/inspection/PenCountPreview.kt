package com.sentri.nativeapp.inspection

enum class PenCountReason { DIED_UNRECORDED, BORN_UNRECORDED, MOVED_UNRECORDED, MISCOUNT, OTHER }

/** Local arithmetic only. Nullable batch keys are preserved, never sent as legacy allocations. */
data class PenCountPreview(val baseline: LocationInventorySnapshot,
    val quantities: Map<String?, Int> = baseline.nonIdentityByBatch,
    val reason: PenCountReason? = null, val otherReason: String = "") {
    init {
        require(quantities.keys == baseline.nonIdentityByBatch.keys)
        require(quantities.values.all { it >= 0 })
        require(quantities.values.sumOf(Int::toLong) + baseline.identityCount <= Int.MAX_VALUE)
    }
    val identifiedCount get() = baseline.identityCount
    val totalCount get() = identifiedCount + quantities.values.sum()
    val delta get() = totalCount - baseline.totalCount
    val changed get() = quantities != baseline.nonIdentityByBatch
    val valid get() = changed && (reason == null || reason in reasons) &&
        (reason != PenCountReason.OTHER || otherReason.isNotBlank())
    val reasons get() = listOfNotNull(
        if (delta < 0) PenCountReason.DIED_UNRECORDED else if (delta > 0) PenCountReason.BORN_UNRECORDED else null,
        if (delta != 0) PenCountReason.MOVED_UNRECORDED else null,
        PenCountReason.MISCOUNT, PenCountReason.OTHER)

    fun setQuantity(batchId: String?, count: Int): PenCountPreview {
        require(quantities.containsKey(batchId))
        val next = copy(quantities = quantities + (batchId to count))
        return if (next.reason != null && next.reason !in next.reasons) next.copy(reason = null) else next
    }

    fun setSingleTotal(count: Int): PenCountPreview {
        require(quantities.size == 1 && count >= identifiedCount)
        return setQuantity(quantities.keys.single(), count - identifiedCount)
    }
}
