package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter

/** Reject incomplete or foreign-farm dose histories before they become trusted snapshots. */
object VaccinationReadValidation {
    private fun time(value: String?) { value?.let(TimestampAdapter::validate) }
    fun task(farmId: String, value: VaccinationTask) {
        require(value.id.isNotBlank() && value.farmId == farmId && value.vaccineId.isNotBlank() && value.brandId.isNotBlank())
        require(value.status.isNotBlank() && value.route.isNotBlank() && value.taskType.isNotBlank())
        require(value.doseCount >= 1 && value.targetCount >= 0 && value.completedTargetDoseCount >= 0 &&
            value.exemptedTargetCount >= 0 && value.settledTargetCount >= 0)
        require(value.exemptedTargetCount <= value.targetCount && value.settledTargetCount <= value.targetCount)
        time(value.scheduledAt); time(value.modifiedAt); time(value.createdAt); time(value.completedAt)
        time(value.executedAt); time(value.deletedAt)
        value.effectReview?.let { JsonValueAdapter.validate(it.encoded) }
        value.vaccine?.let { require(it.id == value.vaccineId && it.farmId == farmId && it.status.isNotBlank()) }
        value.brand?.let { require(it.id == value.brandId && it.farmId == farmId && it.vaccineId == value.vaccineId && it.status.isNotBlank()) }
        require(value.targets.map { it.targetId to it.doseIndex }.distinct().size == value.targets.size)
        require(value.targets.map { it.targetId }.toSet().size == value.targetCount)
        value.targets.forEach { target ->
            require(target.taskId == value.id && target.farmId == farmId && target.vaccineId == value.vaccineId &&
                target.brandId == value.brandId && target.targetId.isNotBlank() && target.doseIndex in 1..value.doseCount && target.status.isNotBlank())
            time(target.scheduledAt); time(target.executedAt)
            target.pig?.let { pig ->
                require(pig.id == target.targetId && pig.farmId == farmId && pig.lifecycleStatus.isNotBlank())
                time(pig.birthday)
                pig.location?.let { require(it.farmId == farmId) }
            }
            require(target.exemptionQueried || target.exemption == null)
            target.exemption?.let { exemption ->
                require(exemption.farmId == farmId && exemption.pigId == target.targetId)
                exemption.ruleCodes?.let { JsonValueAdapter.validate(it.encoded) }
                exemption.reasons?.let { JsonValueAdapter.validate(it.encoded) }
                time(exemption.evaluatedAt); time(exemption.effectiveUntil); time(exemption.modifiedAt)
            }
        }
        value.targets.groupBy { it.targetId }.values.forEach { doses ->
            require(doses.map { it.doseIndex }.sorted() == (1..doses.size).toList())
            require(doses.map { it.exemptionQueried to it.exemption }.distinct().size == 1)
        }
    }
    fun page(farmId: String, request: VaccinationPageRequest, page: VaccinationPage) {
        require(page.request == request && page.rows.size <= request.limit && (!page.hasMore || page.rows.size == request.limit))
        require(page.rows.map { it.id }.distinct().size == page.rows.size)
        page.rows.forEach { task(farmId, it); require(request.accepts(it)) }
    }
}
