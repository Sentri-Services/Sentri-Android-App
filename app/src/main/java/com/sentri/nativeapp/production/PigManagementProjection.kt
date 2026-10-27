package com.sentri.nativeapp.production

import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.pigs.*

/** Mirrors Pig.update_property and PigBreederService, including ordered duplicate targets and atomic failure. */
internal object PigManagementProjection {
    private fun requireValid(value: Boolean) { if (!value) throw ProjectionProblem(ProjectionDisposition.INVALID_CONTEXT) }
    private fun active(profile: PigProfile) = requireValid(profile.lifecycle == PigLifecycle.ACTIVE.name)

    fun apply(command: QueuedCommand, tasks: MutableMap<String, ProductionTaskSnapshot>, metadata: MutableMap<String, TaskMetadata>,
        profiles: MutableMap<String, PigProfile>): ProductionEffect {
        val affected = mutableSetOf<String>()
        fun change(id: String, fields: Set<PigProfileField> = emptySet(), roleChanged: Boolean = false, update: (PigProfile) -> PigProfile) {
            val before = profiles[id] ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)
            active(before)
            val after = update(before)
            profiles[id] = after
            affected += PigProfileProjectionReferences.update(after, fields, roleChanged, tasks, metadata)
        }
        when (val input = command.input) {
            is UpdatePigPropertyInput -> change(input.pigId, fields = buildSet {
                if (input.sex != null) add(PigProfileField.SEX)
                if (input.weight != null) add(PigProfileField.WEIGHT)
            }) { before ->
                before.copy(lifecycle = input.lifecycle?.name ?: before.lifecycle, sex = input.sex?.name ?: before.sex,
                    teatCount = input.teats ?: before.teatCount, weight = input.weight ?: before.weight, remark = input.remark ?: before.remark)
            }
            is UpdateBreederRoleInput -> {
                // The backend's input dictionary uses the last input, but its aggregate list still contains every occurrence.
                val inputs = input.pigs.associateBy { it.pigId }
                for (target in input.pigs) {
                    val value = inputs.getValue(target.pigId)
                    val providedIdentity = !value.earTag.isNullOrEmpty() || !value.earNotch.isNullOrEmpty()
                    val fields = buildSet {
                        if (input.action == BreederRoleAction.MARK_AS_BREEDER) {
                            if (providedIdentity) {
                                if (value.earTag != null) add(PigProfileField.EAR_TAG)
                                if (value.earNotch != null) add(PigProfileField.EAR_NOTCH)
                            }
                            if (value.sex != null) add(PigProfileField.SEX)
                        }
                    }
                    change(target.pigId, fields, roleChanged = true) { before ->
                        var after = before
                        val breeder = before.role in setOf(PigRole.BREEDER_GILT.name, PigRole.BREEDER_BOAR.name)
                        val role = when (input.action) {
                            BreederRoleAction.MARK_AS_BREEDER -> {
                                requireValid(before.role in setOf(PigRole.PIGLET.name, PigRole.FATTENING.name))
                                requireValid(!before.earTag.isNullOrEmpty() || !before.earNotch.isNullOrEmpty() || providedIdentity)
                                requireValid(value.sex != null || before.sex != null)
                                if (providedIdentity) after = after.copy(earTag = value.earTag ?: before.earTag, earNotch = value.earNotch ?: before.earNotch)
                                after = after.copy(sex = value.sex?.name ?: before.sex)
                                // Unknown read codes cannot be interpreted as male by a local client.
                                requireValid(after.sex in PigletSex.entries.map { it.name })
                                if (after.sex == PigletSex.FEMALE.name) PigRole.BREEDER_GILT else PigRole.BREEDER_BOAR
                            }
                            BreederRoleAction.UPGRADE_TO_PRODUCTION -> {
                                requireValid(breeder)
                                if (before.role == PigRole.BREEDER_GILT.name) PigRole.PRODUCTION_SOW else PigRole.PRODUCTION_BOAR
                            }
                            BreederRoleAction.CANCEL_TO_FATTENING -> { requireValid(breeder); PigRole.FATTENING }
                            BreederRoleAction.CANCEL_TO_PIGLET -> { requireValid(breeder); PigRole.PIGLET }
                        }
                        // Parent references and markedAt are retained in the intent; this endpoint does not update parent links.
                        after.copy(role = role.name)
                    }
                }
            }
            else -> throw ProjectionProblem(ProjectionDisposition.UNSUPPORTED)
        }
        return ProductionEffect(command, ProjectionDisposition.PREVIEW, affected)
    }
}

internal enum class PigProfileField { EAR_TAG, EAR_NOTCH, SEX, WEIGHT, LOCATION }

/** Propagates explicit writes, including equal values; untouched task fields retain their independently read values. */
internal object PigProfileProjectionReferences {
    fun update(after: PigProfile, fields: Set<PigProfileField>, roleChanged: Boolean, tasks: MutableMap<String, ProductionTaskSnapshot>,
        metadata: MutableMap<String, TaskMetadata>): Set<String> {
        val writeTag = PigProfileField.EAR_TAG in fields; val writeNotch = PigProfileField.EAR_NOTCH in fields
        val writeSex = PigProfileField.SEX in fields; val writeWeight = PigProfileField.WEIGHT in fields
        val writeLocation = PigProfileField.LOCATION in fields
        fun tag(old: String?) = if (writeTag) after.earTag else old
        fun notch(old: String?) = if (writeNotch) after.earNotch else old
        fun location(old: TaskPigLocation?) = if (!writeLocation) old else TaskPigLocation(after.locationCode,
            after.location?.unitId, after.location?.unitLocation, after.location?.penLocation)
        val affected = mutableSetOf<String>()
        for ((id, task) in tasks.toMap()) {
            if (after.id !in task.pigIds) continue
            val growerRole = roleChanged && task is GrowingTask && task.status in setOf("PLANNED", "SCHEDULED", "RUNNING")
            if (!writeTag && !writeNotch && !writeLocation && !(task is GrowingTask && (writeSex || writeWeight || growerRole))) continue
            tasks[id] = when (task) {
                is EstrusTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = location(it.location)) else it })
                is MatingTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = location(it.location)) else it })
                is ReEstrusTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is PregnancyTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is FarrowingTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is PigletTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is PostpartumTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is LactationTask -> task.copy(pigs = task.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), location = requireNotNull(location(it.location))) else it })
                is GrowingTask -> task.copy(pigs = task.pigs.map { pig -> if (pig.id != after.id) pig else pig.copy(
                    earTag = tag(pig.earTag), earNotch = notch(pig.earNotch), location = requireNotNull(location(pig.location)),
                    sex = if (writeSex) PigletSex.entries.singleOrNull { it.name == after.sex } else pig.sex,
                    weight = if (writeWeight) after.weight else pig.weight,
                    result = if (!growerRole) pig.result else (pig.result ?: throw ProjectionProblem(ProjectionDisposition.MISSING_CONTEXT)).copy(
                        breeder = after.role in setOf(PigRole.BREEDER_GILT.name, PigRole.BREEDER_BOAR.name))) })
                else -> throw ProjectionProblem(ProjectionDisposition.UNSUPPORTED)
            }
            affected += id
        }
        if (writeTag || writeNotch || writeLocation) for ((id, value) in metadata.toMap()) {
            if (value.pigs.none { it.id == after.id }) continue
            metadata[id] = value.copy(pigs = value.pigs.map { if (it.id == after.id) it.copy(earTag = tag(it.earTag), earNotch = notch(it.earNotch), locationCode = if (writeLocation) after.locationCode else it.locationCode) else it })
            affected += id
        }
        return affected
    }
}
