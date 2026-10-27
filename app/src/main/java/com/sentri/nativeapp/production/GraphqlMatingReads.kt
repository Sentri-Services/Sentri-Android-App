package com.sentri.nativeapp.production

import com.sentri.nativeapp.graphql.NativeMatingTaskQuery
import com.sentri.nativeapp.graphql.fragment.NativeMatingMaterial
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

class GraphqlMatingReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<MatingTask> {
    override suspend fun task(lease: ScopeLease, id: String): MatingTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeMatingTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        if (rows.size != 1) throw ApiFailure.Protocol()
        val row = rows.single()
        if (row.id != id || row.batch?.farm_id != lease.scope.farmId || row.task_type.rawValue != "MATING") throw ApiFailure.Protocol()
        fun material(value: NativeMatingMaterial?): MatingMaterial? = value?.let {
            if (it.task_id != id || (it.semen_count ?: 0) < 0) throw ApiFailure.Protocol()
            val boar = it.boar
            if (boar != null && (boar.id != it.boar_id || boar.farm_id != lease.scope.farmId)) throw ApiFailure.Protocol()
            MatingMaterial(it.id, it.boar_id, it.semen_id, it.semen_breeder_type, it.semen_collect_at, it.semen_count, boar?.ear_tag, boar?.ear_notch)
        }
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            if (pig.id != target.pig_id || pig.farm_id != lease.scope.farmId) throw ApiFailure.Protocol()
            val summary = target.mating_pig?.let {
                if (it.task_id != id || it.pig_id != pig.id || it.mating_count < 0) throw ApiFailure.Protocol()
                MatingSummary(it.mating_count, it.estrus_at, it.created_at, it.first_mating_at, it.first_mating_with, it.last_mating_at, it.last_mating_with)
            }
            val records = target.mating_pig_records.map {
                if (it.task_id != id || it.pig_id != pig.id || it.mating_times < 0) throw ApiFailure.Protocol()
                MatingRecord(it.mating_at, it.mating_times, it.mating_with, it.operator_id, material(it.mating_boar?.nativeMatingMaterial), material(it.mating_semen?.nativeMatingMaterial))
            }
            MatingPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, summary, records, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location))
        }
        if (pigs.map { it.id }.distinct().size != pigs.size) throw ApiFailure.Protocol()
        return MatingTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
