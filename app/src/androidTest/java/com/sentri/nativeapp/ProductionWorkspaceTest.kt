package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object ProjectionDeviceFixture {
    val f = TaskCatalogFixture
    val estrus = EstrusTask("e", f.scope.farmId, "batch", "RUNNING", listOf(EstrusPig("pig", "TagCase:A", null, false, null, null, null, null, TaskPigLocation("z.u.p", "unit", "z.u", "z.u.p"))))
    val mating = MatingTask("m", f.scope.farmId, "batch", "RUNNING", emptyList())
    val metadata = f.metadata.copy(id = "e", kind = ProductionTaskKind.ESTRUS, matingMaterials = emptyList(), nurseryLocations = emptyList(),
        batch = f.metadata.batch.copy(tasks = listOf(RelatedProductionTask("e", ProductionTaskKind.ESTRUS, ProductionTaskStatus.RUNNING), RelatedProductionTask("m", ProductionTaskKind.MATING, ProductionTaskStatus.RUNNING))))
    fun reader(cache: SnapshotCache, queue: CommandQueueStore, offline: Boolean, missingMating: Boolean = false,
        check: (ScopeLease) -> Unit = {}): ProductionWorkspaceReads {
        val catalog = TaskCatalogReads(object : TaskCatalogRemote {
            override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage = error("Unused")
            override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? { if (offline) throw ApiFailure.Network(); return if (id == "e") metadata else null }
        }, cache, check) { 100 }
        val e = ProductionTaskReads(TaskRemote { _: ScopeLease, _: String -> if (offline) throw ApiFailure.Network() else estrus }, cache, ProductionTaskCodecs.estrus, check) { 101 }
        val m = ProductionTaskReads(TaskRemote { _: ScopeLease, _: String -> if (offline || missingMating) throw ApiFailure.Network() else mating }, cache, ProductionTaskCodecs.mating, check) { 102 }
        return ProductionWorkspaceReads(catalog, mapOf<ProductionTaskKind, ProductionDetailRead>(
            ProductionTaskKind.ESTRUS to { lease, id, only -> e.task(lease, id, only) }, ProductionTaskKind.MATING to { lease, id, only -> m.task(lease, id, only) },
        ), queue, check)
    }
}

class ProductionWorkspaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = ProjectionDeviceFixture
    private fun databases(block: suspend (SqliteSnapshotCache, SqliteCommandQueue, String) -> Unit) = runBlocking {
        val name = "projection-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { block(cache, it, name) } } }
        finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue") }
    }
    @Test fun persistedQueueRebuildsCrossTaskPreviewAndKeepsConfirmedCacheUnchanged() = databases { cache, queue, name ->
        f.reader(cache, queue, false).task(f.f.lease, "e")
        val check = queue.enqueue(f.f.scope, "check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, f.f.at), emptySet(), 0)
        queue.enqueue(f.f.scope, "submit", SubmitEstrusInput("e", listOf("pig"), f.f.at), emptySet(), 1)
        SqliteSnapshotCache(context, name).use { restored -> SqliteCommandQueue(context, "$name-queue").use { reopened ->
            val value = f.reader(restored, reopened, true).task(f.f.lease, "e", true)
            assertEquals(DataSource.CACHE, value.metadata.source); assertEquals(100L, value.metadata.fetchedAt)
            assertEquals(f.estrus, value.projection.serverTasks["e"]); assertEquals(f.mating, value.projection.serverTasks["m"])
            assertTrue((value.projection.previewTasks["e"] as EstrusTask).pigs.single().isInEstrus!!)
            assertEquals("pig", (value.projection.previewTasks["m"] as MatingTask).pigs.single().id)
            assertEquals(2, value.projection.effects.size); assertEquals(check.record.id, value.projection.effects.first().command.record.id)
            val raw = ProductionTaskReads<EstrusTask>(TaskRemote { _, _ -> throw ApiFailure.Network() }, restored, ProductionTaskCodecs.estrus, {})
            assertEquals(f.estrus, raw.task(f.f.lease, "e", true).value)
            assertEquals(f.metadata, value.metadata.value); assertTrue(value.unavailableRelatedTasks.isEmpty())
        } }
    }
    @Test fun localCancellationAndUnknownRecoveryAreVisibleWithoutRewritingSnapshots() = databases { cache, queue, _ ->
        val reader = f.reader(cache, queue, false); reader.task(f.f.lease, "e")
        val mark = queue.enqueue(f.f.scope, "mark", MarkTaskPigInput("e", "pig", "batch", "local"), emptySet(), 0)
        assertEquals("local", reader.task(f.f.lease, "e", true).projection.previewMetadata["e"]!!.pigs.single().mark)
        queue.cancel(f.f.scope, mark.record.id, 1)
        assertEquals("RemarkCase", reader.task(f.f.lease, "e", true).projection.previewMetadata["e"]!!.pigs.single().mark)
        queue.enqueue(f.f.scope, "check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, f.f.at), emptySet(), 2)
        queue.claim(QueueContext(f.f.scope, 1, true, true), 3); assertEquals(1, queue.recoverInterrupted(f.f.scope, 4))
        val value = reader.task(f.f.lease, "e", true)
        assertEquals(f.estrus, value.projection.previewTasks["e"]); assertEquals(ProjectionDisposition.WAITING_CONFIRMATION, value.projection.effects.single().disposition)
    }
    @Test fun unavailableSiblingStopsAtomicCrossTaskProjectionAndReportsItsExactReadFailure() = databases { cache, queue, _ ->
        val reader = f.reader(cache, queue, false, missingMating = true); reader.task(f.f.lease, "e")
        queue.enqueue(f.f.scope, "auto", SubmitEstrusInput("e", listOf("pig"), f.f.at, true), emptySet(), 0)
        val value = f.reader(cache, queue, true).task(f.f.lease, "e", true)
        assertEquals("m", value.unavailableRelatedTasks.single().id); assertTrue(value.unavailableRelatedTasks.single().failure is CacheMiss)
        assertEquals(f.estrus, value.projection.previewTasks["e"]); assertEquals(ProjectionDisposition.MISSING_CONTEXT, value.projection.effects.single().disposition)
    }
    @Test fun anotherScopeCannotBorrowSnapshotsCommandsOrReconciliationProof() = databases { cache, queue, _ ->
        val reader = f.reader(cache, queue, false); reader.task(f.f.lease, "e")
        queue.enqueue(f.f.scope.copy(userId = "other"), "foreign", MarkTaskPigInput("e", "pig", "batch", "foreign"), emptySet(), 0)
        assertTrue(reader.task(f.f.lease, "e", true).projection.effects.isEmpty())
        try { reader.task(ScopeLease(f.f.scope.copy(userId = "other"), 2), "e", true); fail() } catch (_: CacheMiss) { }
        try { reader.task(f.f.lease, "e", true, setOf("foreign")); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun contextChangesDuringAssemblyCannotPublishTheOldWorkspace() = databases { cache, queue, _ ->
        f.reader(cache, queue, false).task(f.f.lease, "e")
        var current = f.f.lease; var checks = 0
        val reader = f.reader(cache, queue, true, check = { lease -> if (++checks == 8) current = ScopeLease(f.f.scope.copy(farmId = "other"), 2); if (lease != current) throw RequestContextChanged() })
        try { reader.task(f.f.lease, "e", true); fail() } catch (_: RequestContextChanged) { }
        assertEquals(f.estrus, f.reader(cache, queue, true).task(f.f.lease, "e", true).projection.serverTasks["e"])
    }
    @Test fun successReceiptWaitsForExplicitReadEvidenceAndKnownFailureRemovesOnlyItsPreview() = databases { cache, queue, _ ->
        val reader = f.reader(cache, queue, false); reader.task(f.f.lease, "e")
        val mark = queue.enqueue(f.f.scope, "mark", MarkTaskPigInput("e", "pig", "batch", "local"), emptySet(), 0)
        val attempted = requireNotNull(queue.claim(QueueContext(f.f.scope, 1, true, true), 1)); queue.finish(attempted, CommandOutcome.Success("simulated-receipt"), 2)
        val waiting = reader.task(f.f.lease, "e", true); assertEquals(ProjectionDisposition.WAITING_REFRESH, waiting.projection.effects.single().disposition)
        assertEquals("RemarkCase", waiting.projection.previewMetadata["e"]!!.pigs.single().mark)
        assertTrue(reader.task(f.f.lease, "e", true, setOf(mark.record.id)).projection.effects.isEmpty())
        val check = queue.enqueue(f.f.scope, "check", CheckEstrusInput("e", "pig", EstrusStatus.IN_ESTRUS, f.f.at), emptySet(), 3)
        queue.finish(requireNotNull(queue.claim(QueueContext(f.f.scope, 1, true, true), 4)), CommandOutcome.Rejected("SIMULATED"), 5)
        val failed = reader.task(f.f.lease, "e", true, setOf(mark.record.id)); assertEquals(check.record.id, failed.projection.effects.single().command.record.id)
        assertEquals(ProjectionDisposition.REJECTED, failed.projection.effects.single().disposition); assertEquals(f.estrus, failed.projection.previewTasks["e"])
    }
}
