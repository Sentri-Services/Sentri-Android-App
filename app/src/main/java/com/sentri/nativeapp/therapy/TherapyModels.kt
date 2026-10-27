package com.sentri.nativeapp.therapy

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.offline.CommandInput
import java.math.BigDecimal

/** Therapy/task identities are separate from production task identities. Prescription owns the shared aggregate. */
sealed interface TherapyCommandInput : CommandInput {
    val prescriptionId: String
    val therapyId: String?
    override val productionType: String? get() = null
    override val taskId: String? get() = null
    override val groupKeys: Set<String> get() = buildSet {
        add("prescription:$prescriptionId")
        therapyId?.let { add("therapy:$it") }
        targetPigIds.forEach { add("pig:$it") }
    }
}

data class PrescriptionTargetInput(val targetId: String, val earTag: String? = null, val penCode: String? = null,
    val symptoms: List<String> = emptyList(), val diseases: List<String> = emptyList()) {
    init { require(targetId.isNotBlank()) }
    fun graphql() = BiosafetyPrescriptionTargetParamInput(target_id = targetId, ear_tag = Optional.Present(earTag),
        pen_code = Optional.Present(penCode), symptoms = Optional.Present(symptoms), diseases = Optional.Present(diseases))
}

/** prescription/therapy IDs are local coordination context; the wire operation identifies the scheduled task. */
data class CompleteTherapyTargetInput(override val prescriptionId: String, override val therapyId: String,
    val therapyTaskId: String, val targetIds: List<String>, val completedAt: String) : TherapyCommandInput {
    override val operationName: String get() = "CompleteTherapyTarget"
    override val targetPigIds: Set<String> get() = targetIds.toSet()
    init { validate(prescriptionId, therapyId, therapyTaskId); require(targetIds.all(String::isNotBlank)); TimestampAdapter.validate(completedAt) }
    fun graphql() = BiosafetyCompleteTherapyTaskInput(completed_at = completedAt, task_id = therapyTaskId, target_ids = Optional.Present(targetIds))
}
data class SkipTherapyTaskInput(override val prescriptionId: String, override val therapyId: String,
    val therapyTaskId: String, val targetIds: List<String>, val reason: String?) : TherapyCommandInput {
    override val operationName: String get() = "SkipTherapyTask"
    override val targetPigIds: Set<String> get() = targetIds.toSet()
    init { validate(prescriptionId, therapyId, therapyTaskId); require(targetIds.all(String::isNotBlank)) }
    fun graphql() = BiosafetySkipTherapyTaskInput(task_id = therapyTaskId, target_ids = Optional.Present(targetIds), reason = Optional.Present(reason))
}
/** Add/remove affect the whole prescription, including other therapies; caller's active therapy is not a wire filter. */
data class AddPrescriptionTargetInput(override val prescriptionId: String, val targets: List<PrescriptionTargetInput>, val reason: String?) : TherapyCommandInput {
    override val therapyId: String? get() = null
    override val operationName: String get() = "AddPrescriptionTarget"
    override val targetPigIds: Set<String> get() = targets.map { it.targetId }.toSet()
    init { validate(prescriptionId) }
    fun graphql() = BiosafetyAddPrescriptionTargetInput(prescription_id = prescriptionId, targets = Optional.Present(targets.map { it.graphql() }), reason = Optional.Present(reason))
}
data class RemovePrescriptionTargetInput(override val prescriptionId: String, val targets: List<PrescriptionTargetInput>, val reason: String?) : TherapyCommandInput {
    override val therapyId: String? get() = null
    override val operationName: String get() = "RemovePrescriptionTarget"
    override val targetPigIds: Set<String> get() = targets.map { it.targetId }.toSet()
    init { validate(prescriptionId) }
    fun graphql() = BiosafetyRemovePrescriptionTargetInput(prescription_id = prescriptionId, targets = Optional.Present(targets.map { it.graphql() }), reason = Optional.Present(reason))
}
data class TerminateTherapyInput(override val prescriptionId: String, override val therapyId: String, val reason: String?) : TherapyCommandInput {
    override val operationName: String get() = "TerminateTherapy"
    override val targetPigIds: Set<String> get() = emptySet()
    init { validate(prescriptionId, therapyId) }
    fun graphql() = BiosafetyTerminateTherapyInput(prescription_id = prescriptionId, therapy_id = therapyId, reason = Optional.Present(reason))
}
data class TerminatePrescriptionInput(override val prescriptionId: String, val reason: String?) : TherapyCommandInput {
    override val therapyId: String? get() = null
    override val operationName: String get() = "TerminatePrescription"
    override val targetPigIds: Set<String> get() = emptySet()
    init { validate(prescriptionId) }
    fun graphql() = BiosafetyTerminatePrescriptionInput(prescription_id = prescriptionId, reason = Optional.Present(reason))
}

data class PrescriptionTherapyInput(val treatmentId: String, val medicationId: String, val medicationCatalogId: String?,
    val dosage: BigDecimal, val unit: String, val startDate: String, val times: Int = 1, val interval: Int? = null) {
    init { validate(treatmentId, medicationId); TimestampAdapter.validate(startDate) }
    fun graphql() = BiosafetyPrescriptionTherapyParamInput(treatment_id = treatmentId, medication_id = medicationId,
        medication_catalog_id = Optional.Present(medicationCatalogId), dosage = dosage, unit = unit, start_date = startDate,
        times = Optional.Present(times), interval = Optional.Present(interval))
}
/** Old app declares this operation without an active creation entry. It is not a queue command or a local ID mapping. */
data class CreatePrescriptionInput(val targets: List<PrescriptionTargetInput>, val therapies: List<PrescriptionTherapyInput>,
    val symptoms: List<String> = emptyList(), val diseases: List<String> = emptyList(), val remark: String? = null, val adhoc: Boolean = false) {
    fun graphql() = BiosafetyCreatePrescriptionInput(targets = Optional.Present(targets.map { it.graphql() }),
        therapies = Optional.Present(therapies.map { it.graphql() }), symptoms = Optional.Present(symptoms), diseases = Optional.Present(diseases),
        remark = Optional.Present(remark), is_adhoc = Optional.Present(adhoc))
}
private fun validate(vararg ids: String) { require(ids.all(String::isNotBlank)) }
