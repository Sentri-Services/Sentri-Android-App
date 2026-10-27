package com.sentri.nativeapp.production

import com.sentri.nativeapp.graphql.NativeFarrowingTaskQuery
import com.sentri.nativeapp.graphql.NativePigletTaskQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.pigs.ScopeLease

private fun validCare(condition: Boolean) { if (!condition) throw ApiFailure.Protocol() }
private inline fun <reified T : Enum<T>> careEnum(raw: String): T = enumValues<T>().find { it.name == raw } ?: throw ApiFailure.Protocol()
private fun owner(task: String, pig: String, expectedTask: String, expectedPig: String) = validCare(task == expectedTask && pig == expectedPig)
private fun unique(values: List<Any>) = validCare(values.distinct().size == values.size)

class GraphqlFarrowingReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<FarrowingTask> {
    override suspend fun task(lease: ScopeLease, id: String): FarrowingTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativeFarrowingTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validCare(rows.size == 1)
        val row = rows.single()
        validCare(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "FARROWING")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validCare(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId)
            val result = target.farrowing_pig?.let { value ->
                owner(value.task_id, value.pig_id, id, pig.id)
                validCare(value.adopt_count >= 0 && value.foster_count >= 0 && value.no_identity_count >= 0)
                val identities = value.identity_piglets.map {
                    owner(it.task_id, it.pig_id, id, pig.id)
                    validCare(it.piglet_seq >= 0 && (it.birth_weight?.signum() ?: 0) >= 0)
                    IdentityPiglet(it.piglet_seq, it.birth_weight, it.ear_tag, it.ear_notch, it.sex?.rawValue?.let { raw -> careEnum<PigletSex>(raw) })
                }
                val counts = value.piglet_statistic.map {
                    owner(it.task_id, it.pig_id, id, pig.id); validCare(it.count >= 0)
                    PigletCount(careEnum(it.piglet_type.rawValue), it.count)
                }
                val records = value.records.map {
                    owner(it.task_id, it.pig_id, id, pig.id); validCare(it.alive_count >= 0 && it.dead_count >= 0)
                    FarrowingRecord(it.id, it.alive_count, it.dead_count, it.submitted_at, it.operator_id)
                }
                unique(identities.map { it.sequence }); unique(counts.map { it.type }); unique(records.map { it.id })
                FarrowingResult(careEnum(value.status.rawValue), value.is_disabled, value.adopt_count, value.foster_count, value.no_identity_count,
                    value.first_farrowing_at, value.last_farrowing_at, value.mating_at, value.mating_by, value.re_check_at, value.operator_id, identities, counts, records)
            }
            FarrowingPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled,
                TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location), result)
        }
        unique(pigs.map { it.id })
        return FarrowingTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}

class GraphqlPigletReads(private val executor: GraphqlExecutor, private val check: (ScopeLease) -> Unit) : TaskRemote<PigletTask> {
    override suspend fun task(lease: ScopeLease, id: String): PigletTask? {
        require(id.isNotBlank()); check(lease)
        val rows = executor.query(NativePigletTaskQuery(id, lease.scope.farmId)).tasks
        check(lease)
        if (rows.isEmpty()) return null
        validCare(rows.size == 1)
        val row = rows.single()
        validCare(row.id == id && row.batch?.farm_id == lease.scope.farmId && row.task_type.rawValue == "PIGLET_PROCESSING")
        val pigs = row.pigs.map { target ->
            val pig = target.pig ?: throw ApiFailure.Protocol()
            validCare(pig.id == target.pig_id && pig.farm_id == lease.scope.farmId)
            val result = target.piglet_handling_pig?.let { value ->
                owner(value.task_id, value.pig_id, id, pig.id)
                validCare(listOf(value.adopt_count, value.foster_count, value.no_identity_count, value.female_count, value.male_count, value.litter_number).all { it >= 0 })
                validCare((value.total_weight?.signum() ?: 0) >= 0)
                val identities = value.identity_piglets.map {
                    owner(it.task_id, it.pig_id, id, pig.id); validCare(it.piglet_seq >= 0 && (it.birth_weight?.signum() ?: 0) >= 0)
                    IdentityPiglet(it.piglet_seq, it.birth_weight, it.ear_tag, it.ear_notch, it.sex?.rawValue?.let { raw -> careEnum<PigletSex>(raw) })
                }
                val todo = value.todo_items.map {
                    owner(it.task_id, it.pig_id, id, pig.id)
                    PigletTodo(careEnum(it.item_type.rawValue), it.plan_days, it.completed_at, it.operator_id)
                }
                unique(identities.map { it.sequence }); unique(todo.map { it.type })
                PigletResult(value.is_disabled, value.is_farrowed, value.adopt_count, value.foster_count, value.no_identity_count, value.female_count, value.male_count,
                    value.litter_number, value.last_farrowing_at, value.total_weight, value.items, identities, todo)
            }
            PigletPig(pig.id, pig.ear_tag, pig.ear_notch, target.disabled,
                TaskPigLocation(pig.location_code, pig.location?.unit_id, pig.location?.unit_location, pig.location?.pen_location), result)
        }
        unique(pigs.map { it.id })
        return PigletTask(row.id, lease.scope.farmId, row.batch_id, row.status.rawValue, pigs)
    }
}
