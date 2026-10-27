package com.sentri.nativeapp.production

import com.sentri.nativeapp.offline.QueuedCommand

internal object ProductionFosterProjection {
    private data class Inventory(val identities: List<IdentityPiglet>, val noIdentity: Int, val breeders: Map<Int, Boolean> = emptyMap())
    private fun invalid(disposition: ProjectionDisposition = ProjectionDisposition.INVALID_CONTEXT): Nothing = throw ProjectionProblem(disposition)
    private fun inventory(task: ProductionTaskSnapshot, pig: String): Inventory? = when (task) {
        is FarrowingTask -> task.pigs.find { it.id == pig }?.result?.let { Inventory(it.identities, it.noIdentityCount) }
        is PigletTask -> task.pigs.find { it.id == pig }?.result?.let { Inventory(it.identities, it.noIdentityCount) }
        is LactationTask -> task.pigs.find { it.id == pig }?.result?.let { Inventory(it.identities.map { p -> p.piglet }, it.noIdentityCount, it.identities.associate { p -> p.piglet.sequence to p.breeder }) }
        else -> null
    }
    private fun accepts(task: ProductionTaskSnapshot, pig: String): Boolean = when (task) {
        is FarrowingTask -> task.pigs.any { it.id == pig && !it.disabled && it.result?.status == FarrowingStatus.FARROWING }
        is PigletTask -> task.pigs.any { it.id == pig && !it.disabled && it.result?.farrowed == true }
        is LactationTask -> task.pigs.any { it.id == pig && !it.disabled && it.result?.sow?.farrowed == true }
        else -> false
    }
    private fun update(task: ProductionTaskSnapshot, pig: String, identities: List<IdentityPiglet>, noIdentity: Int, delta: Int,
        adoption: Boolean, breeders: Map<Int, Boolean>): ProductionTaskSnapshot = when (task) {
        is FarrowingTask -> task.copy(pigs = task.pigs.map { if (it.id != pig) it else it.copy(result = it.result!!.copy(identities = identities, noIdentityCount = noIdentity,
            adoptCount = if (adoption) Math.addExact(it.result.adoptCount, delta) else it.result.adoptCount,
            fosterCount = if (adoption) it.result.fosterCount else Math.addExact(it.result.fosterCount, delta))) })
        is PigletTask -> task.copy(pigs = task.pigs.map { if (it.id != pig) it else it.copy(result = it.result!!.copy(identities = identities, noIdentityCount = noIdentity,
            adoptCount = if (adoption) Math.addExact(it.result.adoptCount, delta) else it.result.adoptCount,
            fosterCount = if (adoption) it.result.fosterCount else Math.addExact(it.result.fosterCount, delta))) })
        is LactationTask -> task.copy(pigs = task.pigs.map { if (it.id != pig) it else it.copy(result = it.result!!.copy(identities = identities.map { identity ->
            LactationIdentity(identity, breeders[identity.sequence] ?: invalid(ProjectionDisposition.MISSING_CONTEXT)) }, noIdentityCount = noIdentity)) })
        else -> invalid()
    }
    fun apply(command: QueuedCommand, input: FosterPigletInput, tasks: MutableMap<String, ProductionTaskSnapshot>): LocalProductionRecord.Foster {
        val source = tasks[input.taskId] ?: invalid(ProjectionDisposition.MISSING_CONTEXT)
        if (source.type != input.stage.name || !accepts(source, input.pigId)) invalid()
        val stock = inventory(source, input.pigId) ?: invalid(ProjectionDisposition.MISSING_CONTEXT)
        val kinds = if (input.stage == FosterStage.LACTATION) listOf("LACTATION") else listOf("FARROWING", "PIGLET_PROCESSING", "LACTATION")
        // The reference helper prefers active farrowing, then piglet processing, then lactation in this batch.
        val active = tasks.values.filter { it.batchId == source.batchId && it.status in setOf("SCHEDULED", "RUNNING") }
        val adopt = kinds.firstNotNullOfOrNull { kind ->
            val candidates = active.filter { it.type == kind && accepts(it, input.adoptPigId) }
            if (candidates.size > 1) invalid(); candidates.singleOrNull()
        } ?: invalid(ProjectionDisposition.MISSING_CONTEXT)
        val target = inventory(adopt, input.adoptPigId) ?: invalid(ProjectionDisposition.MISSING_CONTEXT)
        val tags = input.earTags.orEmpty().toSet(); val notches = input.earNotches.orEmpty().toSet()
        val transferred = stock.identities.filter { it.earTag?.let(tags::contains) == true || it.earNotch?.let(notches::contains) == true }
        if (tags.any { tag -> transferred.none { it.earTag == tag } } || notches.any { notch -> transferred.none { it.earNotch == notch } } || input.noIdentityCount > stock.noIdentity) invalid(ProjectionDisposition.MISSING_CONTEXT)
        if (transferred.any { p -> target.identities.any { (p.earTag != null && p.earTag == it.earTag) || (p.earNotch != null && p.earNotch == it.earNotch) } }) invalid()
        val delta = Math.addExact(transferred.size, input.noIdentityCount)
        val left = stock.identities.filter { it !in transferred }
        // Sequence is the per-mother display ordinal; the local record keeps original identities and operation provenance.
        val additions = transferred.mapIndexed { index, identity -> identity.copy(sequence = Math.addExact(target.identities.maxOfOrNull { it.sequence } ?: 0, index + 1)) }
        val breeders = target.breeders + additions.zip(transferred).mapNotNull { (added, original) -> stock.breeders[original.sequence]?.let { added.sequence to it } }.toMap()
        if (adopt is LactationTask && additions.any { it.sequence !in breeders }) invalid(ProjectionDisposition.MISSING_CONTEXT)
        tasks[source.id] = update(source, input.pigId, left, stock.noIdentity - input.noIdentityCount, delta, false, stock.breeders)
        // When both pigs are in one task, preserve the source update while applying the adopt side.
        tasks[adopt.id] = update(requireNotNull(tasks[adopt.id]), input.adoptPigId, target.identities + additions,
            Math.addExact(target.noIdentity, input.noIdentityCount), delta, true, breeders)
        return LocalProductionRecord.Foster(command.record.id, source.id, input.pigId, adopt.id, input.adoptPigId, transferred, input.noIdentityCount)
    }
}
