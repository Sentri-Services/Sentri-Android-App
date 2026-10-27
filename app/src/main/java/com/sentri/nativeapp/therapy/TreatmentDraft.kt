package com.sentri.nativeapp.therapy

import java.math.BigDecimal

/** Local form state only. It is not an offline command or evidence of a saved treatment. */
data class TreatmentDraft(
    val pigIds: List<String>,
    val medicineId: String? = null,
    val method: String? = null, // Stable ID from the existing treatment-method directory.
    val unit: String? = null,
    val sharedDose: String = "",
    val ownDoses: Map<String, String> = emptyMap(),
    val note: String = "",
    val condition: TreatmentConditionKey? = null,
) {
    init { require(pigIds.isNotEmpty() && pigIds.none(String::isBlank) && pigIds.distinct().size == pigIds.size) }

    fun chooseMedicine(id: String) = copy(medicineId = id.takeIf(String::isNotBlank))
    fun setOwnDose(pigId: String, value: String): TreatmentDraft {
        require(pigId in pigIds)
        val followsShared = value.isBlank() || (positiveDose(value) && positiveDose(sharedDose) &&
            value.toBigDecimal().compareTo(sharedDose.toBigDecimal()) == 0)
        return copy(ownDoses = if (followsShared) ownDoses - pigId else ownDoses + (pigId to value))
    }
    fun removePig(pigId: String): TreatmentDraft {
        require(pigId in pigIds && pigIds.size > 1)
        return copy(pigIds = pigIds - pigId, ownDoses = ownDoses - pigId)
    }
    fun doseFor(pigId: String): String {
        require(pigId in pigIds)
        return ownDoses[pigId] ?: sharedDose
    }
    val adjustedCount: Int get() = ownDoses.keys.count(pigIds::contains)
    val valid: Boolean get() = medicineId != null && !method.isNullOrBlank() && !unit.isNullOrBlank() &&
        note.length <= 200 && pigIds.all { positiveDose(doseFor(it)) }

    companion object {
        fun positiveDose(value: String): Boolean = value.toBigDecimalOrNull()?.let { it > BigDecimal.ZERO } == true
    }
}
