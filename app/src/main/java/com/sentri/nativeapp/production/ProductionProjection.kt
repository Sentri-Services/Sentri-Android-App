package com.sentri.nativeapp.production

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*

enum class ProjectionDisposition {
    PREVIEW, WAITING_CONFIRMATION, REJECTED, WAITING_DEPENDENCY, WAITING_REFRESH,
    MISSING_CONTEXT, INVALID_CONTEXT, UNSUPPORTED, CYCLIC_DEPENDENCY, COMPLETION_REQUESTED, RESOURCE_READY,
}
/** Local rows have operation identities. They never impersonate server-generated record IDs or execution times. */
sealed interface LocalProductionRecord {
    val commandId: String
    val taskId: String
    val pigId: String
    data class Mating(override val commandId: String, override val taskId: String, override val pigId: String,
        val boarId: String?, val semenId: String?, val requestedAtMillis: Long) : LocalProductionRecord
    data class Farrowing(override val commandId: String, override val taskId: String, override val pigId: String,
        val addedAlive: Int, val addedDead: Int, val submittedAt: String?) : LocalProductionRecord
    data class Foster(override val commandId: String, override val taskId: String, override val pigId: String,
        val adoptTaskId: String, val adoptPigId: String, val identities: List<IdentityPiglet>, val noIdentityCount: Int) : LocalProductionRecord
}
data class ProductionEffect(val command: QueuedCommand, val disposition: ProjectionDisposition,
    val affectedTaskIds: Set<String>, val records: List<LocalProductionRecord> = emptyList(), val deaths: List<LocalPigDeath> = emptyList())
data class ProductionProjection(val serverTasks: Map<String, ProductionTaskSnapshot>, val previewTasks: Map<String, ProductionTaskSnapshot>,
    val serverMetadata: Map<String, TaskMetadata>, val previewMetadata: Map<String, TaskMetadata>, val effects: List<ProductionEffect>,
    val serverProfiles: Map<String, PigProfile> = emptyMap(), val previewProfiles: Map<String, PigProfile> = emptyMap())

/** Pure replay of known local intents. The queue and confirmed snapshots are never modified. */
object ProductionProjector {
    fun project(scope: DataScope, tasks: List<ProductionTaskSnapshot>, metadata: List<TaskMetadata>, commands: List<QueuedCommand>,
        reflectedCommandIds: Set<String> = emptySet(), profiles: List<PigProfile> = emptyList(), deathContext: PigDeathContext = PigDeathContext()): ProductionProjection {
        require(tasks.map { it.id }.distinct().size == tasks.size && metadata.map { it.id }.distinct().size == metadata.size)
        require(tasks.all { it.farmId == scope.farmId && it.pigIds.distinct().size == it.pigIds.size } && metadata.all { it.farmId == scope.farmId })
        val original = tasks.associateBy { it.id }; val originalMetadata = metadata.associateBy { it.id }
        require(profiles.map { it.id }.distinct().size == profiles.size)
        profiles.forEach { PigProfileValidation.profile(scope, it) }
        val originalProfiles = profiles.associateBy { it.id }
        require(deathContext.absentPigIds.none(originalProfiles::containsKey))
        original.values.forEach { task -> originalMetadata[task.id]?.let { require(it.batch.id == task.batchId && it.kind.name == task.type) } }
        val scoped = commands.filter { it.record.scope == scope }
        require(scoped.map { it.record.id }.distinct().size == scoped.size)
        require(scoped.all { it.record.operationName == it.input.operationName && it.record.productionType == it.input.productionType && it.record.groupKeys == it.input.groupKeys })
        val indexed = scoped.associateBy { it.record.id }
        val resources = scoped.filter { it.input is UploadPigDeathImageInput && it.record.status == CommandStatus.SYNCED &&
            runCatching { DeathImageReceipt.decode(requireNotNull(it.receipt)) }.isSuccess }.map { it.record.id }.toSet()
        require(reflectedCommandIds.all { indexed[it]?.record?.status == CommandStatus.SYNCED })
        val preview = original.toMutableMap(); val previewMetadata = originalMetadata.toMutableMap(); val effects = mutableListOf<ProductionEffect>()
        val previewProfiles = originalProfiles.toMutableMap()
        val applied = mutableSetOf<String>(); val remaining = scoped.filter { it.record.status != CommandStatus.CANCELLED && it.record.id !in reflectedCommandIds }.toMutableList()
        fun reaches(from: QueuedCommand, target: String): Boolean {
            val todo = ArrayDeque(from.record.dependsOn); val visited = mutableSetOf<String>()
            while (todo.isNotEmpty()) { val id = todo.removeFirst(); if (id == target) return true; if (visited.add(id)) indexed[id]?.record?.dependsOn?.let(todo::addAll) }
            return false
        }
        fun predecessors(command: QueuedCommand): Set<String> = command.record.dependsOn + scoped.filter { other ->
            other.record.id != command.record.id && other.record.status != CommandStatus.CANCELLED && other.record.id !in reflectedCommandIds &&
                CommandPolicy.before(other.record, command.record) && other.record.groups.any(command.record.groups::contains) && !reaches(other, command.record.id)
        }.map { it.record.id }
        while (remaining.isNotEmpty()) {
            val command = remaining.filter { candidate -> predecessors(candidate).none { id -> remaining.any { it.record.id == id } } }
                .minWithOrNull(compareBy<QueuedCommand> { it.record.createdAtMillis }.thenBy { it.record.id })
            if (command == null) {
                remaining.forEach { effects += ProductionEffect(it, ProjectionDisposition.CYCLIC_DEPENDENCY, setOfNotNull(it.input.taskId)) }; break
            }
            remaining.remove(command)
            val deps = predecessors(command)
            val disposition = when (command.record.status) {
                CommandStatus.SYNCED -> if (command.input !is UploadPigDeathImageInput) ProjectionDisposition.WAITING_REFRESH
                    else if (command.record.id in resources) ProjectionDisposition.RESOURCE_READY else ProjectionDisposition.INVALID_CONTEXT
                CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN -> ProjectionDisposition.WAITING_CONFIRMATION
                CommandStatus.FAILED, CommandStatus.CONFLICTED -> ProjectionDisposition.REJECTED
                CommandStatus.CANCELLED -> error("Excluded cancellation")
                CommandStatus.QUEUED -> when {
                    deps.any { indexed[it]?.record?.status == CommandStatus.SYNCED && it !in reflectedCommandIds && it !in resources } -> ProjectionDisposition.WAITING_REFRESH
                    deps.any { it !in applied && it !in reflectedCommandIds && it !in resources && indexed[it]?.record?.status != CommandStatus.CANCELLED } -> ProjectionDisposition.WAITING_DEPENDENCY
                    else -> null
                }
            }
            if (disposition != null) { effects += ProductionEffect(command, disposition, setOfNotNull(command.input.taskId)); continue }
            // A command is atomic even if it touches multiple tasks/pigs: preflight and apply to private copies.
            val next = preview.toMutableMap(); val nextMetadata = previewMetadata.toMutableMap(); val nextProfiles = previewProfiles.toMutableMap()
            val effect = try {
                when (command.input) {
                    is UpdatePigPropertyInput, is UpdateBreederRoleInput -> PigManagementProjection.apply(command, next, nextMetadata, nextProfiles)
                    is UploadPigDeathImageInput -> ProductionEffect(command, ProjectionDisposition.PREVIEW, emptySet())
                    is ReportPigDeathInput -> PigDeathProjection.apply(command, scoped, deathContext, next, nextMetadata, nextProfiles)
                    else -> ProductionProjectionRules.apply(command, next, nextMetadata)
                }
            }
            catch (failure: ProjectionProblem) { ProductionEffect(command, failure.disposition, setOfNotNull(command.input.taskId)) }
            catch (_: ArithmeticException) { ProductionEffect(command, ProjectionDisposition.INVALID_CONTEXT, setOfNotNull(command.input.taskId)) }
            effects += effect
            if (effect.disposition in setOf(ProjectionDisposition.PREVIEW, ProjectionDisposition.COMPLETION_REQUESTED)) {
                preview.clear(); preview.putAll(next); previewMetadata.clear(); previewMetadata.putAll(nextMetadata); applied += command.record.id
                previewProfiles.clear(); previewProfiles.putAll(nextProfiles)
            }
        }
        return ProductionProjection(original, preview.toMap(), originalMetadata, previewMetadata.toMap(), effects.toList(), originalProfiles, previewProfiles.toMap())
    }
}
internal class ProjectionProblem(val disposition: ProjectionDisposition) : RuntimeException()
