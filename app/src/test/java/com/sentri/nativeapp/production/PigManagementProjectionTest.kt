package com.sentri.nativeapp.production

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigManagementProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
    private val original = PigProfile(id = "pig", farmId = "farm", earTag = "OldTag", earNotch = "OldNotch", extendedEarTag = "ExCase",
        role = "PIGLET", sex = "MALE", lifecycle = "ACTIVE", origin = "SELFBRED", locationCode = "z.u.p", birthday = null, entryDate = null,
        remark = "old", weight = BigDecimal("12.123456789012345678900"), parity = null, breedType = null, breederCode = null, teatCount = 12,
        firstEstrusAt = null, lastEstrusAt = null, lastFarrowingAt = null, lastLactationAt = null, lastMatingAt = null, productionStatus = null,
        currentBatchId = "batch", breederProductionStatus = null, batch = null, semen = null, location = null, health = null, deathImages = null)
    private val growing = GrowingTask("n", "farm", "batch", "RUNNING", GrowingStage.NURSERY,
        listOf(GrowingPig("pig", "TaskTag", "TaskNotch", false, location, PigletSex.MALE, BigDecimal.ONE, GrowingPigResult(true, false, "z.u.p"))), emptyList())
    private fun command(id: String, input: CommandInput, created: Long = 0, status: CommandStatus = CommandStatus.QUEUED, deps: Set<String> = emptySet()) =
        QueuedCommand(CommandRecord(id, "op-$id", scope, input.operationName, input.productionType, input.groupKeys, deps, created, status), input)
    private fun role(action: BreederRoleAction, id: String = "pig", tag: String? = null, notch: String? = null, sex: PigletSex? = null) =
        UpdateBreederRoleInput(action, listOf(BreederPigInput(id, tag, notch, sex, "mother", "father")), null)
    private fun project(commands: List<QueuedCommand>, profiles: List<PigProfile> = listOf(original), tasks: List<ProductionTaskSnapshot> = listOf(growing),
        metadata: List<TaskMetadata> = emptyList(), reflected: Set<String> = emptySet()) = ProductionProjector.project(scope, tasks, metadata, commands, reflected, profiles)

    @Test fun `property null is no change while zero empty and exact decimals propagate to growing fields`() {
        val amount = BigDecimal("0.000000000000000000012300")
        val command = command("property", UpdatePigPropertyInput("pig", sex = PigletSex.FEMALE, teats = 0, weight = amount, remark = ""))
        val projected = project(listOf(command))
        val expected = original.copy(sex = "FEMALE", teatCount = 0, weight = amount, remark = "")
        assertEquals(expected, projected.previewProfiles["pig"]); assertEquals(original, projected.serverProfiles["pig"])
        val pig = (projected.previewTasks["n"] as GrowingTask).pigs.single()
        assertEquals(PigletSex.FEMALE, pig.sex); assertEquals(amount, pig.weight); assertEquals("TaskTag", pig.earTag)
        assertFalse(pig.result!!.breeder)
        assertEquals(original, project(listOf(command("null", UpdatePigPropertyInput("pig")))).previewProfiles["pig"])
        assertEquals(growing, project(listOf(command("null", UpdatePigPropertyInput("pig")))).previewTasks["n"])
        assertEquals(growing, projected.serverTasks["n"])
    }

    @Test fun `mark role uses updated sex and identity and upgrade ignores supplied sex and parents`() {
        val mark = command("mark", role(BreederRoleAction.MARK_AS_BREEDER, tag = "NewCase", notch = "", sex = PigletSex.FEMALE))
        val upgrade = command("upgrade", role(BreederRoleAction.UPGRADE_TO_PRODUCTION, tag = "Ignored", sex = PigletSex.MALE), 1)
        val marked = project(listOf(mark))
        assertEquals("BREEDER_GILT", marked.previewProfiles["pig"]!!.role)
        assertEquals("", marked.previewProfiles["pig"]!!.earNotch)
        assertTrue((marked.previewTasks["n"] as GrowingTask).pigs.single().result!!.breeder)
        val upgraded = project(listOf(upgrade, mark)); val pig = upgraded.previewProfiles["pig"]!!
        assertEquals("PRODUCTION_SOW", pig.role); assertEquals("FEMALE", pig.sex); assertEquals("NewCase", pig.earTag)
        assertFalse((upgraded.previewTasks["n"] as GrowingTask).pigs.single().result!!.breeder)
        assertNull(pig.firstEstrusAt); assertEquals("ExCase", pig.extendedEarTag)
        assertTrue(upgraded.effects.all { it.disposition == ProjectionDisposition.PREVIEW })
    }

    @Test fun `both cancel actions require breeder and inactive pigs cannot be revived by properties`() {
        val breeder = original.copy(role = "BREEDER_BOAR")
        listOf(BreederRoleAction.CANCEL_TO_PIGLET to "PIGLET", BreederRoleAction.CANCEL_TO_FATTENING to "FATTENING").forEach { (action, expected) ->
            assertEquals(expected, project(listOf(command("cancel", role(action))), listOf(breeder)).previewProfiles["pig"]!!.role)
            assertEquals(ProjectionDisposition.INVALID_CONTEXT, project(listOf(command("bad", role(action)))).effects.single().disposition)
        }
        for (lifecycle in listOf("DEAD", "MISSING", "REMOVED", "SOLD", "FUTURE_LIFECYCLE")) {
            val inactive = original.copy(lifecycle = lifecycle)
            val value = project(listOf(command("revive", UpdatePigPropertyInput("pig", lifecycle = PigLifecycle.ACTIVE))), listOf(inactive))
            assertEquals(inactive, value.previewProfiles["pig"]); assertEquals(ProjectionDisposition.INVALID_CONTEXT, value.effects.single().disposition)
        }
        val dead = project(listOf(command("dead", UpdatePigPropertyInput("pig", lifecycle = PigLifecycle.DEAD))))
        assertEquals("DEAD", dead.previewProfiles["pig"]!!.lifecycle)
        // Generic properties do not run ReportPigDeath's task synchronization.
        assertEquals(growing, dead.previewTasks["n"])
    }

    @Test fun `missing identity sex profile or grower result does not partially change a batch`() {
        val mark = role(BreederRoleAction.MARK_AS_BREEDER)
        for (value in listOf(original.copy(earTag = null, earNotch = ""), original.copy(sex = null), original.copy(sex = "FUTURE_SEX"), original.copy(role = "PRODUCTION_SOW"))) {
            val projected = project(listOf(command("bad", mark)), listOf(value))
            assertEquals(value, projected.previewProfiles["pig"]); assertEquals(growing, projected.previewTasks["n"])
            assertEquals(ProjectionDisposition.INVALID_CONTEXT, projected.effects.single().disposition)
        }
        val batch = mark.copy(pigs = mark.pigs + mark.pigs.single().copy(pigId = "missing"))
        val missing = project(listOf(command("batch", batch)))
        assertEquals(original, missing.previewProfiles["pig"]); assertEquals(growing, missing.previewTasks["n"])
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, missing.effects.single().disposition)
        val noResult = growing.copy(pigs = growing.pigs.map { it.copy(result = null) })
        val projected = project(listOf(command("mark", mark)), tasks = listOf(noResult))
        assertEquals(original, projected.previewProfiles["pig"]); assertEquals(noResult, projected.previewTasks["n"])
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, projected.effects.single().disposition)
    }

    @Test fun `duplicate role targets preserve backend last input and repeated aggregate rejection atomically`() {
        val first = BreederPigInput("pig", "First", null, PigletSex.MALE, null, null)
        val last = first.copy(earTag = "Last", sex = PigletSex.FEMALE)
        val input = UpdateBreederRoleInput(BreederRoleAction.MARK_AS_BREEDER, listOf(first, last), null)
        val projected = project(listOf(command("duplicate", input)))
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, projected.effects.single().disposition)
        assertEquals(original, projected.previewProfiles["pig"]); assertEquals(growing, projected.previewTasks["n"])
        assertEquals(listOf(first, last), (projected.effects.single().command.input as UpdateBreederRoleInput).pigs)
    }

    @Test fun `unknown result blocks same pig production while successful role requires read reflection`() {
        val property = command("property", UpdatePigPropertyInput("pig", remark = "new"), status = CommandStatus.UNCERTAIN)
        val estrus = EstrusTask("e", "farm", "batch", "RUNNING", listOf(EstrusPig("pig", null, null, false, null, null, null, null, location)))
        val check = command("check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, null), 1)
        val blocked = project(listOf(check, property), tasks = listOf(estrus))
        assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), blocked.effects.map { it.disposition })
        assertEquals(estrus, blocked.previewTasks["e"])
        val synced = property.copy(record = property.record.copy(status = CommandStatus.SYNCED))
        assertTrue(project(listOf(synced, check), tasks = listOf(estrus)).effects.all { it.disposition == ProjectionDisposition.WAITING_REFRESH })
        val reflected = project(listOf(synced, check), listOf(original.copy(remark = "new")), listOf(estrus), reflected = setOf("property"))
        assertEquals(EstrusStatus.IN_ESTRUS, (reflected.previewTasks["e"] as EstrusTask).pigs.single().checkStatus)
        assertEquals("new", reflected.previewProfiles["pig"]!!.remark)
    }

    @Test fun `role identity updates common metadata but completed growing aggregate keeps its breeder flag`() {
        val meta = TaskMetadata("n", "farm", ProductionTaskKind.NURSERY, ProductionTaskStatus.COMPLETED, null, null, null, null, null, null,
            TaskBatch("batch", "SN", null, null, emptyList()), null, listOf(TaskPigReference("pig", false, "MetaTag", "MetaNotch", "z.u.p", "mark")), emptyList(), emptyList(), emptyList())
        val task = growing.copy(status = "COMPLETED")
        val projected = project(listOf(command("mark", role(BreederRoleAction.MARK_AS_BREEDER, tag = "Case"))), tasks = listOf(task), metadata = listOf(meta))
        val pig = (projected.previewTasks["n"] as GrowingTask).pigs.single()
        assertEquals("Case", pig.earTag); assertEquals("TaskNotch", pig.earNotch); assertFalse(pig.result!!.breeder)
        assertEquals("Case", projected.previewMetadata["n"]!!.pigs.single().earTag)
        assertEquals("MetaNotch", projected.previewMetadata["n"]!!.pigs.single().earNotch)
        assertEquals("mark", projected.previewMetadata["n"]!!.pigs.single().mark)
        assertEquals(meta, projected.serverMetadata["n"])
    }

    @Test fun `scope validation and deterministic recomputation retain confirmed original profiles`() {
        val input = command("mark", role(BreederRoleAction.MARK_AS_BREEDER))
        val first = project(listOf(input)); assertEquals(first, project(listOf(input)))
        assertEquals(original, first.serverProfiles["pig"])
        assertTrue(project(listOf(input.copy(record = input.record.copy(scope = scope.copy(userId = "other"))))).effects.isEmpty())
        try { project(listOf(input), listOf(original.copy(farmId = "other"))); fail() } catch (_: IllegalArgumentException) { }
        try { project(listOf(input), listOf(original, original)); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun `changed identities propagate to all ten task pig relationships and leave unrelated pigs unchanged`() {
        val tasks: List<ProductionTaskSnapshot> = listOf(
            EstrusTask("e", "farm", "batch", "RUNNING", listOf(EstrusPig("pig", "TaskTag", "TaskNotch", false, null, null, null, null, location))),
            MatingTask("m", "farm", "batch", "RUNNING", listOf(MatingPig("pig", "TaskTag", "TaskNotch", false, null, emptyList(), location))),
            ReEstrusTask("r", "farm", "batch", "RUNNING", listOf(ReEstrusPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            PregnancyTask("p", "farm", "batch", "RUNNING", listOf(PregnancyPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            FarrowingTask("f", "farm", "batch", "RUNNING", listOf(FarrowingPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            PigletTask("i", "farm", "batch", "RUNNING", listOf(PigletPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            PostpartumTask("s", "farm", "batch", "RUNNING", listOf(PostpartumPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            LactationTask("l", "farm", "batch", "RUNNING", listOf(LactationPig("pig", "TaskTag", "TaskNotch", false, location, null))),
            growing, growing.copy(id = "g", stage = GrowingStage.FATTENING))
        val unrelated = tasks.first().let { (it as EstrusTask).copy(id = "other", pigs = it.pigs.map { pig -> pig.copy(id = "other") }) }
        val result = project(listOf(command("mark", role(BreederRoleAction.MARK_AS_BREEDER, tag = "NewCase"))), tasks = tasks + unrelated)
        assertEquals(tasks.associateBy { it.id } + (unrelated.id to unrelated), result.serverTasks)
        assertEquals(unrelated, result.previewTasks["other"])
        val identities = result.previewTasks.values.filter { it.id != "other" }.map { task -> when (task) {
            is EstrusTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is MatingTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is ReEstrusTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is PregnancyTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is FarrowingTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is PigletTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is PostpartumTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is LactationTask -> task.pigs.single().let { it.earTag to it.earNotch }
            is GrowingTask -> task.pigs.single().let { it.earTag to it.earNotch }
            else -> error("Unexpected task")
        } }
        assertEquals(List(10) { "NewCase" to "TaskNotch" }, identities)
        assertEquals(tasks.map { it.id }.toSet(), result.effects.single().affectedTaskIds)
    }

    @Test fun `unknown growing identity update and role changes block each other across task location groups`() {
        val growingInput = CheckGrowingInput("n", "z.u.p", GrowingStage.NURSERY, GrowingAssessment(1, 0, 1, 0, BigDecimal.ONE, 0),
            listOf(GrowingIdentityInput("pig", "z.u.p", true, false, "Case", null, PigletSex.FEMALE, BigDecimal.ONE)), null)
        val unknownGrowing = command("growing", growingInput, status = CommandStatus.UNCERTAIN)
        val after = project(listOf(unknownGrowing, command("role", role(BreederRoleAction.MARK_AS_BREEDER), 1)))
        assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), after.effects.map { it.disposition })
        assertEquals(original, after.previewProfiles["pig"])
        val unknownRole = command("role", role(BreederRoleAction.MARK_AS_BREEDER), status = CommandStatus.UNCERTAIN)
        val reverse = project(listOf(unknownRole, command("growing", growingInput, 1)))
        assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), reverse.effects.map { it.disposition })
        assertEquals(growing, reverse.previewTasks["n"])
    }

    @Test fun `explicit writes update stale task fields even when equal to the separately read profile`() {
        val task = growing.copy(pigs = growing.pigs.map { it.copy(sex = PigletSex.FEMALE) })
        val property = command("property", UpdatePigPropertyInput("pig", sex = PigletSex.MALE, weight = original.weight))
        val projected = project(listOf(property), tasks = listOf(task))
        val pig = (projected.previewTasks["n"] as GrowingTask).pigs.single()
        assertEquals(PigletSex.MALE, pig.sex); assertEquals(original.weight, pig.weight)
        assertEquals("TaskTag", pig.earTag); assertEquals("TaskNotch", pig.earNotch)
        val mark = command("mark", role(BreederRoleAction.MARK_AS_BREEDER, tag = original.earTag))
        val marked = (project(listOf(mark)).previewTasks["n"] as GrowingTask).pigs.single()
        assertEquals("OldTag", marked.earTag); assertEquals("TaskNotch", marked.earNotch)
    }
}
