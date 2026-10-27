package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValue
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigDeathProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val profile = PigProfile("pig", "farm", "Tag", "Notch", null, "PRODUCTION_SOW", "FEMALE", "ACTIVE", "SELFBRED", "old",
        null, null, null, BigDecimal("12.12345678900"), null, null, null, null, null, null, null, null, null, null, "batch", null, null, null, null, null, null)
    private val location = TaskPigLocation("old", "unit", "unit.code", "old")
    private val identity = IdentityPiglet(1, BigDecimal("0.00000012300"), "Tag", "Notch", PigletSex.FEMALE)
    private val sow = SowCheckResult(false, null, null, null, null, true, false, null, null)
    private val farrow = FarrowingTask("f", "farm", "batch", "PLANNED", listOf(FarrowingPig("pig", "Tag", "Notch", false, location,
        FarrowingResult(FarrowingStatus.FARROWING, false, 0, 0, 0, null, null, null, null, null, null, emptyList(), emptyList(), emptyList()))))
    private val piglet = PigletTask("i", "farm", "batch", "RUNNING", listOf(PigletPig("pig", "Tag", "Notch", false, location,
        PigletResult(false, true, 0, 0, 0, 0, 0, 0, null, null, null, emptyList(), emptyList()))))
    private val lactation = LactationTask("l", "farm", "batch", "RUNNING", listOf(LactationPig("pig", "Tag", "Notch", false, location,
        LactationResult(sow, 0, 0, 0, null, null, emptyList(), emptyList()))))
    private val growing = GrowingTask("n", "farm", "batch", "SCHEDULED", GrowingStage.NURSERY,
        listOf(GrowingPig("pig", "Tag", "Notch", false, location, PigletSex.FEMALE, profile.weight, GrowingPigResult(true, true, "old"))), emptyList())
    private fun command(id: String, input: CommandInput, status: CommandStatus = CommandStatus.QUEUED, deps: Set<String> = emptySet(), receipt: String? = null, at: Long = 0) =
        QueuedCommand(CommandRecord(id, "op-$id", scope, input.operationName, input.productionType, input.groupKeys, deps, at, status), input, receipt)
    private fun context(tasks: List<ProductionTaskSnapshot>, ids: Set<String> = setOf("pig"), absent: Set<String> = emptySet()) =
        PigDeathContext(ids.associateWith { pig -> tasks.filter { it.status in setOf("PLANNED", "SCHEDULED", "RUNNING") && pig in it.pigIds }.map { it.id }.toSet() }, absent)
    private fun project(commands: List<QueuedCommand>, tasks: List<ProductionTaskSnapshot> = emptyList(), profiles: List<PigProfile> = listOf(profile),
        context: PigDeathContext = context(tasks)) = ProductionProjector.project(scope, tasks, emptyList(), commands, profiles = profiles, deathContext = context)
    private fun report(vararg pigs: DeathPigInput) = ReportPigDeathInput(pigs.toList())

    @Test fun `actual death disables all ten task types with planned membership and explicit location without inventing timestamps`() {
        val tasks = listOf<ProductionTaskSnapshot>(
            EstrusTask("e", "farm", "batch", "RUNNING", listOf(EstrusPig("pig", "Tag", null, false, null, null, null, null, location))),
            MatingTask("m", "farm", "batch", "RUNNING", listOf(MatingPig("pig", null, null, false, null, emptyList(), location))),
            ReEstrusTask("r", "farm", "batch", "RUNNING", listOf(ReEstrusPig("pig", null, null, false, location, null))),
            PregnancyTask("p", "farm", "batch", "RUNNING", listOf(PregnancyPig("pig", null, null, false, location, null))),
            farrow, piglet, PostpartumTask("s", "farm", "batch", "RUNNING", listOf(PostpartumPig("pig", null, null, false, location, sow))),
            lactation, growing, growing.copy(id = "g", stage = GrowingStage.FATTENING))
        val result = project(listOf(command("death", report(DeathPigInput(pigId = "pig", locationCode = "new")))), tasks)
        assertEquals(ProjectionDisposition.PREVIEW, result.effects.single().disposition)
        assertEquals(tasks.map { it.id }.toSet(), result.effects.single().affectedTaskIds)
        assertEquals(profile, result.serverProfiles["pig"]); assertEquals(tasks.associateBy { it.id }, result.serverTasks)
        assertEquals("DEAD", result.previewProfiles["pig"]!!.lifecycle); assertEquals("new", result.previewProfiles["pig"]!!.locationCode)
        assertEquals(JsonValue("[]"), result.previewProfiles["pig"]!!.deathImages)
        assertEquals(setOf(DeathPendingField.SERVER_TIME), result.effects.single().deaths.single().pendingFields)
        for (task in result.previewTasks.values) assertTrue(when (task) {
            is EstrusTask -> task.pigs.single().disabled && task.pigs.single().location!!.locationCode == "new"
            is MatingTask -> task.pigs.single().disabled
            is ReEstrusTask -> task.pigs.single().disabled
            is PregnancyTask -> task.pigs.single().disabled
            is FarrowingTask -> task.pigs.single().disabled && task.pigs.single().result!!.disabled
            is PigletTask -> task.pigs.single().disabled && task.pigs.single().result!!.disabled
            is PostpartumTask -> task.pigs.single().disabled && task.pigs.single().result!!.disabled
            is LactationTask -> task.pigs.single().disabled && task.pigs.single().result!!.sow.disabled
            is GrowingTask -> task.pigs.single().disabled
            else -> false
        })
    }

    @Test fun `anonymous maternal reports adjust every concurrent litter and remove only first matching identity`() {
        val f = farrow.copy(pigs = farrow.pigs.map { it.copy(id = "mother", result = it.result!!.copy(noIdentityCount = 1, identities = listOf(identity, identity.copy(sequence = 2)))) })
        val i = piglet.copy(pigs = piglet.pigs.map { it.copy(id = "mother", result = it.result!!.copy(noIdentityCount = 1, identities = listOf(identity, identity.copy(sequence = 2)))) })
        val l = lactation.copy(pigs = lactation.pigs.map { it.copy(id = "mother", result = it.result!!.copy(noIdentityCount = 1, identities = listOf(LactationIdentity(identity, true), LactationIdentity(identity.copy(sequence = 2), false)))) })
        val tasks = listOf(f, i, l); val mother = profile.copy(id = "mother")
        val input = report(DeathPigInput(motherId = "mother", earTag = "Tag"), DeathPigInput(motherId = "mother"), DeathPigInput(motherId = "mother"))
        val result = project(listOf(command("death", input)), tasks, listOf(mother), context(tasks, setOf("mother")))
        assertEquals(mother, result.previewProfiles["mother"]); assertEquals(setOf("mother"), input.targetPigIds)
        val fs = (result.previewTasks["f"] as FarrowingTask).pigs.single().result!!
        val isnap = (result.previewTasks["i"] as PigletTask).pigs.single().result!!
        val ls = (result.previewTasks["l"] as LactationTask).pigs.single().result!!
        assertEquals(0, fs.noIdentityCount); assertEquals(0, isnap.noIdentityCount); assertEquals(0, ls.noIdentityCount)
        assertEquals(2, fs.identities.single().sequence); assertEquals(identity.birthWeight, fs.identities.single().birthWeight)
        assertEquals(1, isnap.identities.size); assertFalse(ls.identities.single().breeder)
        assertEquals(3, result.effects.single().deaths.size)
        result.effects.single().deaths.forEach { assertNull(it.input.pigId); assertTrue(it.pendingFields.containsAll(setOf(DeathPendingField.SERVER_ID, DeathPendingField.MORTALITY_COUNTERS))) }
    }

    @Test fun `mother reference is a modified litter target and actual pig identity fallback leaves mother alive`() {
        val task = farrow.copy(pigs = farrow.pigs.map { it.copy(id = "mother", result = it.result!!.copy(identities = listOf(identity))) })
        val input = report(DeathPigInput(pigId = "pig", motherId = "mother", earTag = ""))
        val result = project(listOf(command("death", input)), listOf(task), listOf(profile, profile.copy(id = "mother")), context(listOf(task), setOf("pig", "mother")))
        assertEquals(setOf("pig", "mother"), input.targetPigIds); assertEquals("pig", input.pigs.single().targetId)
        assertEquals("DEAD", result.previewProfiles["pig"]!!.lifecycle); assertEquals("ACTIVE", result.previewProfiles["mother"]!!.lifecycle)
        assertTrue((result.previewTasks["f"] as FarrowingTask).pigs.single().result!!.identities.isEmpty())
        assertEquals("", result.effects.single().deaths.single().input.earTag)
    }

    @Test fun `sow preflight runs before any row and absent export status cannot be guessed`() {
        val task = lactation.copy(pigs = lactation.pigs.map { it.copy(result = it.result!!.copy(noIdentityCount = 1)) })
        val input = report(DeathPigInput(motherId = "pig"), DeathPigInput(pigId = "pig"))
        val result = project(listOf(command("death", input)), listOf(task))
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, result.effects.single().disposition)
        assertEquals(task, result.previewTasks["l"]); assertEquals(profile, result.previewProfiles["pig"])
        assertEquals(setOf(DeathPendingField.SOW_EXPORT_STATE), result.effects.single().deaths.single().pendingFields)
        assertEquals(1, result.effects.single().deaths.single().row)
    }

    @Test fun `confirmed absence skips a real pig but unloaded context cannot masquerade as absence`() {
        val input = command("death", report(DeathPigInput(pigId = "missing")))
        val absent = project(listOf(input), profiles = emptyList(), context = PigDeathContext(absentPigIds = setOf("missing")))
        assertEquals(ProjectionDisposition.PREVIEW, absent.effects.single().disposition); assertTrue(absent.effects.single().deaths.isEmpty())
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, project(listOf(input), profiles = emptyList()).effects.single().disposition)
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, project(listOf(command("death", report(DeathPigInput(pigId = "pig")))), context = PigDeathContext()).effects.single().disposition)
    }

    @Test fun `already dead and repeated real pig reports do not duplicate effects or replace earlier images`() {
        val input = report(DeathPigInput(pigId = "pig", locationCode = "first"), DeathPigInput(pigId = "pig", locationCode = "ignored"))
        val result = project(listOf(command("death", input)))
        assertEquals(1, result.effects.single().deaths.size); assertEquals("first", result.previewProfiles["pig"]!!.locationCode)
        val dead = profile.copy(lifecycle = "DEAD", deathImages = JsonValue("[\"old\"]"))
        val noop = project(listOf(command("death", input)), profiles = listOf(dead))
        assertEquals(dead, noop.previewProfiles["pig"]); assertTrue(noop.effects.single().deaths.isEmpty())
    }

    @Test fun `invalid later target or already disabled stage rolls back all private changes`() {
        val input = report(DeathPigInput(pigId = "pig"), DeathPigInput(pigId = "other"))
        val result = project(listOf(command("death", input)), profiles = listOf(profile, profile.copy(id = "other", lifecycle = "SOLD")), context = context(emptyList(), setOf("pig", "other")))
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, result.effects.single().disposition); assertEquals(profile, result.previewProfiles["pig"])
        val disabled = farrow.copy(pigs = farrow.pigs.map { it.copy(result = it.result!!.copy(disabled = true)) })
        val value = project(listOf(command("death", report(DeathPigInput(pigId = "pig")))), listOf(disabled))
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, value.effects.single().disposition); assertEquals(disabled, value.previewTasks["f"])
        assertEquals(profile, value.previewProfiles["pig"])
    }

    @Test fun `multiple actual task matches are ambiguous but all maternal task copies are adjusted`() {
        val tasks = listOf(growing, growing.copy(id = "another"))
        val value = project(listOf(command("death", report(DeathPigInput(pigId = "pig")))), tasks)
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, value.effects.single().disposition); assertEquals(tasks.associateBy { it.id }, value.previewTasks)
        val moms = listOf(farrow, farrow.copy(id = "f2")).map { it.copy(pigs = it.pigs.map { pig -> pig.copy(result = pig.result!!.copy(noIdentityCount = 1)) }) }
        val maternal = project(listOf(command("death", report(DeathPigInput(motherId = "pig")))), moms)
        assertTrue(maternal.previewTasks.values.all { (it as FarrowingTask).pigs.single().result!!.noIdentityCount == 0 })
    }

    private fun upload(status: CommandStatus = CommandStatus.QUEUED, receipt: String? = null) = command("image",
        UploadPigDeathImageInput("op-report", 0, 0, "data:image/png;base64,fixture", "pig"), status, receipt = receipt)
    private fun imageReport(deps: Set<String> = setOf("image")) = command("report",
        report(DeathPigInput(pigId = "pig", images = listOf(DeathImage.Uploaded("op-image")))), deps = deps, at = 1)

    @Test fun `confirmed image receipt is ready without pig read reflection and keeps image order in local intent`() {
        val image = upload(CommandStatus.SYNCED, DeathImageReceipt("key", "https://fixture.invalid/key.png").encode())
        val value = project(listOf(imageReport(), image))
        assertEquals(listOf(ProjectionDisposition.RESOURCE_READY, ProjectionDisposition.PREVIEW), value.effects.map { it.disposition })
        assertEquals("https://fixture.invalid/key.png", value.effects.last().deaths.single().images.single().confirmedUrl)
        assertNull(value.previewProfiles["pig"]!!.deathImages)
        assertTrue(DeathPendingField.IMAGE_METADATA in value.effects.last().deaths.single().pendingFields)
    }

    @Test fun `queued image can preview unresolved intent while unknown failed or cancelled image blocks death`() {
        val preview = project(listOf(upload(), imageReport()))
        assertEquals("DEAD", preview.previewProfiles["pig"]!!.lifecycle)
        assertNull(preview.effects.last().deaths.single().images.single().confirmedUrl)
        for (status in listOf(CommandStatus.UNCERTAIN, CommandStatus.IN_FLIGHT, CommandStatus.FAILED, CommandStatus.CANCELLED)) {
            val result = project(listOf(upload(status), imageReport()))
            assertEquals(profile, result.previewProfiles["pig"])
            assertEquals(ProjectionDisposition.WAITING_DEPENDENCY, result.effects.last().disposition)
        }
    }

    @Test fun `foreign attachment missing dependency and malformed confirmed receipt cannot become business preview`() {
        val noDependency = project(listOf(upload(), imageReport(emptySet())))
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, noDependency.effects.last().disposition)
        val foreign = upload().let { it.copy(input = (it.input as UploadPigDeathImageInput).copy(reportOperationId = "foreign")) }
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, project(listOf(foreign, imageReport())).effects.last().disposition)
        val malformed = project(listOf(upload(CommandStatus.SYNCED, "invalid"), imageReport()))
        assertEquals(ProjectionDisposition.INVALID_CONTEXT, malformed.effects.first().disposition); assertEquals(profile, malformed.previewProfiles["pig"])
    }

    @Test fun `death preview remains scoped deterministic and never invents server identity for standalone anonymous rows`() {
        val command = command("ghost", report(DeathPigInput(earTag = "Case", images = listOf(DeathImage.Remote("")))))
        val first = project(listOf(command)); assertEquals(first, project(listOf(command)))
        assertEquals(profile, first.previewProfiles["pig"]); assertEquals(setOf("pig"), first.previewProfiles.keys)
        assertNull(first.effects.single().deaths.single().input.pigId)
        assertEquals(setOf(DeathPendingField.SERVER_ID, DeathPendingField.SERVER_TIME, DeathPendingField.IMAGE_METADATA), first.effects.single().deaths.single().pendingFields)
        assertTrue(project(listOf(command.copy(record = command.record.copy(scope = scope.copy(userId = "other"))))).effects.isEmpty())
    }

    @Test fun `unknown actual pig death also blocks later operations on its mothers litter`() {
        val death = command("death", report(DeathPigInput(pigId = "pig", motherId = "mother")), CommandStatus.UNCERTAIN)
        val property = command("mother", UpdatePigPropertyInput("mother", remark = "new"), at = 1)
        val mother = profile.copy(id = "mother")
        val result = project(listOf(death, property), profiles = listOf(profile, mother))
        assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), result.effects.map { it.disposition })
        assertEquals(mother, result.previewProfiles["mother"])
    }

    @Test fun `disabled mother litter is unchanged and needs no mortality counter preview`() {
        val task = farrow.copy(pigs = farrow.pigs.map { it.copy(result = it.result!!.copy(disabled = true, noIdentityCount = 3, identities = listOf(identity))) })
        val result = project(listOf(command("death", report(DeathPigInput(motherId = "pig")))), listOf(task))
        assertEquals(ProjectionDisposition.PREVIEW, result.effects.single().disposition); assertEquals(task, result.previewTasks["f"])
        assertEquals(setOf(DeathPendingField.SERVER_ID, DeathPendingField.SERVER_TIME), result.effects.single().deaths.single().pendingFields)
    }
}
