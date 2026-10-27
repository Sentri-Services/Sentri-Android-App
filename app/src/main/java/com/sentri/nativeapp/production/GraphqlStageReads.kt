package com.sentri.nativeapp.production

import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

private fun validStage(value: Boolean) { if (!value) throw ApiFailure.Protocol() }
private fun stageOwner(task: String, pig: String, expectedTask: String, expectedPig: String) = validStage(task == expectedTask && pig == expectedPig)
private fun stageUnique(values: List<Any>) = validStage(values.distinct().size == values.size)
private fun stageSex(raw: String?) = raw?.let { value -> PigletSex.entries.find { it.name == value } ?: throw ApiFailure.Protocol() }

class GraphqlPostpartumReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<PostpartumTask> {
    override suspend fun task(lease: ScopeLease, id: String): PostpartumTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativePostpartumTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validStage(rows.size == 1)
        val row = rows.single()
        validStage(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "POSTPARTUM")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validStage(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId)
            val location = TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location)
            val result = target.postpartum_pig?.let {
                stageOwner(it.task_id, it.pig_id, id, pig.id)
                SowCheckResult(it.is_disabled, it.check_at, it.check_status, it.description, it.farrowing_at, it.is_farrowed, it.is_obsoleted, it.obsoleted_reason, it.operator_id)
            }
            PostpartumPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, location, result)
        }
        stageUnique(pigs.map { it.id })
        return PostpartumTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
class GraphqlLactationReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<LactationTask> {
    override suspend fun task(lease: ScopeLease, id: String): LactationTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeLactationTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validStage(rows.size == 1)
        val row = rows.single()
        validStage(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "LACTATION")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validStage(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId)
            val location = TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location)
            val result = target.lactation_pig?.let { value ->
                stageOwner(value.task_id, value.pig_id, id, pig.id)
                validStage(listOf(value.female_count, value.male_count, value.no_identity_count).all { it >= 0 } && (value.init_total_count ?: 0) >= 0 && (value.total_weight?.signum() ?: 0) >= 0)
                val identities = value.identity_piglets.map {
                    stageOwner(it.task_id, it.pig_id, id, pig.id)
                    validStage(it.piglet_seq >= 0 && (it.birth_weight?.signum() ?: 0) >= 0)
                    LactationIdentity(IdentityPiglet(it.piglet_seq, it.birth_weight, it.ear_tag, it.ear_notch, stageSex(it.sex?.rawValue)), it.is_breeder)
                }
                val counts = value.piglet_counts.map {
                    stageOwner(it.task_id, it.pig_id, id, pig.id); validStage(it.count >= 0)
                    PigletCount(FarrowingPigletType.entries.find { code -> code.name == it.piglet_type.rawValue } ?: throw ApiFailure.Protocol(), it.count)
                }
                stageUnique(identities.map { it.piglet.sequence }); stageUnique(counts.map { it.type })
                LactationResult(SowCheckResult(value.is_disabled, value.check_at, value.check_status, value.description, value.farrowing_at, value.is_farrowed,
                    value.is_obsoleted, value.obsoleted_reason, value.operator_id), value.female_count, value.male_count, value.no_identity_count,
                    value.init_total_count, value.total_weight, identities, counts)
            }
            LactationPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, location, result)
        }
        stageUnique(pigs.map { it.id })
        return LactationTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
class GraphqlNurseryReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<GrowingTask> {
    override suspend fun task(lease: ScopeLease, id: String): GrowingTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeNurseryTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validStage(rows.size == 1)
        val row = rows.single()
        validStage(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "NURSERY")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validStage(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId && (pig.weight?.signum() ?: 0) >= 0)
            val result = target.nursery_pig?.let {
                stageOwner(it.task_id, it.pig_id, id, pig.id)
                validStage(it.location_code.isNotBlank())
                GrowingPigResult(it.has_identity, it.is_breeder, it.location_code)
            }
            GrowingPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location,
                pig.location?.pen_location), stageSex(pig.sex?.rawValue), pig.weight, result)
        }
        stageUnique(pigs.map { it.id })
        val locations = row.nursery_locations.map {
            validStage(it.task_id == id && it.location_code.isNotBlank() && it.total_count >= 0 && it.total_weight.signum() >= 0)
            NurseryLocation(it.location_code, it.check_at, it.created_at, it.operator_id, it.total_count, it.total_weight)
        }
        stageUnique(locations.map { it.code })
        return GrowingTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, GrowingStage.NURSERY, pigs, locations)
    }
}
class GraphqlFatteningReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<GrowingTask> {
    override suspend fun task(lease: ScopeLease, id: String): GrowingTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeFatteningTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validStage(rows.size == 1)
        val row = rows.single()
        validStage(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "FATTENING")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validStage(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId && (pig.weight?.signum() ?: 0) >= 0)
            val result = target.fattening_pig?.let {
                stageOwner(it.task_id, it.pig_id, id, pig.id)
                validStage(it.location_code.isNotBlank())
                GrowingPigResult(it.has_identity, it.is_breeder, it.location_code)
            }
            GrowingPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled, TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location,
                pig.location?.pen_location), stageSex(pig.sex?.rawValue), pig.weight, result)
        }
        stageUnique(pigs.map { it.id })
        val locations = emptyList<NurseryLocation>()
        return GrowingTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, GrowingStage.FATTENING, pigs, locations)
    }
}
