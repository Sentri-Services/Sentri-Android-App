package com.sentri.nativeapp.production

import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.graphql.type.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

interface TaskCatalogRemote {
    suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage
    suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata?
}
class GraphqlTaskCatalog(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskCatalogRemote {
    override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage {
        check(lease)
        val response = executor.query(NativeProductionTaskCatalogQuery(lease.scope.farmId, filter(request), order(request.order), request.offset, request.limit))
        check(lease)
        val rows = response.tasks.map { map(it, lease) }
        val count = response.count.aggregate?.count ?: throw ApiFailure.Protocol()
        if (count < rows.size || rows.size > request.limit || rows.map { it.id }.distinct().size != rows.size || rows.any { !request.accepts(it) }) throw ApiFailure.Protocol()
        return TaskCatalogPage(request, rows, count)
    }
    override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? {
        require(id.isNotBlank()); check(lease)
        val response = executor.query(NativeProductionTaskCatalogQuery(lease.scope.farmId,
            Farm_view_task_bool_exp(id = Optional.Present(String_comparison_exp(_eq = Optional.Present(id)))), order(TaskCatalogOrder.CREATED_DESC), 0, 1))
        val rows = response.tasks
        check(lease)
        val count = response.count.aggregate?.count ?: throw ApiFailure.Protocol()
        if (rows.isEmpty() && count == 0) return null
        if (rows.size != 1 || count != 1) throw ApiFailure.Protocol()
        return map(rows.single(), lease).also { if (it.id != id) throw ApiFailure.Protocol() }
    }
    companion object {
        fun filter(request: TaskCatalogRequest): Farm_view_task_bool_exp {
            val statuses = if (request.statuses.isEmpty()) null else Farm_enum_task_status_enum_comparison_exp(_in = Optional.Present(request.statuses.sortedBy { it.name }.map { Farm_enum_task_status_enum.safeValueOf(it.name) }))
            val alternatives = request.completedWindow?.let { window ->
                listOfNotNull(statuses?.let { Farm_view_task_bool_exp(status = Optional.Present(it)) }, Farm_view_task_bool_exp(
                    status = Optional.Present(Farm_enum_task_status_enum_comparison_exp(_eq = Optional.Present(Farm_enum_task_status_enum.COMPLETED))),
                    actual_end_time = Optional.Present(Timestamptz_comparison_exp(_gte = Optional.Present(window.from), _lt = Optional.Present(window.until)))))
            }
            return Farm_view_task_bool_exp(
                task_type = if (request.kinds.isEmpty()) Optional.Absent else Optional.Present(Farm_enum_batch_task_type_enum_comparison_exp(_in = Optional.Present(request.kinds.sortedBy { it.name }.map { Farm_enum_batch_task_type_enum.safeValueOf(it.name) }))),
                status = if (alternatives == null && statuses != null) Optional.Present(statuses) else Optional.Absent,
                _or = alternatives?.let { Optional.Present(it) } ?: Optional.Absent,
                batch_id = request.batchId?.let { Optional.Present(String_comparison_exp(_eq = Optional.Present(it))) } ?: Optional.Absent,
                operator_id = request.operatorId?.let { Optional.Present(String_comparison_exp(_eq = Optional.Present(it))) } ?: Optional.Absent,
                batch = request.lineId?.let { Optional.Present(Farm_view_batch_bool_exp(line_id = Optional.Present(String_comparison_exp(_eq = Optional.Present(it))))) } ?: Optional.Absent,
                pigs = request.pigIds?.let { Optional.Present(Farm_view_task_pig_bool_exp(pig_id = Optional.Present(String_comparison_exp(_in = Optional.Present(it.sorted()))))) } ?: Optional.Absent,
            )
        }
        fun order(value: TaskCatalogOrder): List<Farm_view_task_order_by> = if (value == TaskCatalogOrder.CREATED_DESC) listOf(
            Farm_view_task_order_by(created_at = Optional.Present(Order_by.desc)), Farm_view_task_order_by(id = Optional.Present(Order_by.desc)))
        else listOf(Farm_view_task_order_by(batch_task = Optional.Present(Farm_view_batch_task_order_by(planned_start_time = Optional.Present(Order_by.asc)))), Farm_view_task_order_by(id = Optional.Present(Order_by.asc)))
        private fun valid(condition: Boolean) { if (!condition) throw ApiFailure.Protocol() }
        private inline fun <reified T : Enum<T>> code(raw: String): T = enumValues<T>().find { it.name == raw } ?: throw ApiFailure.Protocol()
        private fun unique(ids: List<Any>) = valid(ids.distinct().size == ids.size)
        private fun map(row: NativeProductionTaskCatalogQuery.Task, lease: ScopeLease): TaskMetadata {
            val batch = row.batch ?: throw ApiFailure.Protocol()
            valid(batch.id == row.batch_id && batch.farm_id == lease.scope.farmId)
            val kind = code<ProductionTaskKind>(row.task_type.rawValue)
            val line = batch.line?.let { valid(it.farm_id == lease.scope.farmId && it.max_breeding_sows_per_batch >= 0); TaskLine(it.id, it.farm_id, it.name, it.max_breeding_sows_per_batch) }
            val related = batch.tasks.map { valid(it.batch_id == batch.id); RelatedProductionTask(it.id, code(it.task_type.rawValue), code(it.status.rawValue)) }
            unique(related.map { it.id })
            val operator = row.operator?.let { valid(it.id == row.operator_id); TaskOperator(it.id, it.display_name, it.avatar_url) }
            val plan = row.batch_task?.let { valid(it.task_id == row.id && it.batch_id == batch.id && it.task_type.rawValue == kind.name); TaskPlan(it.planned_start_time, it.planned_duration_days) }
            val pigs = row.pigs.map { target ->
                val pig = target.pig ?: throw ApiFailure.Protocol()
                valid(target.task_id == row.id && target.batch_id == batch.id && target.pig_id == pig.id && pig.farm_id == lease.scope.farmId)
                TaskPigReference(pig.id, target.disabled, pig.ear_tag, pig.ear_notch, pig.location_code, target.mark)
            }
            unique(pigs.map { it.id })
            val materials = row.mating_materials.map {
                valid(it.task_id == row.id && (it.semen_count ?: 0) >= 0)
                it.boar?.let { boar -> valid(boar.id == it.boar_id && boar.farm_id == lease.scope.farmId) }
                MatingMaterial(it.id, it.boar_id, it.semen_id, it.semen_breeder_type, it.semen_collect_at, it.semen_count, it.boar?.ear_tag, it.boar?.ear_notch)
            }
            unique(materials.map { it.id })
            val statistics = row.re_estrus_statistics.map { valid(it.task_id == row.id); TaskRecheckStatistic(it.statistics_date, it.statistics) }
            unique(statistics.map { it.at })
            val locations = row.nursery_locations.map { valid(it.task_id == row.id && it.total_count >= 0 && it.total_weight.signum() >= 0); NurseryLocation(it.location_code, it.check_at, it.created_at, it.operator_id, it.total_count, it.total_weight) }
            unique(locations.map { it.code })
            return TaskMetadata(row.id, lease.scope.farmId, kind, code(row.status.rawValue), row.actual_start_time, row.actual_end_time, row.created_at, row.modified_at,
                row.operator_id, operator, TaskBatch(batch.id, batch.sn, batch.profile_config, line, related), plan, pigs, materials, statistics, locations)
        }
    }
}
