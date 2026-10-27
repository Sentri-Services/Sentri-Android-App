package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.offline.QueuedCommand

/** Operation-specific preview rules, grounded in the reference backend and legacy local adapters. */
internal object ProductionProjectionRules {
    private fun need(condition: Boolean, disposition: ProjectionDisposition = ProjectionDisposition.MISSING_CONTEXT) { if (!condition) throw ProjectionProblem(disposition) }
    private inline fun <reified T : ProductionTaskSnapshot> task(id: String, tasks: Map<String, ProductionTaskSnapshot>): T {
        val value = tasks[id] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
        return value as? T ?: throw ProjectionProblem(ProjectionDisposition.INVALID_CONTEXT)
    }
    private fun count(vararg values: Int): Int = values.fold(0, Math::addExact)
    private fun healthy(kind: FarrowingPigletType) = kind in setOf(FarrowingPigletType.HEALTHY, FarrowingPigletType.WEAK, FarrowingPigletType.MALFORMED)
    private fun dead(kind: FarrowingPigletType) = kind in setOf(FarrowingPigletType.STILLBORN, FarrowingPigletType.MUMMY)
    private fun identities(values: List<IdentityPigletInput>?) = values.orEmpty().mapIndexed { i, v -> IdentityPiglet(i + 1, v.birthWeight, v.earTag, v.earNotch, v.sex) }
    private fun assessment(value: SowAssessment) = JsonValueAdapter.validate("""{"activity_level":"${value.activity}","backfat_thickness":"${value.backfat}","body_condition_score":"${value.body}","feeding_situation":"${value.feeding}","mastitis_severity":"${value.mastitis}","milk_production_capacity":"${value.milk}","secretion_status":"${value.secretion}","prolapse_type":${value.prolapse?.let { "\"$it\"" } ?: "null"},"prolapse_recurred":${value.recurred},"prolapse_resolved":${value.resolved}}""")
    private fun active(task: ProductionTaskSnapshot) = task.status in setOf("SCHEDULED", "RUNNING")

    fun apply(command: QueuedCommand, tasks: MutableMap<String, ProductionTaskSnapshot>, metadata: MutableMap<String, TaskMetadata>): ProductionEffect {
        val input = command.input; val affected = mutableSetOf<String>(); val records = mutableListOf<LocalProductionRecord>()
        input.taskId?.let(affected::add)
        input.taskId?.let { id ->
            tasks[id]?.let { need(active(it), ProjectionDisposition.INVALID_CONTEXT) }
            metadata[id]?.let { need(it.status in setOf(ProductionTaskStatus.RUNNING, ProductionTaskStatus.SCHEDULED), ProjectionDisposition.INVALID_CONTEXT) }
        }
        fun <T> targets(rows: List<T>, ids: Set<String>, id: (T) -> String) { need(ids.all { target -> rows.any { id(it) == target } }) }
        when (input) {
            is MarkTaskPigInput -> {
                val value = metadata[input.taskId] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                need(value.batch.id == input.batchId, ProjectionDisposition.INVALID_CONTEXT); targets(value.pigs, setOf(input.pigId)) { it.id }
                metadata[value.id] = value.copy(pigs = value.pigs.map { if (it.id == input.pigId) it.copy(mark = input.remark) else it })
            }
            is CompleteTaskInput -> {
                val value = metadata[input.taskId] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                need(value.batch.id == input.batchId && value.kind == input.kind, ProjectionDisposition.INVALID_CONTEXT)
                // The existing entry completes only online after unresolved operations finish. Keep server status until confirmation.
                return ProductionEffect(command, ProjectionDisposition.COMPLETION_REQUESTED, affected)
            }
            is CheckEstrusInput -> {
                val value = task<EstrusTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == input.pigId) it.copy(checkStatus = input.status, checkAt = input.checkAt) else it })
            }
            is SubmitEstrusInput -> {
                val value = task<EstrusTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val selected = value.pigs.filter { it.id in input.targetPigIds && !it.disabled && it.isInEstrus != true && (input.autoEstrus || it.checkStatus == EstrusStatus.IN_ESTRUS) }
                tasks[value.id] = value.copy(pigs = value.pigs.map { pig -> if (pig !in selected) pig else pig.copy(isInEstrus = true, estrusAt = input.submitAt,
                    checkStatus = if (input.autoEstrus) EstrusStatus.IN_ESTRUS else pig.checkStatus, checkAt = if (input.autoEstrus) input.submitAt else pig.checkAt) })
                val related = tasks.values.filterIsInstance<MatingTask>().filter { it.batchId == value.batchId && active(it) }
                need(related.size <= 1, ProjectionDisposition.INVALID_CONTEXT)
                if (selected.isNotEmpty()) {
                    need(related.size == 1)
                    val next = related.single(); val additions = selected.filter { it.id !in next.pigIds }.map {
                        MatingPig(it.id, it.earTag, it.earNotch, false, MatingSummary(0, input.submitAt, null, null, null, null, null), emptyList(), it.location)
                    }
                    tasks[next.id] = next.copy(pigs = next.pigs + additions); affected += next.id
                    metadata[next.id]?.let { meta -> metadata[next.id] = meta.copy(pigs = meta.pigs + additions.filter { p -> meta.pigs.none { it.id == p.id } }.map {
                        TaskPigReference(it.id, false, it.earTag, it.earNotch, it.location?.locationCode, null)
                    }) }
                }
            }
            is MateWithBoarInput, is MateWithSemenInput -> {
                val value = task<MatingTask>(requireNotNull(input.taskId), tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val pigId = input.targetPigIds.single()
                records += LocalProductionRecord.Mating(command.record.id, value.id, pigId, (input as? MateWithBoarInput)?.boarId,
                    (input as? MateWithSemenInput)?.semenId, command.record.createdAtMillis)
            }
            is CheckReEstrusInput -> {
                val value = task<ReEstrusTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                need(value.pigs.single { it.id == input.pigId }.result != null)
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == input.pigId) it.copy(result = it.result!!.copy(status = input.status, checkedAt = input.checkAt)) else it })
            }
            is SubmitReEstrusInput -> {
                val value = task<ReEstrusTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                need(value.pigs.filter { it.id in input.targetPigIds }.all { it.result != null })
                val returned = value.pigs.filter { it.id in input.targetPigIds && !it.disabled && it.result?.isInReEstrus != true && it.result?.status == ReEstrusStatus.RE_ESTRUS }.map { it.id }.toSet()
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id in returned) it.copy(disabled = true, result = it.result!!.copy(isInReEstrus = true, reEstrusAt = input.submitAt)) else it })
                val next = tasks.values.filterIsInstance<PregnancyTask>().filter { it.batchId == value.batchId && active(it) }
                need(next.size <= 1, ProjectionDisposition.INVALID_CONTEXT)
                if (returned.isNotEmpty()) { need(next.size == 1); next.single().let { pregnancy -> tasks[pregnancy.id] = pregnancy.copy(pigs = pregnancy.pigs.map { if (it.id in returned) it.copy(disabled = true) else it }); affected += pregnancy.id } }
            }
            is CheckPregnancyInput -> {
                val value = task<PregnancyTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val pig = value.pigs.single { it.id == input.pigId }; need(pig.result != null)
                // reCheckDays remains in the effect's exact input; the backend computes its date using server-time fallback.
                if (pig.result!!.isPregnant == null) tasks[value.id] = value.copy(pigs = value.pigs.map {
                    if (it.id == input.pigId) it.copy(result = it.result!!.copy(status = input.status, checkedAt = input.checkAt)) else it })
            }
            is SubmitPregnancyInput -> {
                val value = task<PregnancyTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                need(value.pigs.filter { it.id in input.targetPigIds }.all { it.result != null })
                tasks[value.id] = value.copy(pigs = value.pigs.map { pig ->
                    val result = pig.result
                    if (pig.id !in input.targetPigIds || pig.disabled || result?.status == null || result.status == PregnancyStatus.NOT_SURE) pig
                    else pig.copy(disabled = pig.disabled || result.status == PregnancyStatus.NOT_PREGNANT,
                        result = result.copy(isPregnant = result.status == PregnancyStatus.PREGNANT, submittedAt = input.submitAt))
                })
            }
            is SubmitFarrowingInput -> {
                val value = task<FarrowingTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val pig = value.pigs.single { it.id == input.pigId }; val old = pig.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                need(old.status != FarrowingStatus.COMPLETED, ProjectionDisposition.INVALID_CONTEXT)
                val previous = old.counts.associate { it.type to it.count }; val requested = input.counts.orEmpty().associate { it.type to it.count }
                val merged = previous.toMutableMap(); requested.forEach { (kind, n) -> merged[kind] = maxOf(n, previous[kind] ?: 0) }
                val deltas = requested.mapValues { (kind, n) -> (n - (previous[kind] ?: 0)).coerceAtLeast(0) }
                val addedAlive = deltas.filterKeys(::healthy).values.fold(0, Math::addExact); val addedDead = deltas.filterKeys(::dead).values.fold(0, Math::addExact)
                val addedStock = deltas.filterKeys { !dead(it) }.values.fold(0, Math::addExact)
                val updated = old.copy(status = if (input.completed || requested.values.all { it == 0 }) FarrowingStatus.COMPLETED else FarrowingStatus.FARROWING,
                    firstAt = old.firstAt ?: input.submitAt, lastAt = input.submitAt, noIdentityCount = count(old.noIdentityCount, addedStock), counts = merged.map { PigletCount(it.key, it.value) })
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == pig.id) it.copy(result = updated) else it })
                records += LocalProductionRecord.Farrowing(command.record.id, value.id, pig.id, addedAlive, addedDead, input.submitAt)
            }
            is SubmitPigletItemsInput -> {
                val value = task<PigletTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }; need(value.pigs.single { it.id == input.pigId }.result != null)
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id != input.pigId) it else it.copy(result = it.result!!.copy(todo = it.result.todo.map { todo -> if (todo.type in input.items) todo.copy(completedAt = input.submitAt, operatorId = command.record.scope.userId) else todo })) })
            }
            is SubmitPigletInfoInput -> {
                val value = task<PigletTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val pig = value.pigs.single { it.id == input.pigId }; val old = pig.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                val ids = identities(input.identities); val total = count(input.femaleCount, input.maleCount); need(ids.size <= total, ProjectionDisposition.INVALID_CONTEXT)
                val updated = old.copy(femaleCount = input.femaleCount, maleCount = input.maleCount, identities = ids, noIdentityCount = total - ids.size,
                    totalWeight = input.totalWeight ?: old.totalWeight, todo = old.todo.map { if (it.type == PigletHandlingItem.INFO_SUBMISSION) it.copy(completedAt = input.submitAt, operatorId = command.record.scope.userId) else it })
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == pig.id) it.copy(result = updated) else it })
            }
            is SubmitPigletWeightInput -> {
                val value = task<PigletTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }; need(value.pigs.single { it.id == input.pigId }.result != null)
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == input.pigId) it.copy(result = it.result!!.copy(totalWeight = input.totalWeight)) else it })
            }
            is SubmitPostpartumInput -> {
                val value = task<PostpartumTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }; need(value.pigs.single { it.id == input.pigId }.result != null)
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id != input.pigId) it else it.copy(result = it.result!!.copy(assessment = assessment(input.assessment), checkedAt = input.checkedAt,
                    description = input.description, obsoleted = input.obsoleted, obsoletedReason = input.obsoletedReason)) })
            }
            is SubmitLactationInput -> {
                val value = task<LactationTask>(input.taskId, tasks); targets(value.pigs, input.targetPigIds) { it.id }
                val pig = value.pigs.single { it.id == input.pigId }; val old = pig.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                val ids = identities(input.identities).mapIndexed { i, p -> LactationIdentity(p, input.identities!![i].breeder) }
                val counts = input.counts.orEmpty().associate { it.type to it.count }.map { PigletCount(it.key, it.value) }
                val total = counts.filter { healthy(it.type) }.map { it.count }.fold(0, Math::addExact)
                val sow = old.sow.copy(assessment = input.assessment?.let(::assessment) ?: old.sow.assessment,
                    checkedAt = if (input.assessment != null) input.checkedAt else old.sow.checkedAt, description = input.description, obsoleted = input.obsoleted, obsoletedReason = input.obsoletedReason)
                val updated = old.copy(sow = sow, femaleCount = input.femaleCount, maleCount = input.maleCount, totalWeight = input.totalWeight ?: old.totalWeight,
                    identities = ids, counts = counts, noIdentityCount = (total - ids.size).coerceAtLeast(0))
                tasks[value.id] = value.copy(pigs = value.pigs.map { if (it.id == pig.id) it.copy(result = updated) else it })
            }
            is CheckGrowingInput -> {
                val value = task<GrowingTask>(input.taskId, tasks); need(value.stage == input.stage, ProjectionDisposition.INVALID_CONTEXT)
                val aggregate = input.assessment
                val locations = if (value.stage == GrowingStage.FATTENING) value.locations else {
                    need(value.locations.any { it.code == input.locationCode })
                    value.locations.map { if (it.code != input.locationCode) it else it.copy(checkedAt = input.checkedAt,
                        totalCount = count(aggregate.healthyCount, aggregate.weakCount, aggregate.malformedCount), totalWeight = aggregate.totalWeight) }
                }
                // The backend owns newly created pigs. Existing identity references can be previewed without inventing IDs.
                val ids = input.identities.orEmpty().associateBy { it.pigId }; need(ids.size == input.identities.orEmpty().size, ProjectionDisposition.INVALID_CONTEXT)
                need(ids.keys.all { it in value.pigIds })
                tasks[value.id] = value.copy(locations = locations, pigs = value.pigs.map { pig -> ids[pig.id]?.let { identity ->
                    pig.copy(earTag = identity.earTag, earNotch = identity.earNotch, sex = identity.sex, weight = identity.weight,
                        result = GrowingPigResult(identity.hasIdentity, identity.breeder, identity.locationCode), location = pig.location.copy(locationCode = identity.locationCode))
                } ?: pig })
            }
            is FosterPigletInput -> {
                val transfer = ProductionFosterProjection.apply(command, input, tasks)
                affected += transfer.adoptTaskId; records += transfer
            }
            else -> throw ProjectionProblem(ProjectionDisposition.UNSUPPORTED)
        }
        return ProductionEffect(command, ProjectionDisposition.PREVIEW, affected, records)
    }
}
