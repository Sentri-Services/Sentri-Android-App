package com.sentri.nativeapp.production

import com.sentri.nativeapp.network.JsonValueAdapter
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class ProductionProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val at = "2026-10-09T09:00:00.123456789+08:00"
    private val weight = BigDecimal("123456789.12345678900")
    private val location = TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p")
    private val identity = IdentityPiglet(1, weight, "TagCase:A", "NotchCase", PigletSex.FEMALE)
    private val estrus = EstrusTask("e", "farm", "batch", "RUNNING", listOf(EstrusPig("pig", "TagCase", null, false, null, null, null, null, location)))
    private val mating = MatingTask("m", "farm", "batch", "RUNNING", emptyList())
    private val re = ReEstrusTask("r", "farm", "batch", "RUNNING", listOf(ReEstrusPig("pig", null, null, false, location, ReEstrusResult(null, null, false, null, at, "boar", null, null))))
    private val pregnancy = PregnancyTask("p", "farm", "batch", "RUNNING", listOf(PregnancyPig("pig", null, null, false, location, PregnancyResult(null, null, null, at, at, "boar", null, null))))
    private val farrowing = FarrowingTask("f", "farm", "batch", "RUNNING", listOf(FarrowingPig("pig", null, null, false, location,
        FarrowingResult(FarrowingStatus.FARROWING, false, 0, 0, 4, at, at, null, null, null, null, listOf(identity), listOf(PigletCount(FarrowingPigletType.HEALTHY, 5)), emptyList()))))
    private val piglet = PigletTask("i", "farm", "batch", "RUNNING", listOf(PigletPig("pig", null, null, false, location,
        PigletResult(false, true, 2, 1, 4, 3, 2, 1, at, weight, JsonValueAdapter.validate("{\"future\":0.1234567890123456789}"), listOf(identity), listOf(PigletTodo(PigletHandlingItem.INFO_SUBMISSION, 1, null, null), PigletTodo(PigletHandlingItem.IRON_SUPPLEMENT, 2, null, null))))))
    private val assessment = SowAssessment(ActivityLevel.LAME, BackfatThickness.THIN, BodyConditionScore.SCORE_2, FeedingSituation.REDUCED_INTAKE,
        MastitisSeverity.NO_MASSTITIS, MilkProductionCapacity.POOR, SecretionStatus.NORMAL, ProlapseType.RECTAL, null, false)
    private val sow = SowCheckResult(false, at, JsonValueAdapter.validate("{\"future\":0.1234567890123456789}"), "old", at, true, false, null, "operator")
    private val postpartum = PostpartumTask("s", "farm", "batch", "RUNNING", listOf(PostpartumPig("pig", null, null, false, location, sow)))
    private val lactation = LactationTask("l", "farm", "batch", "RUNNING", listOf(LactationPig("pig", null, null, false, location,
        LactationResult(sow, 3, 2, 4, null, weight, listOf(LactationIdentity(identity, true)), emptyList()))))
    private val nursery = GrowingTask("n", "farm", "batch", "RUNNING", GrowingStage.NURSERY,
        listOf(GrowingPig("pig", null, null, false, location, null, null, GrowingPigResult(false, false, "z.u.p"))), listOf(NurseryLocation("z.u.p", null, at, null, 1, BigDecimal.ZERO)))
    private val fattening = nursery.copy(id = "g", stage = GrowingStage.FATTENING, locations = emptyList())
    private fun command(id: String, input: CommandInput, at: Long = 0, deps: Set<String> = emptySet(), status: CommandStatus = CommandStatus.QUEUED,
        scope: DataScope = this.scope) = QueuedCommand(CommandRecord(id, "op-$id", scope, input.operationName, input.productionType, input.groupKeys, deps, at, status), input)
    private fun project(tasks: List<ProductionTaskSnapshot>, commands: List<QueuedCommand>, metadata: List<TaskMetadata> = emptyList(), reflected: Set<String> = emptySet()) = ProductionProjector.project(scope, tasks, metadata, commands, reflected)
    private inline fun <reified T> ProductionProjection.preview(id: String) = previewTasks[id] as T
    private fun metadata() = TaskMetadata("m", "farm", ProductionTaskKind.MATING, ProductionTaskStatus.RUNNING, at, null, at, null, null, null,
        TaskBatch("batch", "BatchCase", null, null, emptyList()), null, listOf(TaskPigReference("pig", false, "TagCase", null, null, "old")), emptyList(), emptyList(), emptyList())

    @Test fun `estrus preview gates next mating membership on actual or auto estrus and remains batch scoped`() {
        val check = command("check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, at), 100)
        val submit = command("submit", SubmitEstrusInput("e", listOf("pig"), at), 0, setOf("check"))
        val projected = project(listOf(estrus, mating), listOf(submit, check))
        assertTrue(projected.preview<EstrusTask>("e").pigs.single().isInEstrus!!)
        assertEquals("pig", projected.preview<MatingTask>("m").pigs.single().id)
        assertEquals(listOf("check", "submit"), projected.effects.map { it.command.record.id })
        assertEquals(estrus, projected.serverTasks["e"]); assertTrue(mating.pigs.isEmpty())
        val notEstrus = project(listOf(estrus, mating), listOf(check.copy(input = CheckEstrusInput("e", "pig", EstrusStatus.NOT_IN_ESTRUS, at)), submit))
        assertTrue(notEstrus.preview<MatingTask>("m").pigs.isEmpty()); assertNull(notEstrus.preview<EstrusTask>("e").pigs.single().isInEstrus)
        val auto = project(listOf(estrus, mating), listOf(command("auto", SubmitEstrusInput("e", listOf("pig"), null, true))))
        assertEquals(EstrusStatus.IN_ESTRUS, auto.preview<EstrusTask>("e").pigs.single().checkStatus); assertNull(auto.preview<MatingTask>("m").pigs.single().summary?.createdAt)
        val missing = project(listOf(estrus, mating.copy(batchId = "other")), listOf(submit, check))
        assertEquals(estrus.copy(pigs = listOf(estrus.pigs.single().copy(checkStatus = EstrusStatus.IN_ESTRUS, checkAt = at))), missing.previewTasks["e"])
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, missing.effects.last().disposition)
    }
    @Test fun `re estrus and pregnancy submissions apply typed results and cross task disable in dependency order`() {
        val check = command("check", CheckReEstrusInput("r", "pig", ReEstrusStatus.RE_ESTRUS, at, "unit"), 100)
        val submit = command("submit", SubmitReEstrusInput("r", listOf("pig"), at, "unit"), 0, setOf("check"))
        val result = project(listOf(re, pregnancy), listOf(submit, check))
        assertTrue(result.preview<ReEstrusTask>("r").pigs.single().disabled); assertTrue(result.preview<PregnancyTask>("p").pigs.single().disabled)
        assertTrue(result.preview<ReEstrusTask>("r").pigs.single().result!!.isInReEstrus)
        val yj = project(listOf(pregnancy), listOf(command("yj-check", CheckPregnancyInput("p", "pig", PregnancyStatus.NOT_PREGNANT, at, 3, "unit"), 1),
            command("yj-submit", SubmitPregnancyInput("p", listOf("pig"), at, "unit"), 2, setOf("yj-check"))))
        val pig = yj.preview<PregnancyTask>("p").pigs.single(); assertTrue(pig.disabled); assertEquals(false, pig.result!!.isPregnant)
        assertEquals(at, pig.result.reCheckAt); assertEquals(at, pig.result.submittedAt)
        val unsure = project(listOf(pregnancy), listOf(command("check", CheckPregnancyInput("p", "pig", PregnancyStatus.NOT_SURE, null, 0, "")), command("submit", SubmitPregnancyInput("p", listOf("pig"), null, ""), 1, setOf("check"))))
        assertNull(unsure.preview<PregnancyTask>("p").pigs.single().result!!.isPregnant)
    }
    @Test fun `mating attempts use local operation identities without fabricating server execution records`() {
        val task = mating.copy(pigs = listOf(MatingPig("pig", null, null, false, MatingSummary(2, at, at, at, "boar", at, "boar"), listOf(MatingRecord(at, 2, "boar", null, null, null)), location)))
        val result = project(listOf(task), listOf(command("boar", MateWithBoarInput("m", "pig", "boar"), 10), command("semen", MateWithSemenInput("m", "pig", "semen"), 11)))
        assertEquals(task, result.previewTasks["m"])
        val rows = result.effects.flatMap { it.records }.filterIsInstance<LocalProductionRecord.Mating>()
        assertEquals(listOf("boar", "semen"), rows.map { it.commandId }); assertEquals("semen", rows.last().semenId); assertEquals(11L, rows.last().requestedAtMillis)
    }
    @Test fun `farrowing cumulative snapshots only grow stock and retain server records separately`() {
        val inputs = listOf(command("lower", SubmitFarrowingInput("f", "pig", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 3)), at)),
            command("higher", SubmitFarrowingInput("f", "pig", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 7), PigletCount(FarrowingPigletType.STILLBORN, 1)), null), 1))
        val result = project(listOf(farrowing), inputs); val pig = result.preview<FarrowingTask>("f").pigs.single().result!!
        assertEquals(6, pig.noIdentityCount); assertEquals(7, pig.counts.first { it.type == FarrowingPigletType.HEALTHY }.count)
        assertTrue(pig.records.isEmpty()); assertNull(pig.lastAt); assertEquals(at, pig.firstAt)
        val rows = result.effects.flatMap { it.records }.filterIsInstance<LocalProductionRecord.Farrowing>()
        assertEquals(0, rows.first().addedAlive); assertEquals(2, rows.last().addedAlive); assertEquals(1, rows.last().addedDead)
        val empty = project(listOf(farrowing), listOf(command("empty", SubmitFarrowingInput("f", "pig", false, null, null))))
        assertEquals(FarrowingStatus.COMPLETED, empty.preview<FarrowingTask>("f").pigs.single().result!!.status)
    }
    @Test fun `piglet information replaces identities preserves decimal and handles null differently from weight`() {
        val info = command("info", SubmitPigletInfoInput("i", "pig", 2, 0, emptyList(), null, null, null, at))
        val items = command("items", SubmitPigletItemsInput("i", "pig", listOf(PigletHandlingItem.IRON_SUPPLEMENT), at), 1)
        val weighted = command("weight", SubmitPigletWeightInput("i", "pig", BigDecimal.ZERO, null), 2)
        val result = project(listOf(piglet), listOf(info, items, weighted)); val pig = result.preview<PigletTask>("i").pigs.single().result!!
        assertTrue(pig.identities.isEmpty()); assertEquals(2, pig.noIdentityCount); assertEquals(BigDecimal.ZERO, pig.totalWeight)
        assertEquals(listOf(at, at), pig.todo.map { it.completedAt }); assertEquals(piglet.pigs.single().result!!.items, pig.items)
        val nullInfo = project(listOf(piglet), listOf(info.copy(input = (info.input as SubmitPigletInfoInput).copy(identities = null))))
        assertTrue(nullInfo.preview<PigletTask>("i").pigs.single().result!!.identities.isEmpty()); assertEquals(weight, nullInfo.preview<PigletTask>("i").pigs.single().result!!.totalWeight)
    }
    @Test fun `postpartum preserves all prolapse input and lactation absent assessment keeps old check`() {
        val pp = command("pp", SubmitPostpartumInput("s", "pig", assessment, null, null, true, "ReasonCase"))
        val lc = command("lc", SubmitLactationInput("l", "pig", null, null, "", false, null, 2, 1, null, emptyList(), listOf(PigletCount(FarrowingPigletType.HEALTHY, 3))), 1)
        val result = project(listOf(postpartum, lactation), listOf(pp, lc)); val post = result.preview<PostpartumTask>("s").pigs.single().result!!
        assertTrue(post.assessment!!.encoded.contains("\"prolapse_type\":\"RECTAL\"")); assertTrue(post.assessment.encoded.contains("\"prolapse_recurred\":null")); assertTrue(post.assessment.encoded.contains("\"prolapse_resolved\":false"))
        assertTrue(post.obsoleted); assertNull(post.checkedAt)
        val lact = result.preview<LactationTask>("l").pigs.single().result!!
        assertEquals(sow.assessment, lact.sow.assessment); assertEquals(at, lact.sow.checkedAt); assertEquals(weight, lact.totalWeight); assertEquals(3, lact.noIdentityCount)
        assertTrue(lact.identities.isEmpty()); assertEquals("", lact.sow.description)
    }
    @Test fun `growing aggregate remains atomic and nursery does not fabricate fattening locations`() {
        val assessment = GrowingAssessment(3, 2, 1, 1, weight, 1)
        val ns = command("ns", CheckGrowingInput("n", "z.u.p", GrowingStage.NURSERY, assessment, listOf(GrowingIdentityInput("pig", "z.u.p", true, false, "Case", null, PigletSex.FEMALE, weight)), at))
        val ft = command("ft", CheckGrowingInput("g", "z.u.p", GrowingStage.FATTENING, assessment, emptyList(), null), 1)
        val result = project(listOf(nursery, fattening), listOf(ns, ft))
        assertEquals(6, result.preview<GrowingTask>("n").locations.single().totalCount); assertEquals(weight, result.preview<GrowingTask>("n").pigs.single().weight)
        assertTrue(result.preview<GrowingTask>("g").locations.isEmpty())
        val missingInput = (ns.input as CheckGrowingInput).copy(identities = listOf(GrowingIdentityInput("missing", "z.u.p", true, false, null, null, null, null)))
        val missing = project(listOf(nursery), listOf(command("missing", missingInput)))
        assertEquals(nursery, missing.previewTasks["n"]); assertEquals(ProjectionDisposition.MISSING_CONTEXT, missing.effects.single().disposition)
    }
    @Test fun `foster transfers both mothers and cross task counts atomically with exact identity weight`() {
        val adopt = piglet.copy(pigs = listOf(piglet.pigs.single().copy(id = "adopt", result = piglet.pigs.single().result!!.copy(identities = emptyList(), noIdentityCount = 1))))
        val transfer = command("foster", FosterPigletInput("f", "pig", "adopt", FosterStage.FARROWING, listOf("TagCase:A"), listOf("NotchCase"), 2, null))
        val result = project(listOf(farrowing, adopt), listOf(transfer))
        val source = result.preview<FarrowingTask>("f").pigs.single().result!!; val target = result.preview<PigletTask>("i").pigs.single().result!!
        assertTrue(source.identities.isEmpty()); assertEquals(2, source.noIdentityCount); assertEquals(3, source.fosterCount)
        assertEquals(3, target.noIdentityCount); assertEquals(5, target.adoptCount); assertEquals(weight, target.identities.single().birthWeight)
        assertEquals(setOf("f", "i"), result.effects.single().affectedTaskIds)
        val missing = project(listOf(farrowing), listOf(transfer)); assertEquals(farrowing, missing.previewTasks["f"]); assertEquals(ProjectionDisposition.MISSING_CONTEXT, missing.effects.single().disposition)
        val same = farrowing.copy(pigs = farrowing.pigs + farrowing.pigs.single().copy(id = "adopt", result = farrowing.pigs.single().result!!.copy(identities = emptyList())))
        val paired = project(listOf(same), listOf(transfer)).preview<FarrowingTask>("f")
        assertEquals(2, paired.pigs.first().result!!.noIdentityCount); assertEquals(6, paired.pigs.last().result!!.noIdentityCount)
    }
    @Test fun `lactation foster preserves breeder and missing identity selectors do not partially transfer`() {
        val task = lactation.copy(pigs = lactation.pigs + lactation.pigs.single().copy(id = "adopt", result = lactation.pigs.single().result!!.copy(identities = emptyList())))
        val input = FosterPigletInput("l", "pig", "adopt", FosterStage.LACTATION, listOf("TagCase:A"), null, 1, null)
        val result = project(listOf(task), listOf(command("transfer", input))).preview<LactationTask>("l")
        assertTrue(result.pigs.last().result!!.identities.single().breeder); assertEquals(3, result.pigs.first().result!!.noIdentityCount)
        val bad = project(listOf(task), listOf(command("missing", input.copy(earTags = listOf("other")))))
        assertEquals(task, bad.previewTasks["l"]); assertEquals(ProjectionDisposition.MISSING_CONTEXT, bad.effects.single().disposition)
    }
    @Test fun `unknown rejected dependencies and cycles retain intents without changing server values`() {
        val check = command("check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, at), status = CommandStatus.UNCERTAIN)
        val submit = command("submit", SubmitEstrusInput("e", listOf("pig"), at), 1, setOf("check"))
        val result = project(listOf(estrus, mating), listOf(check, submit)); assertEquals(estrus, result.previewTasks["e"])
        assertEquals(listOf(ProjectionDisposition.WAITING_CONFIRMATION, ProjectionDisposition.WAITING_DEPENDENCY), result.effects.map { it.disposition })
        val failed = project(listOf(estrus, mating), listOf(check.copy(record = check.record.copy(status = CommandStatus.FAILED)), submit))
        assertEquals(ProjectionDisposition.REJECTED, failed.effects.first().disposition)
        val cycle = project(listOf(estrus, mating), listOf(check.copy(record = check.record.copy(status = CommandStatus.QUEUED, dependsOn = setOf("submit"))), submit))
        assertTrue(cycle.effects.all { it.disposition == ProjectionDisposition.CYCLIC_DEPENDENCY }); assertEquals(estrus, cycle.previewTasks["e"])
        val cancelled = project(listOf(estrus), listOf(check.copy(record = check.record.copy(status = CommandStatus.CANCELLED))))
        assertTrue(cancelled.effects.isEmpty())
    }
    @Test fun `successful receipts require read reconciliation before adding more stock`() {
        val first = command("first", SubmitFarrowingInput("f", "pig", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 7)), at), status = CommandStatus.SYNCED)
        val next = command("next", SubmitFarrowingInput("f", "pig", false, listOf(PigletCount(FarrowingPigletType.HEALTHY, 8)), at), 1, setOf("first"))
        val blocked = project(listOf(farrowing), listOf(first, next)); assertEquals(farrowing, blocked.previewTasks["f"]); assertTrue(blocked.effects.all { it.disposition == ProjectionDisposition.WAITING_REFRESH })
        val refreshed = farrowing.copy(pigs = listOf(farrowing.pigs.single().copy(result = farrowing.pigs.single().result!!.copy(noIdentityCount = 6, counts = listOf(PigletCount(FarrowingPigletType.HEALTHY, 7))))))
        assertEquals(7, project(listOf(refreshed), listOf(first, next), reflected = setOf("first")).preview<FarrowingTask>("f").pigs.single().result!!.noIdentityCount)
        try { project(listOf(farrowing), listOf(first, next), reflected = setOf("next")); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `mark and completion retain existing status and cannot borrow a different batch`() {
        val meta = metadata(); val mark = command("mark", MarkTaskPigInput("m", "pig", "batch", null))
        val end = command("end", CompleteTaskInput("m", "batch", ProductionTaskKind.MATING, null), 1, setOf("mark"))
        val result = project(emptyList(), listOf(mark, end), listOf(meta)); assertNull(result.previewMetadata["m"]!!.pigs.single().mark)
        assertEquals(ProductionTaskStatus.RUNNING, result.previewMetadata["m"]!!.status); assertEquals(ProjectionDisposition.COMPLETION_REQUESTED, result.effects.last().disposition)
        val invalid = project(emptyList(), listOf(mark.copy(input = MarkTaskPigInput("m", "pig", "other", "new"))), listOf(meta))
        assertEquals(meta, invalid.previewMetadata["m"]); assertEquals(ProjectionDisposition.INVALID_CONTEXT, invalid.effects.single().disposition)
    }
    @Test fun `scope isolation restart style recomputation and integer overflow keep snapshots unchanged`() {
        val input = command("check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, at))
        assertTrue(project(listOf(estrus), listOf(input.copy(record = input.record.copy(scope = scope.copy(userId = "other"))))).effects.isEmpty())
        val first = project(listOf(estrus), listOf(input)); assertEquals(first, project(listOf(estrus), listOf(input))); assertEquals(estrus, first.serverTasks["e"])
        val overflow = command("overflow", CheckGrowingInput("n", "z.u.p", GrowingStage.NURSERY, GrowingAssessment(Int.MAX_VALUE, 1, 1, 1, weight, 1), emptyList(), null))
        val result = project(listOf(nursery), listOf(overflow)); assertEquals(nursery, result.previewTasks["n"]); assertEquals(ProjectionDisposition.INVALID_CONTEXT, result.effects.single().disposition)
    }
}
