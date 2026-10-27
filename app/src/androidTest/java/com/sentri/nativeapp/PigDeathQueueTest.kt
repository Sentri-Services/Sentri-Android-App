package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import com.sentri.nativeapp.graphql.type.ProductionReportPigDeathCommandStrawberryInput
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object PigDeathFixture {
    val scope = TaskCatalogFixture.scope
    val draft = PigDeathDraft(listOf(DeathPigDraft(DeathPigInput("A", earTag = "", reason = "reason"),
        listOf("data:image/png;base64,AA==", "https://fixture.invalid/remote", "data:image/jpeg;base64,AQ==")),
        DeathPigDraft(DeathPigInput(motherId = "mother", locationCode = "pen"), emptyList()), DeathPigDraft(DeathPigInput(), emptyList())))
    val context = QueueContext(scope, 1, true, true)
    fun receipt(index: Int) = DeathImageReceipt("key$index", "https://fixture.invalid/upload$index").encode()
}
class PigDeathQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = PigDeathFixture
    private fun database(block: (SqliteCommandQueue, String) -> Unit) {
        val name = "pig-death-" + UUID.randomUUID()
        try { SqliteCommandQueue(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    private fun rejected(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: Exception) { } }
    @Test fun wholeDraftIsAtomicIdempotentAndStrictlyRestored() = database { queue, name ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        assertEquals(report, queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 1))
        val rows = queue.list(f.scope); assertEquals(3, rows.size)
        assertEquals(rows.filter { it.input is UploadPigDeathImageInput }.map { it.record.id }.toSet(), report.record.dependsOn)
        assertEquals(setOf("A", "mother"), report.input.targetPigIds)
        assertTrue(queue.list(f.scope.copy(farmId = "other")).isEmpty())
        SqliteCommandQueue(context, name).use { assertEquals(rows, it.list(f.scope)) }
        val bad = f.draft.copy(pigs = f.draft.pigs.mapIndexed { i, row -> if (i == 0) row.copy(imageSources = row.imageSources + "data:new") else row })
        rejected { queue.enqueueDeath(f.scope, "report", bad, emptySet(), 2) }
        assertEquals(rows, queue.list(f.scope)) // The new third upload also rolled back.
        val encoded = CommandPayloadCodec.encode(report.input)
        assertEquals(report.input, CommandPayloadCodec.decode(2, encoded))
        rejected { CommandPayloadCodec.decode(2, JSONObject(encoded).apply { getJSONObject("input").put("extra", true) }.toString()) }
        rejected { CommandPayloadCodec.decode(2, encoded.replace("\"uploaded\"", "\"future\"")) }
        val upload = rows.first { it.input is UploadPigDeathImageInput }.input
        rejected { CommandPayloadCodec.decode(2, CommandPayloadCodec.encode(upload).replace("\"pigIndex\":0", "\"pigIndex\":0.5")) }
    }
    @Test fun imagePayloadLargerThanCursorWindowRestoresWithoutTruncation() = database { queue, name ->
        val source = "data:image/png;base64," + "A".repeat(2_700_000)
        val draft = PigDeathDraft(listOf(DeathPigDraft(DeathPigInput("large", reason = "🐷"), listOf(source))))
        queue.enqueueDeath(f.scope, "large-report", draft, emptySet(), 0)
        SqliteCommandQueue(context, name).use { reopened ->
            val upload = reopened.list(f.scope).single { it.input is UploadPigDeathImageInput }.input as UploadPigDeathImageInput
            assertEquals(source.length, upload.dataUrl.length); assertTrue(source == upload.dataUrl)
            val report = reopened.list(f.scope).single { it.input is ReportPigDeathInput }.input as ReportPigDeathInput
            assertEquals("🐷", report.pigs.single().reason)
        }
    }
    @Test fun unknownUploadBlocksReportAndRetryReusesConfirmedReceipts() = database { queue, _ -> runBlocking {
        queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val first = requireNotNull(queue.claim(f.context, 1)); assertTrue(first.input is UploadPigDeathImageInput)
        queue.finish(first, CommandOutcome.Success(f.receipt(0)), 2)
        val second = requireNotNull(queue.claim(f.context, 3)); assertTrue(second.input is UploadPigDeathImageInput)
        queue.finish(second, CommandOutcome.Uncertain, 4); assertNull(queue.claim(f.context, 5))
        rejected { queue.finish(second, CommandOutcome.Success("untyped-success"), 6) }
        assertEquals(CommandStatus.UNCERTAIN, queue.list(f.scope).single { it.record.id == second.record.id }.record.status)
        queue.finish(second, CommandOutcome.Success(f.receipt(1)), 7)
        var uploads = 0; val reports = mutableListOf<ProductionReportPigDeathCommandStrawberryInput>()
        val transport = PigDeathTransport(queue, object : PigDeathGateway {
            override suspend fun upload(input: UploadPigDeathImageInput): CommandOutcome { uploads++; error("Already uploaded") }
            override suspend fun report(input: ProductionReportPigDeathCommandStrawberryInput): CommandOutcome {
                reports += input
                return if (reports.size == 1) CommandOutcome.Rejected("SIMULATED") else CommandOutcome.Success("simulated-report")
            }
        }, { f.context })
        var now = 8L; val dispatcher = CommandDispatcher(queue, transport, { f.context }) { now }
        assertTrue(dispatcher.drainOne())
        val report = queue.list(f.scope).single { it.input is ReportPigDeathInput }
        queue.retryAfterConfirmation(f.scope, report.record.id, now, true); now = 10_000
        assertTrue(dispatcher.drainOne()); assertFalse(dispatcher.drainOne())
        assertEquals(0, uploads); assertEquals(2, reports.size); assertEquals(reports[0], reports[1])
        assertEquals(listOf("https://fixture.invalid/upload0", "https://fixture.invalid/remote", "https://fixture.invalid/upload1"),
            reports[0].pigs.getOrNull()!!.first().death_images.getOrNull())
    } }
    @Test fun foreignReferencesAndSlotsCannotBeBorrowed() = database { queue, _ ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val input = report.input as ReportPigDeathInput
        rejected { queue.enqueue(f.scope, "other-report", input, emptySet(), 1) }
        rejected { queue.enqueue(f.scope.copy(userId = "other"), "report", input, emptySet(), 1) }
        val swapped = input.copy(pigs = input.pigs.mapIndexed { i, pig -> if (i == 0) pig.copy(images = pig.images.reversed()) else pig })
        rejected { queue.enqueue(f.scope, "report-swapped", swapped, emptySet(), 1) }
        assertEquals(3, queue.list(f.scope).size)
        queue.enqueue(f.scope, "bad-slot", UploadPigDeathImageInput("slot-report", 1, 0, "data:x", "A"), emptySet(), 2)
        val badSlot = ReportPigDeathInput(listOf(DeathPigInput("A", images = listOf(DeathImage.Uploaded("bad-slot")))))
        rejected { queue.enqueue(f.scope, "slot-report", badSlot, emptySet(), 3) }
    }
    @Test fun cancellingOneImageNeverSatisfiesReportAndParentCancelIsAtomic() = database { queue, _ ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val first = requireNotNull(queue.claim(f.context, 1))
        rejected { queue.cancel(f.scope, report.record.id, 2) }
        assertEquals(CommandStatus.QUEUED, queue.list(f.scope).single { it.record.id == report.record.id }.record.status)
        queue.finish(first, CommandOutcome.Uncertain, 3)
        rejected { queue.cancel(f.scope, report.record.id, 4) }
        queue.finish(first, CommandOutcome.Rejected("SIMULATED"), 5)
        queue.cancel(f.scope, first.record.id, 6)
        val second = requireNotNull(queue.claim(f.context, 7)); queue.finish(second, CommandOutcome.Success(f.receipt(1)), 8)
        assertNull(queue.claim(f.context, 9))
        queue.cancel(f.scope, report.record.id, 10)
        assertEquals(CommandStatus.SYNCED, queue.list(f.scope).single { it.record.id == second.record.id }.record.status)
        val another = queue.enqueueDeath(f.scope, "another", f.draft, emptySet(), 11)
        queue.cancel(f.scope, another.record.id, 12)
        assertEquals(3, queue.list(f.scope).count { it.record.status == CommandStatus.CANCELLED &&
            (it.record.operationId == "another" || (it.input as? UploadPigDeathImageInput)?.reportOperationId == "another") })
    }
    @Test fun completionBarrierAndContextGateIncludeUploadsWithoutSendingOnAnotherScope() = database { queue, _ -> runBlocking {
        queue.enqueueDeath(f.scope, "report", f.draft.copy(taskId = "task"), emptySet(), 0)
        val finish = queue.enqueue(f.scope, "finish", CompleteTaskInput("task", "batch", ProductionTaskKind.ESTRUS, null), emptySet(), 1)
        assertEquals(3, finish.record.dependsOn.size)
        val first = requireNotNull(queue.claim(f.context, 2))
        val transport = PigDeathTransport(queue, object : PigDeathGateway {
            override suspend fun upload(input: UploadPigDeathImageInput): CommandOutcome = error("Must not send")
            override suspend fun report(input: ProductionReportPigDeathCommandStrawberryInput): CommandOutcome = error("Must not send")
        }, { f.context.copy(scope = f.scope.copy(farmId = "other")) })
        assertTrue(transport.send(first) is CommandOutcome.NotSent)
    } }
}
