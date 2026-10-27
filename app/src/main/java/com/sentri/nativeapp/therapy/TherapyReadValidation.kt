package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.network.TimestampAdapter

/** The JWT supplies farm scope; all nested farm and foreign-key relationships must agree. */
object TherapyReadValidation {
    private fun timestamp(value: String?) { value?.let(TimestampAdapter::validate) }
    private fun nonblank(vararg values: String) = require(values.all(String::isNotBlank))
    fun medications(farmId: String, rows: List<TherapyMedication>) {
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { nonblank(it.id); require(it.farmId == farmId) }
    }
    fun catalogs(farmId: String, rows: List<TherapyMedicationCatalog>) {
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { nonblank(it.id, it.medicationId); require(it.farmId == farmId) }
    }
    fun treatments(rows: List<TherapyTreatment>) {
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { nonblank(it.id) }
    }
    fun context(farmId: String, prescriptionId: String, value: TherapyPrescriptionContext) {
        nonblank(value.id)
        require(value.id == prescriptionId && value.farmId == farmId && value.status.isNotBlank())
        require(value.targetIds.all(String::isNotBlank) && value.therapyStatuses.keys.all(String::isNotBlank))
        require(value.therapyStatuses.values.all(String::isNotBlank))
    }
    fun rows(farmId: String, rows: List<TherapyRow>) {
        require(rows.map { it.id }.distinct().size == rows.size)
        rows.forEach { row(farmId, it) }
    }
    fun row(farmId: String, value: TherapyRow) {
        nonblank(value.id, value.prescriptionId, value.medicationId, value.treatmentId)
        require(value.farmId == farmId && value.times >= 0)
        timestamp(value.createdAt); timestamp(value.startDate)
        value.medication?.let { require(it.id == value.medicationId && it.farmId == farmId) }
        value.medicationCatalog?.let {
            require(it.id == value.medicationCatalogId && it.medicationId == value.medicationId && it.farmId == farmId)
        }
        value.treatment?.let { require(it.id == value.treatmentId) }
        value.prescription?.let {
            require(it.id == value.prescriptionId && it.farmId == farmId && (it.therapyCount == null || it.therapyCount >= 0))
            timestamp(it.createdAt)
            it.symptoms?.let { json -> JsonValueAdapter.validate(json.encoded) }
            it.diseases?.let { json -> JsonValueAdapter.validate(json.encoded) }
        }
        require(value.tasks.map { Triple(it.taskId, it.targetId, it.times) }.distinct().size == value.tasks.size)
        value.tasks.forEach { task ->
            nonblank(task.taskId, task.targetId)
            require(task.farmId == farmId && task.prescriptionId == value.prescriptionId && task.therapyId == value.id && task.times >= 0)
            timestamp(task.scheduledAt); timestamp(task.executedAt)
            task.pig?.let { pig ->
                require(pig.id == task.targetId && pig.farmId == farmId)
                timestamp(pig.birthday)
                pig.location?.let { location -> require(location.farmId == farmId) }
            }
        }
    }
    fun page(farmId: String, request: TherapyPageRequest, value: TherapyPage) {
        require(value.request == request && value.rows.size <= request.limit && (!value.hasMore || value.rows.size == request.limit))
        rows(farmId, value.rows)
        require(value.rows.all(request::accepts))
    }
}
