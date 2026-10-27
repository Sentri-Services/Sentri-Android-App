package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlProductionAuxiliary(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : ProductionAuxiliaryRemote {
    override suspend fun batches(lease: ScopeLease, request: BatchRequest): BatchPage {
        check(lease)
        val filter = Farm_view_batch_bool_exp(
            status = if (request.statuses.isEmpty()) Optional.Absent else Optional.Present(Farm_enum_batch_status_enum_comparison_exp(
                _in = Optional.Present(request.statuses.sortedBy { it.name }.map { Farm_enum_batch_status_enum.safeValueOf(it.name) }))),
            line_id = request.lineId?.let { Optional.Present(eq(it)) } ?: Optional.Absent,
            id = if (request.excludeDefault) Optional.Present(String_comparison_exp(_nlike = Optional.Present("LINE%"))) else Optional.Absent)
        val rows = executor.query(NativeProductionBatchesQuery(lease.scope.farmId, filter, request.offset, request.limit + 1)).batches
        check(lease)
        val values = rows.map { batch(it, lease) }
        valid(values.size <= request.limit + 1 && values.map { it.id }.distinct().size == values.size && values.all(request::accepts))
        return BatchPage(request, values.take(request.limit), values.size > request.limit)
    }
    override suspend fun batch(lease: ScopeLease, id: String): ProductionBatch? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeProductionBatchesQuery(lease.scope.farmId,
            Farm_view_batch_bool_exp(id = Optional.Present(eq(id))), 0, 1)).batches
        check(lease); valid(rows.size <= 1)
        return rows.singleOrNull()?.let { batch(it, lease).also { value -> valid(value.id == id) } }
    }
    override suspend fun rechecks(lease: ScopeLease, request: RecheckRequest): RecheckPage {
        check(lease)
        val filter = Farm_view_recheck_pig_bool_exp(
            task_id = request.taskId?.let { Optional.Present(eq(it)) } ?: Optional.Absent,
            task = request.batchId?.let { Optional.Present(Farm_view_task_bool_exp(batch_id = Optional.Present(eq(it)))) } ?: Optional.Absent,
            pig = request.unitId?.let { Optional.Present(Farm_view_pig_bool_exp(location = Optional.Present(
                Farm_view_pen_index_bool_exp(unit_id = Optional.Present(eq(it)))))) } ?: Optional.Absent,
            is_in_re_estrus = request.inReEstrus?.let { Optional.Present(Boolean_comparison_exp(_eq = Optional.Present(it))) } ?: Optional.Absent)
        val rows = executor.query(NativeProductionRecheckPigsQuery(lease.scope.farmId, filter, request.offset, request.limit + 1)).pigs
        check(lease)
        val values = rows.map { row ->
            val task = row.task ?: throw ApiFailure.Protocol(); val pig = row.pig ?: throw ApiFailure.Protocol()
            valid(task.id == row.task_id && task.task_type.rawValue == "RE_ESTRUS" && task.batch?.farm_id == lease.scope.farmId &&
                pig.id == row.pig_id && pig.farm_id == lease.scope.farmId)
            val location = pig.location?.let {
                valid(it.farm_id == lease.scope.farmId)
                RecheckLocation(TaskPigLocation(pig.location_code, it.unit_id, it.unit_location, it.pen_location),
                    it.zone?.let { z -> NamedProductionLocation(z.code, z.name_cn, z.name_en) },
                    it.unit?.let { u -> NamedProductionLocation(u.code, u.name_cn, u.name_en) },
                    it.section?.let { s -> NamedProductionLocation(s.code, s.name_cn, s.name_en) },
                    it.row?.code, it.row?.row_number, it.pen_number)
            }
            RecheckPig(row.task_id, task.batch_id, lease.scope.farmId, row.pig_id, pig.ear_tag, pig.ear_notch, location,
                ReEstrusResult(row.check_status?.rawValue?.let { code<ReEstrusStatus>(it) }, row.check_at, row.is_in_re_estrus, row.re_estrus_at,
                    row.mating_at, row.mating_by, row.submitted_at, row.operator_id))
        }
        valid(values.size <= request.limit + 1 && values.map { it.taskId to it.pigId }.distinct().size == values.size && values.all(request::accepts))
        return RecheckPage(request, values.take(request.limit), values.size > request.limit)
    }
    private fun batch(row: NativeProductionBatchesQuery.Batch, lease: ScopeLease): ProductionBatch {
        valid(row.farm_id == lease.scope.farmId)
        val line = row.line?.let { valid(it.id == row.line_id && it.farm_id == lease.scope.farmId); BatchLine(it.id, it.farm_id, it.name, code(it.status.rawValue)) }
        val tasks = row.tasks.map {
            valid(it.batch_id == row.id)
            val kind = code<ProductionTaskKind>(it.task_type.rawValue)
            val plan = it.batch_task?.let { plan ->
                valid(plan.task_id == it.id && plan.batch_id == row.id && plan.task_type.rawValue == kind.name)
                TaskPlan(plan.planned_start_time, plan.planned_duration_days)
            }
            BatchTaskReference(it.id, kind, code(it.status.rawValue), plan)
        }
        valid(tasks.map { it.id }.distinct().size == tasks.size)
        return ProductionBatch(row.id, lease.scope.farmId, row.sn, code(row.status.rawValue), row.line_id, line, tasks)
    }
    private fun eq(value: String) = String_comparison_exp(_eq = Optional.Present(value))
    private fun valid(value: Boolean) { if (!value) throw ApiFailure.Protocol() }
    private inline fun <reified T : Enum<T>> code(value: String): T = enumValues<T>().find { it.name == value } ?: throw ApiFailure.Protocol()
}
