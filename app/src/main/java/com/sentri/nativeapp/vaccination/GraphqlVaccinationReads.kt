package com.sentri.nativeapp.vaccination

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlVaccinationReads(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : VaccinationReadRemote {
    override suspend fun page(lease: ScopeLease, request: VaccinationPageRequest): VaccinationPage {
        check(lease)
        val values = executor.query(NativeVaccinationTasksQuery(lease.scope.farmId, filter(request), order(request.order),
            request.offset, request.limit + 1)).rows
        check(lease)
        val rows = values.map { task(it, lease.scope.farmId) }
        valid(rows.size <= request.limit + 1 && rows.map { it.id }.distinct().size == rows.size)
        verify { rows.forEach { VaccinationReadValidation.task(lease.scope.farmId, it); require(request.accepts(it)) } }
        return VaccinationPage(request, rows.take(request.limit), rows.size > request.limit).also {
            verify { VaccinationReadValidation.page(lease.scope.farmId, request, it) }
        }
    }
    override suspend fun detail(lease: ScopeLease, id: String): VaccinationTask? {
        require(id.isNotBlank()); check(lease)
        val values = executor.query(NativeVaccinationTasksQuery(lease.scope.farmId,
            Farm_view_vaccination_task_bool_exp(id = Optional.Present(String_comparison_exp(_eq = Optional.Present(id)))),
            order(VaccinationReadOrder.ID_ASC), 0, 2)).rows
        check(lease); valid(values.size <= 1)
        return values.singleOrNull()?.let { task(it, lease.scope.farmId).also { task ->
            valid(task.id == id); verify { VaccinationReadValidation.task(lease.scope.farmId, task) }
        } }
    }
    private fun filter(request: VaccinationPageRequest): Farm_view_vaccination_task_bool_exp = when (request.mode) {
        VaccinationReadMode.ALL -> Farm_view_vaccination_task_bool_exp()
        VaccinationReadMode.ACTIVE -> Farm_view_vaccination_task_bool_exp(status = Optional.Present(
            Farm_enum_vaccination_task_status_enum_comparison_exp(_in = Optional.Present(listOf(
                Farm_enum_vaccination_task_status_enum.PENDING, Farm_enum_vaccination_task_status_enum.IN_PROGRESS)))))
        VaccinationReadMode.ACTIVE_OR_COMPLETED_IN_WINDOW -> {
            val nonDeleted = Farm_view_vaccination_task_bool_exp(status = Optional.Present(
                Farm_enum_vaccination_task_status_enum_comparison_exp(_neq = Optional.Present(Farm_enum_vaccination_task_status_enum.DELETED))))
            val active = Farm_view_vaccination_task_bool_exp(status = Optional.Present(
                Farm_enum_vaccination_task_status_enum_comparison_exp(_neq = Optional.Present(Farm_enum_vaccination_task_status_enum.COMPLETED))))
            val completed = Farm_view_vaccination_task_bool_exp(status = Optional.Present(
                Farm_enum_vaccination_task_status_enum_comparison_exp(_eq = Optional.Present(Farm_enum_vaccination_task_status_enum.COMPLETED))),
                modified_at = Optional.Present(Timestamptz_comparison_exp(_gte = Optional.Present(requireNotNull(request.windowStart)),
                    _lt = Optional.Present(requireNotNull(request.windowEnd)))))
            Farm_view_vaccination_task_bool_exp(_and = Optional.Present(listOf(nonDeleted,
                Farm_view_vaccination_task_bool_exp(_or = Optional.Present(listOf(active, completed))))))
        }
    }
    private fun order(value: VaccinationReadOrder) = when (value) {
        VaccinationReadOrder.CREATED_DESC -> listOf(Farm_view_vaccination_task_order_by(created_at = Optional.Present(Order_by.desc)),
            Farm_view_vaccination_task_order_by(id = Optional.Present(Order_by.asc)))
        VaccinationReadOrder.ID_ASC -> listOf(Farm_view_vaccination_task_order_by(id = Optional.Present(Order_by.asc)))
    }
    private fun task(v: NativeVaccinationTasksQuery.Row, farmId: String): VaccinationTask {
        valid(v.targets_aggregate.aggregate?.count == v.targets.size)
        return VaccinationTask(v.id, v.farm_id, v.vaccine_id, v.brand_id, v.status.rawValue,
            v.scheduled_at, v.modified_at, v.created_at, v.completed_at, v.executed_at, v.executed_by,
            v.operator_id, v.route.rawValue, v.interval_value, v.interval_unit, v.dose_unit,
            v.dose_count, v.dose, v.completed_target_dose_count, v.exempted_target_count,
            v.settled_target_count, v.target_count, v.task_type.rawValue, v.plan_id, v.related_task_id,
            v.effect_review, v.is_deleted, v.deleted_at,
            v.vaccine?.let { VaccinationVaccine(it.id, it.farm_id, it.name_cn, it.name_en, it.status.rawValue) },
            v.brand?.let { VaccinationBrand(it.id, it.farm_id, it.vaccine_id, it.brand_name_cn, it.brand_name_en,
                it.dosage_form, it.status.rawValue) },
            v.targets.map { target -> VaccinationTargetDose(target.farm_id, target.task_id, target.target_id,
                target.vaccine_id, target.brand_id, target.dose_index, target.status.rawValue,
                target.scheduled_at, target.executed_at, target.executed_by, target.operator_id,
                target.pig?.let { pig -> VaccinationPig(pig.id, pig.farm_id, pig.ear_tag, pig.ear_notch,
                    pig.birthday, pig.breed_type?.rawValue, pig.role.rawValue, pig.lifecycle_status.rawValue,
                    pig.location_code, pig.current_batch_id, pig.current_batch?.let { batch ->
                        valid(batch.id == pig.current_batch_id)
                        batch.sn.takeIf { batch.farm_id == farmId }
                    }, pig.location?.let { location -> VaccinationLocation(location.farm_id, location.pen_location,
                        location.row_location, location.section_location, location.unit_location,
                        location.zone_location, location.pen_number,
                        location.zone?.let { VaccinationName(it.code, it.name_cn, it.name_en) },
                        location.section?.let { VaccinationName(it.code, it.name_cn, it.name_en) },
                        location.unit?.let { VaccinationName(it.code, it.name_cn, it.name_en) }, location.row?.code) })
                }, target.vaccination_exemption?.let { exemption -> VaccinationExemption(exemption.farm_id,
                    exemption.pig_id, exemption.exemption_hit, exemption.rule_codes, exemption.reasons,
                    exemption.evaluated_at, exemption.effective_until, exemption.modified_at,
                    exemption.operator_id) }, true) }
            )
    }
    private fun valid(ok: Boolean) { if (!ok) throw ApiFailure.Protocol() }
    private fun verify(block: () -> Unit) { try { block() } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() } }
}
