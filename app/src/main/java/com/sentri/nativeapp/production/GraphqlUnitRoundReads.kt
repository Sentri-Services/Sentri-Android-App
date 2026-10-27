package com.sentri.nativeapp.production

import com.sentri.nativeapp.graphql.NativeReEstrusTaskQuery
import com.sentri.nativeapp.graphql.NativePregnancyTaskQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlReEstrusReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<ReEstrusTask> {
    override suspend fun task(lease: ScopeLease, id: String): ReEstrusTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeReEstrusTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        if (rows.size != 1) throw ApiFailure.Protocol()
        val row = rows.single()
        if (row.id != id || row.batch?.farm_id != lease.scope.farmId || row.task_type.rawValue != "RE_ESTRUS") throw ApiFailure.Protocol()
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            if (pig.id != target.pig_id || pig.farm_id != lease.scope.farmId) throw ApiFailure.Protocol()
            val result = target.re_estrus_pig?.let {
                if (it.task_id != id || it.pig_id != pig.id) throw ApiFailure.Protocol()
                val status = it.check_status?.rawValue?.let { raw -> ReEstrusStatus.entries.find { code -> code.name == raw } ?: throw ApiFailure.Protocol() }
                ReEstrusResult(status, it.check_at, it.is_in_re_estrus, it.re_estrus_at, it.mating_at, it.mating_by, it.submitted_at, it.operator_id)
            }
            ReEstrusPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location), result)
        }
        if (pigs.map { it.id }.distinct().size != pigs.size) throw ApiFailure.Protocol()
        return ReEstrusTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
class GraphqlPregnancyReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<PregnancyTask> {
    override suspend fun task(lease: ScopeLease, id: String): PregnancyTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativePregnancyTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        if (rows.size != 1) throw ApiFailure.Protocol()
        val row = rows.single()
        if (row.id != id || row.batch?.farm_id != lease.scope.farmId || row.task_type.rawValue != "PREGNANCY") throw ApiFailure.Protocol()
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            if (pig.id != target.pig_id || pig.farm_id != lease.scope.farmId) throw ApiFailure.Protocol()
            val result = target.pregnancy_pig?.let {
                if (it.task_id != id || it.pig_id != pig.id) throw ApiFailure.Protocol()
                val status = it.check_status?.rawValue?.let { raw -> PregnancyStatus.entries.find { code -> code.name == raw } ?: throw ApiFailure.Protocol() }
                PregnancyResult(status, it.check_at, it.is_pregnant, it.re_check_at, it.mating_at, it.mating_by, it.submitted_at, it.operator_id)
            }
            PregnancyPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location), result)
        }
        if (pigs.map { it.id }.distinct().size != pigs.size) throw ApiFailure.Protocol()
        return PregnancyTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
