package com.sentri.nativeapp.production

import com.sentri.nativeapp.graphql.NativeEstrusTaskQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

/** Read adapter only. NativeCheckEstrus is a compiled contract, not an automatic write transport. */
class GraphqlEstrusReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<EstrusTask> {
    override suspend fun task(lease: ScopeLease, id: String): EstrusTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeEstrusTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        if (rows.size != 1) throw ApiFailure.Protocol()
        val row = rows.single()
        if (row.id != id || row.batch?.farm_id != lease.scope.farmId || row.task_type.rawValue != "ESTRUS") throw ApiFailure.Protocol()
        val pigs = row.pigs.map {
            val pig = it.pig ?: throw ApiFailure.Protocol()
            if (pig.id != it.pig_id || pig.farm_id != lease.scope.farmId) throw ApiFailure.Protocol()
            val estrus = it.estrus_pig
            if (estrus != null && (estrus.pig_id != pig.id || estrus.task_id != id)) throw ApiFailure.Protocol()
            val status = estrus?.check_status?.rawValue?.let { value ->
                EstrusStatus.entries.find { candidate -> candidate.name == value } ?: throw ApiFailure.Protocol()
            }
            EstrusPig(pig.id, pig.ear_tag, pig.ear_notch, it.disabled, status, estrus?.check_at, estrus?.is_in_estrus, estrus?.estrus_at, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location))
        }
        if (pigs.map { it.id }.distinct().size != pigs.size) throw ApiFailure.Protocol()
        return EstrusTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
