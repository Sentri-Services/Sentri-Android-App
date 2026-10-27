package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*

/** Completeness is supplied by a fully paged scoped membership read, never inferred from an empty task list. */
data class PigDeathContext(val taskIdsByPig: Map<String, Set<String>> = emptyMap(), val absentPigIds: Set<String> = emptySet())
enum class DeathPendingField { SERVER_ID, SERVER_TIME, IMAGE_METADATA, MORTALITY_COUNTERS, SOW_EXPORT_STATE }
data class LocalDeathImage(val reference: DeathImage, val confirmedUrl: String?)
/** Operation/row identity is local. pigId=null is a server-created pig, not a made-up server ID. */
data class LocalPigDeath(val commandId: String, val row: Int, val input: DeathPigInput, val images: List<LocalDeathImage>,
    val pendingFields: Set<DeathPendingField>)

internal object PigDeathProjection {
    private val active = setOf("PLANNED", "SCHEDULED", "RUNNING")
    private fun need(value: Boolean, kind: ProjectionDisposition = ProjectionDisposition.MISSING_CONTEXT) {
        if (!value) throw ProjectionProblem(kind)
    }

    fun apply(command: QueuedCommand, all: List<QueuedCommand>, context: PigDeathContext,
        tasks: MutableMap<String, ProductionTaskSnapshot>, metadata: MutableMap<String, TaskMetadata>, profiles: MutableMap<String, PigProfile>): ProductionEffect {
        val input = command.input as ReportPigDeathInput
        val uploads = try { DeathCommandReferences.uploads(command.record.operationId, input, all) }
        catch (_: IllegalArgumentException) { throw ProjectionProblem(ProjectionDisposition.INVALID_CONTEXT) }
        catch (_: IllegalStateException) { throw ProjectionProblem(ProjectionDisposition.INVALID_CONTEXT) }
        need(uploads.all { it.record.id in command.record.dependsOn }, ProjectionDisposition.INVALID_CONTEXT)
        need(uploads.none { it.record.status == CommandStatus.CANCELLED }, ProjectionDisposition.WAITING_DEPENDENCY)
        val receipts = uploads.filter { it.record.status == CommandStatus.SYNCED }.associate {
            it.record.operationId to try { DeathImageReceipt.decode(requireNotNull(it.receipt)).fileUrl }
            catch (_: Exception) { throw ProjectionProblem(ProjectionDisposition.INVALID_CONTEXT) }
        }
        fun intent(row: Int, pig: DeathPigInput, pending: Set<DeathPendingField>) = LocalPigDeath(command.record.id, row, pig,
            pig.images.map { LocalDeathImage(it, when (it) { is DeathImage.Remote -> it.url; is DeathImage.Uploaded -> receipts[it.operationId] }) }, pending)
        fun related(pigId: String): List<ProductionTaskSnapshot> {
            val ids = context.taskIdsByPig[pigId] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            return ids.map { id ->
                val task = tasks[id] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                need(task.status in active && pigId in task.pigIds)
                task
            }
        }
        // The handler checks every real pig before applying any row, including earlier piglet rows in this report.
        for ((row, pig) in input.pigs.withIndex()) {
            if (pig.pigId != null && pig.pigId in context.absentPigIds) continue
            if (pig.pigId != null) need(profiles.containsKey(pig.pigId))
            listOfNotNull(pig.pigId, pig.motherId).forEach(::related)
            if (pig.pigId != null && pig.motherId == null) {
                val lactations = related(pig.pigId).filterIsInstance<LactationTask>()
                for (task in lactations) {
                    val target = task.pigs.single { it.id == pig.pigId }
                    val result = target.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                    if (!result.sow.disabled && (result.noIdentityCount > 0 || result.identities.isNotEmpty())) {
                        // The checked-in SDL lacks is_exported. Do not assume false or reject an already exported litter.
                        return ProductionEffect(command, ProjectionDisposition.MISSING_CONTEXT, setOf(task.id),
                            deaths = listOf(intent(row, pig, setOf(DeathPendingField.SOW_EXPORT_STATE))))
                    }
                }
            }
        }
        val affected = mutableSetOf<String>(); val intents = mutableListOf<LocalPigDeath>()
        for ((row, pig) in input.pigs.withIndex()) {
            if (pig.pigId != null && pig.pigId in context.absentPigIds) continue
            val profile = pig.pigId?.let { profiles.getValue(it) }
            if (profile?.lifecycle == PigLifecycle.DEAD.name) continue
            need(profile == null || profile.lifecycle == PigLifecycle.ACTIVE.name, ProjectionDisposition.INVALID_CONTEXT)
            val effectiveTag = pig.earTag?.takeIf { it.isNotEmpty() } ?: profile?.earTag
            val effectiveNotch = pig.earNotch?.takeIf { it.isNotEmpty() } ?: profile?.earNotch
            val pending = mutableSetOf(DeathPendingField.SERVER_TIME)
            if (profile == null) pending += DeathPendingField.SERVER_ID
            if (pig.images.isNotEmpty()) pending += DeathPendingField.IMAGE_METADATA
            if (profile != null) {
                val explicitLocation = !pig.locationCode.isNullOrEmpty()
                val locationCode = pig.locationCode?.takeIf { it.isNotEmpty() } ?: profile.locationCode
                val after = profile.copy(lifecycle = PigLifecycle.DEAD.name, locationCode = locationCode,
                    location = if (locationCode == profile.locationCode) profile.location else null,
                    deathImages = if (pig.images.isEmpty()) JsonValue("[]") else null)
                profiles[profile.id] = after
                affected += PigProfileProjectionReferences.update(after,
                    if (explicitLocation) setOf(PigProfileField.LOCATION) else emptySet(), false, tasks, metadata)
                val matches = related(profile.id)
                // Backend selects results[0] for each actual-pig task type without an order. Multiple matches are ambiguous.
                need(matches.groupBy { it.type }.values.all { it.size <= 1 })
                for (task in matches) {
                    val sowStage = task is FarrowingTask || task is PigletTask || task is PostpartumTask || task is LactationTask
                    if (pig.motherId != null && sowStage) continue
                    tasks[task.id] = disable(task, profile.id)
                    metadata[task.id]?.let { meta -> metadata[task.id] = meta.copy(pigs = meta.pigs.map { if (it.id == profile.id) it.copy(disabled = true) else it }) }
                    affected += task.id
                }
            }
            pig.motherId?.let { mother ->
                for (task in related(mother)) {
                    val mortalityPending = (task as? FarrowingTask)?.pigs?.single { it.id == mother }?.result?.disabled == false ||
                        (task as? LactationTask)?.pigs?.single { it.id == mother }?.result?.sow?.disabled == false
                    if (mortalityPending) pending += DeathPendingField.MORTALITY_COUNTERS
                    val adjusted = reduce(task, mother, effectiveTag, effectiveNotch)
                    if (adjusted != task) { tasks[task.id] = adjusted; affected += task.id }
                }
            }
            intents += intent(row, pig, pending)
        }
        return ProductionEffect(command, ProjectionDisposition.PREVIEW, affected, deaths = intents)
    }

    private fun disable(task: ProductionTaskSnapshot, id: String): ProductionTaskSnapshot = when (task) {
        is EstrusTask -> task.copy(pigs = task.pigs.map { if (it.id == id) it.copy(disabled = true) else it })
        is MatingTask -> task.copy(pigs = task.pigs.map { if (it.id == id) it.copy(disabled = true) else it })
        is ReEstrusTask -> task.copy(pigs = task.pigs.map { if (it.id == id) it.copy(disabled = true) else it })
        is PregnancyTask -> task.copy(pigs = task.pigs.map { if (it.id == id) it.copy(disabled = true) else it })
        is GrowingTask -> task.copy(pigs = task.pigs.map { if (it.id == id) it.copy(disabled = true) else it })
        is FarrowingTask -> task.copy(pigs = task.pigs.map { if (it.id != id) it else {
            val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            need(!result.disabled, ProjectionDisposition.INVALID_CONTEXT); it.copy(disabled = true, result = result.copy(disabled = true))
        } })
        is PigletTask -> task.copy(pigs = task.pigs.map { if (it.id != id) it else {
            val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            need(!result.disabled, ProjectionDisposition.INVALID_CONTEXT); it.copy(disabled = true, result = result.copy(disabled = true))
        } })
        is PostpartumTask -> task.copy(pigs = task.pigs.map { if (it.id != id) it else {
            val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            need(!result.disabled, ProjectionDisposition.INVALID_CONTEXT); it.copy(disabled = true, result = result.copy(disabled = true))
        } })
        is LactationTask -> task.copy(pigs = task.pigs.map { if (it.id != id) it else {
            val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            need(!result.sow.disabled, ProjectionDisposition.INVALID_CONTEXT); it.copy(disabled = true, result = result.copy(sow = result.sow.copy(disabled = true)))
        } })
        else -> throw ProjectionProblem(ProjectionDisposition.UNSUPPORTED)
    }

    private fun reduce(task: ProductionTaskSnapshot, mother: String, tag: String?, notch: String?): ProductionTaskSnapshot {
        fun selected(value: IdentityPiglet) = (!tag.isNullOrEmpty() && tag == value.earTag) || (!notch.isNullOrEmpty() && notch == value.earNotch)
        fun identities(values: List<IdentityPiglet>): List<IdentityPiglet> {
            val index = values.indexOfFirst(::selected)
            return if (index < 0) values else values.filterIndexed { i, _ -> i != index }
        }
        fun anonymous(count: Int) = if (tag.isNullOrEmpty() && notch.isNullOrEmpty()) (count - 1).coerceAtLeast(0) else count
        return when (task) {
            is FarrowingTask -> task.copy(pigs = task.pigs.map { if (it.id != mother) it else {
                val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                if (result.disabled) it else it.copy(result = result.copy(identities = identities(result.identities), noIdentityCount = anonymous(result.noIdentityCount)))
            } })
            is PigletTask -> task.copy(pigs = task.pigs.map { if (it.id != mother) it else {
                val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                if (result.disabled) it else it.copy(result = result.copy(identities = identities(result.identities), noIdentityCount = anonymous(result.noIdentityCount)))
            } })
            is LactationTask -> task.copy(pigs = task.pigs.map { if (it.id != mother) it else {
                val result = it.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
                val index = result.identities.indexOfFirst { value -> selected(value.piglet) }
                if (result.sow.disabled) it else it.copy(result = result.copy(
                    identities = if (index < 0) result.identities else result.identities.filterIndexed { i, _ -> i != index }, noIdentityCount = anonymous(result.noIdentityCount)))
            } })
            else -> task
        }
    }
}
