package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

internal object DeathWorkspaceFixture {
    val scope = PigProfileFixture.scope
    val lease = PigProfileFixture.lease
    val child = PigProfileFixture.pig.copy(role = "PIGLET", semen = null, earTag = "Tag")
    val mother = child.copy(id = "mother", role = "PRODUCTION_SOW", health = null)
    val location = ProfileWorkspaceFixture.location
    val identity = IdentityPiglet(1, PigProfileFixture.pig.weight, "Tag", "Notch", PigletSex.FEMALE)
    val farrowing = FarrowingTask("f", scope.farmId, "batch", "RUNNING", listOf(FarrowingPig("mother", "Mother", null, false, location,
        FarrowingResult(FarrowingStatus.FARROWING, false, 0, 0, 2, null, null, null, null, null, null, listOf(identity), emptyList(), emptyList()))))
    val piglet = PigletTask("i", scope.farmId, "other-batch", "PLANNED", listOf(PigletPig("mother", "Mother", null, false, location,
        PigletResult(false, true, 0, 0, 2, 0, 0, 0, null, null, null, listOf(identity), emptyList()))))
    val sow = SowCheckResult(false, null, null, null, null, true, false, null, null)
    val lactation = LactationTask("l", scope.farmId, "third-batch", "SCHEDULED", listOf(LactationPig("mother", "Mother", null, false, location,
        LactationResult(sow, 0, 0, 2, null, null, listOf(LactationIdentity(identity, true)), emptyList()))))
    val tasks = listOf<ProductionTaskSnapshot>(farrowing, piglet, lactation)
    fun meta(task: ProductionTaskSnapshot) = TaskCatalogFixture.metadata.copy(id = task.id, kind = ProductionTaskKind.valueOf(task.type), status = ProductionTaskStatus.valueOf(task.status),
        batch = TaskCatalogFixture.metadata.batch.copy(id = task.batchId, tasks = emptyList()), matingMaterials = emptyList(), nurseryLocations = emptyList(),
        pigs = task.pigIds.map { TaskPigReference(it, false, "Mother", null, "Z-S-U-R-1", null) })
    val draft = PigDeathDraft(listOf(DeathPigDraft(DeathPigInput(pigId = "pig", motherId = "mother", earTag = ""), listOf("data:image/png;base64,fixture"))), "f")
    fun reader(cache: SnapshotCache, queue: CommandQueueStore, offline: Boolean = false, tasks: List<ProductionTaskSnapshot> = this.tasks,
        pageFailure: Exception? = null, pageCalled: (TaskCatalogRequest) -> Unit = {}, remoteCalled: () -> Unit = {}, changedTotal: Boolean = false): ProductionWorkspaceReads {
        val indexed = tasks.associateBy { it.id }; val metadata = tasks.map(::meta)
        val catalog = TaskCatalogReads(object : TaskCatalogRemote {
            override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? {
                remoteCalled(); if (offline) throw ApiFailure.Network(); return metadata.singleOrNull { it.id == id }
            }
            override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage {
                remoteCalled(); pageCalled(request); if (offline) throw ApiFailure.Network(); pageFailure?.let { throw it }
                val rows = metadata.filter(request::accepts).sortedBy { it.id }
                return TaskCatalogPage(request, rows.drop(request.offset).take(request.limit), rows.size + if (changedTotal && request.offset > 0) 1 else 0)
            }
        }, cache, {}) { 100 }
        fun <T : ProductionTaskSnapshot> read(codec: TaskCodec<T>) = ProductionTaskReads(TaskRemote { _: ScopeLease, id: String ->
            remoteCalled(); if (offline) throw ApiFailure.Network()
            @Suppress("UNCHECKED_CAST")
            indexed[id] as? T
        }, cache, codec, {}) { 101 }
        val f = read(ProductionTaskCodecs.farrowing); val i = read(ProductionTaskCodecs.piglet); val l = read(StageTaskCodecs.lactation)
        val profiles = PigProfileReads(ProfileWorkspaceFixture.remote(listOf(child, mother), if (offline) ApiFailure.Network() else null, { remoteCalled() }), cache, {}) { 102 }
        return ProductionWorkspaceReads(catalog, mapOf<ProductionTaskKind, ProductionDetailRead>(
            ProductionTaskKind.FARROWING to { lease, id, only -> f.task(lease, id, only) },
            ProductionTaskKind.PIGLET_PROCESSING to { lease, id, only -> i.task(lease, id, only) },
            ProductionTaskKind.LACTATION to { lease, id, only -> l.task(lease, id, only) }), queue, {}, profiles)
    }
}

class DeathWorkspaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = DeathWorkspaceFixture
    private fun databases(block: suspend (SqliteSnapshotCache, SqliteCommandQueue, String) -> Unit) = runBlocking {
        val name = "death-workspace-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { block(cache, it, name) } } }
        finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue") }
    }

    @Test fun actualPigAndMotherMembershipLoadsAcrossBatchesIncludingPlannedAndPreviewsEveryLitter() = databases { cache, queue, _ ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val pages = mutableListOf<TaskCatalogRequest>()
        val value = f.reader(cache, queue, pageCalled = pages::add).task(f.lease, "f")
        assertEquals(setOf("pig", "mother"), pages.single().pigIds)
        assertEquals(setOf(ProductionTaskStatus.PLANNED, ProductionTaskStatus.RUNNING, ProductionTaskStatus.SCHEDULED), pages.single().statuses)
        assertNull(pages.single().batchId); assertEquals(setOf("f", "i", "l"), value.details.keys)
        assertEquals(setOf("f", "i", "l"), value.deathContext.taskIdsByPig["mother"]); assertEquals(emptySet<String>(), value.deathContext.taskIdsByPig["pig"])
        assertEquals(setOf("f", "i", "l"), value.relatedMetadata.keys)
        assertEquals("DEAD", value.projection.previewProfiles["pig"]!!.lifecycle); assertEquals(f.mother, value.projection.previewProfiles["mother"])
        assertTrue((value.projection.previewTasks["f"] as FarrowingTask).pigs.single().result!!.identities.isEmpty())
        assertTrue((value.projection.previewTasks["i"] as PigletTask).pigs.single().result!!.identities.isEmpty())
        assertTrue((value.projection.previewTasks["l"] as LactationTask).pigs.single().result!!.identities.isEmpty())
        assertEquals(f.tasks.associateBy { it.id }, value.projection.serverTasks)
        assertEquals(f.child, value.projection.serverProfiles["pig"])
        assertTrue(report.record.groups.containsAll(setOf("pig:pig", "pig:mother")))
        assertTrue(value.projection.effects.last().deaths.single().pendingFields.containsAll(setOf(DeathPendingField.IMAGE_METADATA, DeathPendingField.MORTALITY_COUNTERS)))
    }

    @Test fun cachedMembershipMetadataProfilesAndPendingImagesReopenWithoutAnyRemoteReadOrFakeUrl() = databases { cache, queue, name ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val first = f.reader(cache, queue).task(f.lease, "f")
        SqliteSnapshotCache(context, name).use { reopened -> SqliteCommandQueue(context, "$name-queue").use { restored ->
            var calls = 0; val value = f.reader(reopened, restored, true, remoteCalled = { calls++ }).task(f.lease, "f", true)
            assertEquals(0, calls); assertEquals(first.projection, value.projection)
            assertTrue(value.relatedMetadata.values.all { it.source == DataSource.CACHE && it.fetchedAt == 100L })
            assertTrue(value.profiles.values.all { it.source == DataSource.CACHE && it.fetchedAt == 102L })
            assertNull(value.projection.effects.last().deaths.single().images.single().confirmedUrl)
            assertEquals(report.record.id, restored.enqueueDeath(f.scope, "report", f.draft, emptySet(), 99).record.id)
        } }
    }

    @Test fun missingMembershipPageCannotBeMistakenForNoOtherTasksOrApplyPartOfDeath() = databases { cache, queue, _ ->
        f.reader(cache, queue).task(f.lease, "f")
        queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val value = f.reader(cache, queue, pageFailure = ApiFailure.Network()).task(f.lease, "f")
        assertTrue(value.deathContext.taskIdsByPig.isEmpty()); assertTrue(value.unavailableRelatedTasks.any { it.id == "pig:mother" })
        assertEquals(f.farrowing, value.projection.previewTasks["f"])
        assertEquals(f.child, value.projection.previewProfiles["pig"])
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, value.projection.effects.last().disposition)
    }

    @Test fun fullMembershipPaginationIsRestoredAndChangingCountsDoNotProduceCompletenessProof() = databases { cache, queue, _ ->
        val tasks = (0..200).map { f.farrowing.copy(id = "f-${it.toString().padStart(3, '0')}") }
        queue.enqueueDeath(f.scope, "report", PigDeathDraft(listOf(DeathPigDraft(DeathPigInput(motherId = "mother"), emptyList())), "f-000"), emptySet(), 0)
        val pages = mutableListOf<TaskCatalogRequest>()
        val value = f.reader(cache, queue, tasks = tasks, pageCalled = pages::add).task(f.lease, "f-000")
        assertEquals(listOf(0, 200), pages.map { it.offset }); assertEquals(201, value.deathContext.taskIdsByPig["mother"]!!.size)
        assertTrue(value.projection.previewTasks.values.all { (it as FarrowingTask).pigs.single().result!!.noIdentityCount == 1 })
        val offline = f.reader(cache, queue, true, tasks).task(f.lease, "f-000", true)
        assertEquals(value.projection, offline.projection)
        val changing = f.reader(cache, queue, tasks = tasks, changedTotal = true).task(f.lease, "f-000")
        assertFalse(changing.deathContext.taskIdsByPig.containsKey("mother"))
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, changing.projection.effects.single().disposition)
        assertTrue(changing.projection.previewTasks.values.all { (it as FarrowingTask).pigs.single().result!!.noIdentityCount == 2 })
    }

    @Test fun imageUnknownAndReportReceiptKeepConfirmedSnapshotsAndSuccessfulImageNeedsNoPigReadProof() = databases { cache, queue, _ ->
        val report = queue.enqueueDeath(f.scope, "report", f.draft, emptySet(), 0)
        val reader = f.reader(cache, queue); reader.task(f.lease, "f")
        val ctx = QueueContext(f.scope, 1, true, true); val image = queue.claim(ctx, 1)!!
        queue.finish(image, CommandOutcome.Uncertain, 2)
        val unknown = reader.task(f.lease, "f", true)
        assertEquals(f.child, unknown.projection.previewProfiles["pig"]); assertEquals(f.farrowing, unknown.projection.previewTasks["f"])
        assertEquals(ProjectionDisposition.WAITING_DEPENDENCY, unknown.projection.effects.last().disposition)
        queue.finish(image, CommandOutcome.Success(DeathImageReceipt("key", "https://fixture.invalid/key.jpg").encode()), 3)
        val ready = reader.task(f.lease, "f", true)
        assertEquals(ProjectionDisposition.RESOURCE_READY, ready.projection.effects.first().disposition)
        assertEquals("https://fixture.invalid/key.jpg", ready.projection.effects.last().deaths.single().images.single().confirmedUrl)
        val attempted = queue.claim(ctx, 4)!!; assertEquals(report.record.id, attempted.record.id)
        queue.finish(attempted, CommandOutcome.Success("simulated-death-receipt"), 5)
        val waiting = reader.task(f.lease, "f", true)
        assertEquals(ProjectionDisposition.WAITING_REFRESH, waiting.projection.effects.last().disposition)
        assertEquals(f.child, waiting.projection.previewProfiles["pig"]); assertEquals(f.farrowing, waiting.projection.previewTasks["f"])
    }

    @Test fun legacyCatalogPagesRemainReadableWhileFilteredVersionTwoAndAllScopesStayDistinct() = databases { cache, _, _ ->
        val old = TaskCatalogFixture
        val legacy = JSONObject(TaskCatalogCodecs.encodePage(old.page)).apply { put("format", 1); getJSONObject("request").remove("pigIds") }.toString()
        cache.write(old.scope, "production_catalog_page", TaskCatalogCodecs.key(old.request), StoredSnapshot(legacy, 7))
        val reads = TaskCatalogReads(old.remote(ApiFailure.Network()), cache, {})
        assertEquals(old.page, reads.page(old.lease, old.request, true).value)
        val request = old.request.copy(pigIds = linkedSetOf("pig", "Z:Case"))
        assertNotEquals(TaskCatalogCodecs.key(old.request), TaskCatalogCodecs.key(request))
        assertEquals(TaskCatalogCodecs.key(request), TaskCatalogCodecs.key(request.copy(pigIds = linkedSetOf("Z:Case", "pig"))))
        try { reads.page(old.lease, request, true); fail() } catch (_: CacheMiss) { }
        val page = old.page.copy(request = request)
        val encoded = TaskCatalogCodecs.encodePage(page); assertEquals(2, JSONObject(encoded).getInt("format"))
        cache.write(old.scope, "production_catalog_page", TaskCatalogCodecs.key(request), StoredSnapshot(encoded, 8))
        assertEquals(page, reads.page(old.lease, request, true).value)
        for (scope in listOf(old.scope.copy(environment = "other"), old.scope.copy(oauthId = "other"), old.scope.copy(userId = "other"), old.scope.copy(farmId = "other"))) {
            try { reads.page(ScopeLease(scope, 2), request, true); fail() } catch (_: CacheMiss) { }
        }
        val bad = JSONObject(encoded).apply { getJSONObject("request").put("pigIds", org.json.JSONArray(listOf("pig", "pig"))) }.toString()
        cache.write(old.scope, "production_catalog_page", TaskCatalogCodecs.key(request), StoredSnapshot(bad, 8))
        try { reads.page(old.lease, request, true); fail() } catch (_: CacheInvalid) { }
    }
}
