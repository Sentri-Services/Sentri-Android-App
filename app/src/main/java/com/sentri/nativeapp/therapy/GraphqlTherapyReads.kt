package com.sentri.nativeapp.therapy

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlTherapyReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TherapyReadRemote {
    override suspend fun context(lease: ScopeLease, prescriptionId: String): TherapyPrescriptionContext? {
        require(prescriptionId.isNotBlank()); check(lease)
        val value = executor.query(NativeTherapyPrescriptionContextQuery(lease.scope.farmId, prescriptionId)).value
        check(lease)
        return value?.let {
            valid(it.id == prescriptionId && it.farm_id == lease.scope.farmId)
            valid(it.targets_aggregate.aggregate?.count == it.targets.size && it.therapies_aggregate.aggregate?.count == it.therapies.size)
            valid(it.targets.all { target -> target.farm_id == lease.scope.farmId && target.prescription_id == prescriptionId && target.target_id.isNotBlank() })
            valid(it.therapies.all { therapy -> therapy.farm_id == lease.scope.farmId && therapy.prescription_id == prescriptionId && therapy.id.isNotBlank() })
            valid(it.targets.map { target -> target.target_id }.distinct().size == it.targets.size &&
                it.therapies.map { therapy -> therapy.id }.distinct().size == it.therapies.size)
            TherapyPrescriptionContext(it.id, it.farm_id, it.status.rawValue,
                it.targets.map { target -> target.target_id }.toSet(), it.therapies.associate { therapy -> therapy.id to therapy.status.rawValue }).also { context ->
                verify { TherapyReadValidation.context(lease.scope.farmId, prescriptionId, context) }
            }
        }
    }
    override suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage {
        check(lease)
        val rows = executor.query(NativeTherapyRowsQuery(lease.scope.farmId, filter(request), order(request.order),
            request.offset, request.limit + 1)).rows
        check(lease)
        val mapped = rows.map { row(it) }
        valid(mapped.size <= request.limit + 1 && mapped.map { it.id }.distinct().size == mapped.size)
        verify { TherapyReadValidation.rows(lease.scope.farmId, mapped) }
        valid(mapped.all(request::accepts))
        return TherapyPage(request, mapped.take(request.limit), mapped.size > request.limit)
    }

    override suspend fun detail(lease: ScopeLease, id: String): TherapyRow? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeTherapyRowsQuery(lease.scope.farmId,
            Farm_view_prescription_therapy_bool_exp(id = Optional.Present(eq(id))),
            order(TherapyReadOrder.ID_ASC), 0, 2)).rows
        check(lease); valid(rows.size <= 1)
        return rows.singleOrNull()?.let { row(it).also { value ->
            verify { TherapyReadValidation.row(lease.scope.farmId, value) }; valid(value.id == id)
        } }
    }

    override suspend fun medications(lease: ScopeLease): List<TherapyMedication> {
        check(lease)
        val rows = executor.query(NativeTherapyMedicationsQuery(lease.scope.farmId)).rows
        check(lease)
        return rows.map { TherapyMedication(it.id, it.farm_id, it.name_cn, it.name_en) }.also {
            verify { TherapyReadValidation.medications(lease.scope.farmId, it) }
        }
    }

    override suspend fun catalogs(lease: ScopeLease): List<TherapyMedicationCatalog> {
        check(lease)
        val rows = executor.query(NativeTherapyMedicationCatalogQuery(lease.scope.farmId)).rows
        check(lease)
        return rows.map { TherapyMedicationCatalog(it.id, it.farm_id, it.medication_id, it.name_cn, it.name_en, it.form_cn, it.form_en) }.also {
            verify { TherapyReadValidation.catalogs(lease.scope.farmId, it) }
        }
    }

    override suspend fun treatments(lease: ScopeLease): List<TherapyTreatment> {
        check(lease)
        val rows = executor.query(NativeTherapyTreatmentsQuery()).rows
        check(lease)
        return rows.map { TherapyTreatment(it.id, it.name_cn, it.name_en, it.detail_cn, it.detail_en) }.also {
            verify { TherapyReadValidation.treatments(it) }
        }
    }

    private fun filter(request: TherapyPageRequest): Farm_view_prescription_therapy_bool_exp {
        val statuses = request.statuses.map { status -> Farm_enum_prescription_status_enum.safeValueOf(status).also {
            valid(it.rawValue == status)
        } }
        val prefetch = if (request.mode == TherapyReadMode.ACTIVE_OR_EXECUTED_IN_WINDOW) {
            val active = Farm_view_prescription_therapy_bool_exp(status = Optional.Present(
                Farm_enum_prescription_status_enum_comparison_exp(_in = Optional.Present(listOf(
                    Farm_enum_prescription_status_enum.InProgress, Farm_enum_prescription_status_enum.NotStarted)))))
            val executed = Farm_view_prescription_therapy_bool_exp(tasks = Optional.Present(
                Farm_view_prescription_therapy_task_bool_exp(executed_at = Optional.Present(
                    Timestamptz_comparison_exp(_gte = Optional.Present(requireNotNull(request.windowStart)),
                        _lt = Optional.Present(requireNotNull(request.windowEnd)))))))
            Optional.Present(listOf(active, executed))
        } else Optional.Absent
        return Farm_view_prescription_therapy_bool_exp(
            prescription_id = request.prescriptionId?.let { Optional.Present(eq(it)) } ?: Optional.Absent,
            status = if (statuses.isEmpty()) Optional.Absent else Optional.Present(
                Farm_enum_prescription_status_enum_comparison_exp(_in = Optional.Present(statuses))),
            _or = prefetch)
    }

    private fun order(value: TherapyReadOrder) = when (value) {
        TherapyReadOrder.CREATED_DESC -> listOf(Farm_view_prescription_therapy_order_by(created_at = Optional.Present(Order_by.desc)),
            Farm_view_prescription_therapy_order_by(id = Optional.Present(Order_by.asc)))
        TherapyReadOrder.ID_ASC -> listOf(Farm_view_prescription_therapy_order_by(id = Optional.Present(Order_by.asc)))
    }
    private fun eq(id: String) = String_comparison_exp(_eq = Optional.Present(id))
    private fun row(value: NativeTherapyRowsQuery.Row): TherapyRow = TherapyRow(
        value.id, value.farm_id, value.prescription_id, value.created_at, value.dosage, value.interval,
        value.medication_id, value.medication?.let { TherapyMedication(it.id, it.farm_id, it.name_cn, it.name_en) },
        value.medication_catalog_id, value.medication_catalog?.let {
            TherapyMedicationCatalog(it.id, it.farm_id, it.medication_id, it.name_cn, it.name_en, it.form_cn, it.form_en)
        }, value.operator_id, value.remark,
        value.prescription?.let {
            TherapyPrescription(it.id, it.farm_id, it.created_at, it.operator_id,
                it.`operator`?.let { op -> TherapyOperator(op.display_name, op.avatar_url) },
                it.symptoms, it.diseases, it.remark, it.status.rawValue, it.therapies_aggregate.aggregate?.count)
        }, value.start_date, value.status.rawValue, value.times, value.treatment_id,
        value.treatment?.let { TherapyTreatment(it.id, it.name_cn, it.name_en, it.detail_cn, it.detail_en) },
        value.unit, value.tasks.map { task ->
            TherapyTargetTask(task.farm_id, task.prescription_id, task.therapy_id, task.task_id, task.target_id,
                task.times, task.status.rawValue, task.scheduled_at, task.executed_at, task.executed_by,
                task.operator_id, task.remark, task.pig?.let { pig ->
                    TherapyPig(pig.id, pig.farm_id, pig.breeder_code, pig.ear_notch, pig.ear_tag,
                        pig.lifecycle_status.rawValue, pig.location_code, pig.origin.rawValue,
                        pig.sex?.rawValue, pig.breed_type?.rawValue, pig.birthday, pig.teat_count,
                        pig.role.rawValue, pig.current_batch_id, pig.location?.let { location ->
                            TherapyPigLocation(location.farm_id, location.pen_location, location.row_location,
                                location.section_location, location.unit_location, location.zone_location,
                                location.pen_number, location.zone?.let { TherapyName(it.name_cn, it.name_en) },
                                location.section?.let { TherapyName(it.name_cn, it.name_en) },
                                location.unit?.let { TherapyName(it.name_cn, it.name_en) }, location.row?.code)
                        })
                })
        })
    private fun valid(ok: Boolean) { if (!ok) throw ApiFailure.Protocol() }
    private fun verify(block: () -> Unit) { try { block() } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() } }
}
