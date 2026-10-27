package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

internal object ProfileWorkspaceFixture {
    val scope = PigProfileFixture.scope
    val lease = PigProfileFixture.lease
    val pig = PigProfileFixture.pig.copy(role = "PIGLET", semen = null)
    val location = TaskPigLocation("Z-S-U-R-1", "unit", "Z-S-U", "Z-S-U-R-1")
    val growing = GrowingTask("n", scope.farmId, "batch", "RUNNING", GrowingStage.NURSERY,
        listOf(GrowingPig("pig", "TaskTag", "TaskNotch", false, location, PigletSex.MALE, BigDecimal.ONE, GrowingPigResult(true, false, "Z-S-U-R-1"))), emptyList())
    val metadata = TaskCatalogFixture.metadata.copy(id = "n", kind = ProductionTaskKind.NURSERY, matingMaterials = emptyList(), nurseryLocations = emptyList(),
        batch = TaskCatalogFixture.metadata.batch.copy(tasks = emptyList()))
    fun remote(pigs: List<PigProfile> = listOf(pig), failure: Exception? = null, called: (PigProfileRequest) -> Unit = {}) = object : PigProfileRemote {
        override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
            called(request); failure?.let { throw it }
            return PigProfilePage(request, pigs.filter { request.ids == null || it.id in request.ids }, null)
        }
    }
    fun reader(cache: SnapshotCache, queue: CommandQueueStore, offline: Boolean = false,
        remote: PigProfileRemote = remote(), check: (ScopeLease) -> Unit = {}, task: GrowingTask = growing, meta: TaskMetadata = metadata): ProductionWorkspaceReads {
        val catalog = TaskCatalogReads(object : TaskCatalogRemote {
            override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage = error("Unused")
            override suspend fun metadata(lease: ScopeLease, id: String): TaskMetadata? {
                if (offline) throw ApiFailure.Network(); return meta
            }
        }, cache, check) { 100 }
        val details = ProductionTaskReads(TaskRemote { _: ScopeLease, _: String -> if (offline) throw ApiFailure.Network() else task }, cache, StageTaskCodecs.nursery, check) { 101 }
        val profiles = PigProfileReads(remote, cache, check) { 102 }
        return ProductionWorkspaceReads(catalog, mapOf(ProductionTaskKind.NURSERY to { lease, id, only -> details.task(lease, id, only) }), queue, check, profiles)
    }
    fun role(pigs: List<BreederPigInput> = listOf(BreederPigInput("pig", "NewCase", "", PigletSex.FEMALE, "mother", "father"))) =
        UpdateBreederRoleInput(BreederRoleAction.MARK_AS_BREEDER, pigs, null)
}

class ProfileWorkspaceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = ProfileWorkspaceFixture
    private fun databases(block: suspend (SqliteSnapshotCache, SqliteCommandQueue, String) -> Unit) = runBlocking {
        val name = "profile-workspace-${UUID.randomUUID()}"
        try { SqliteSnapshotCache(context, name).use { cache -> SqliteCommandQueue(context, "$name-queue").use { block(cache, it, name) } } }
        finally { context.deleteDatabase(name); context.deleteDatabase("$name-queue") }
    }

    @Test fun restoredProfileAndRoleIntentRebuildTaskAndMetadataWithoutRewritingAnySnapshot() = databases { cache, queue, name ->
        val initial = f.reader(cache, queue).task(f.lease, "n")
        assertEquals(f.pig, initial.profiles["pig"]!!.value); assertEquals(102L, initial.profiles["pig"]!!.fetchedAt)
        queue.enqueue(f.scope, "mark", f.role(), emptySet(), 0)
        SqliteSnapshotCache(context, name).use { restored -> SqliteCommandQueue(context, "$name-queue").use { reopened ->
            var remoteReads = 0
            val value = f.reader(restored, reopened, true, f.remote(called = { remoteReads++ })).task(f.lease, "n", true)
            assertEquals(0, remoteReads); assertEquals(DataSource.CACHE, value.profiles["pig"]!!.source)
            assertEquals(102L, value.profiles["pig"]!!.fetchedAt)
            val profile = value.projection.previewProfiles["pig"]!!
            assertEquals("BREEDER_GILT", profile.role); assertEquals("NewCase", profile.earTag); assertEquals("", profile.earNotch)
            assertEquals(f.pig.health, profile.health); assertEquals(f.pig.weight, profile.weight); assertEquals(f.pig.location, profile.location)
            val pig = (value.projection.previewTasks["n"] as GrowingTask).pigs.single()
            assertEquals("NewCase", pig.earTag); assertEquals(PigletSex.FEMALE, pig.sex); assertTrue(pig.result!!.breeder)
            assertEquals("NewCase", value.projection.previewMetadata["n"]!!.pigs.single().earTag)
            assertEquals(f.growing, value.projection.serverTasks["n"]); assertEquals(f.metadata, value.metadata.value)
            assertEquals(f.pig, value.projection.serverProfiles["pig"])
            val request = PigProfileRequest(filter = PigFilter(pageSize = 100), ids = setOf("pig"), includeCount = false)
            assertEquals(f.pig, PigProfileReads(f.remote(), restored, {}).page(f.lease, request, true).value.pigs.single())
        } }
    }

    @Test fun exactIdsAreChunkedStablyAndKnownAbsentProfilesRetainPageProvenance() = databases { cache, queue, _ ->
        val ids = (0..200).map { "pig-${it.toString().padStart(3, '0')}" }
        val task = f.growing.copy(pigs = ids.reversed().map { f.growing.pigs.single().copy(id = it) })
        val meta = f.metadata.copy(pigs = ids.map { f.metadata.pigs.single().copy(id = it) })
        val pigs = ids.dropLast(1).map { f.pig.copy(id = it, health = null) }
        val requests = mutableListOf<PigProfileRequest>()
        val value = f.reader(cache, queue, remote = f.remote(pigs, called = requests::add), task = task, meta = meta).task(f.lease, "n")
        assertEquals(listOf(100, 100, 1), requests.map { it.ids!!.size })
        assertEquals(ids, requests.flatMap { it.ids!! }); assertTrue(requests.all { it.filter.pageSize == 100 && !it.includeCount && it.offset == 0 })
        assertEquals(201, value.profiles.size); assertNull(value.profiles[ids.last()]!!.value)
        assertEquals(DataSource.NETWORK, value.profiles[ids.last()]!!.source); assertEquals(102L, value.profiles[ids.last()]!!.fetchedAt)
        assertTrue(value.unavailableProfiles.isEmpty())
        val again = f.reader(cache, queue, true, f.remote(failure = ApiFailure.Network()), task = task, meta = meta).task(f.lease, "n", true)
        assertEquals(value.projection, again.projection); assertTrue(again.profiles.values.all { it.source == DataSource.CACHE })
    }

    @Test fun atomicRoleTargetsAndExplicitPrerequisitesExpandContextWithoutLoadingUnrelatedQueueOrParents() = databases { cache, queue, _ ->
        val prerequisite = queue.enqueue(f.scope, "prerequisite", UpdatePigPropertyInput("prerequisite", remark = "new"), emptySet(), 0)
        val input = f.role(f.role().pigs + f.role().pigs.single().copy(pigId = "other"))
        queue.enqueue(f.scope, "mark", input, setOf(prerequisite.record.id), 1)
        queue.enqueue(f.scope, "unrelated", UpdatePigPropertyInput("unrelated", remark = "ignore"), emptySet(), 2)
        val cancelled = queue.enqueue(f.scope, "cancelled", f.role(f.role().pigs + f.role().pigs.single().copy(pigId = "cancelled")), emptySet(), 3)
        queue.cancel(f.scope, cancelled.record.id, 4)
        val requested = mutableListOf<PigProfileRequest>()
        val pigs = listOf(f.pig, f.pig.copy(id = "other", health = null), f.pig.copy(id = "prerequisite", health = null))
        val value = f.reader(cache, queue, remote = f.remote(pigs, called = requested::add)).task(f.lease, "n")
        assertEquals(setOf("pig", "other", "prerequisite"), requested.single().ids)
        assertEquals("BREEDER_GILT", value.projection.previewProfiles["other"]!!.role)
        assertEquals("new", value.projection.previewProfiles["prerequisite"]!!.remark)
        assertFalse(value.profiles.containsKey("mother")); assertFalse(value.profiles.containsKey("father")); assertFalse(value.profiles.containsKey("unrelated"))
    }

    @Test fun unavailableAndAbsentProfilesRemainDistinctAndMissingAtomicContextCannotApplyPartially() = databases { cache, queue, _ ->
        queue.enqueue(f.scope, "mark", f.role(), emptySet(), 0)
        val absent = f.reader(cache, queue, remote = f.remote(emptyList())).task(f.lease, "n")
        assertTrue(absent.profiles.containsKey("pig")); assertNull(absent.profiles["pig"]!!.value); assertTrue(absent.unavailableProfiles.isEmpty())
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, absent.projection.effects.single().disposition)
        cache.clear(f.scope)
        val unavailable = f.reader(cache, queue, remote = f.remote(failure = ApiFailure.Network())).task(f.lease, "n")
        assertFalse(unavailable.profiles.containsKey("pig")); assertTrue(unavailable.unavailableProfiles.single().failure is ApiFailure.Network)
        assertEquals(f.growing, unavailable.projection.previewTasks["n"])
        assertEquals(ProjectionDisposition.MISSING_CONTEXT, unavailable.projection.effects.single().disposition)
    }

    @Test fun onlyNetworkOrUnavailableAuthFallsBackAndProtocolBusinessCancellationOrInvalidCachePropagates() = databases { cache, queue, _ ->
        f.reader(cache, queue).task(f.lease, "n")
        for (failure in listOf(ApiFailure.Network(), AuthUnavailable())) {
            val fallback = f.reader(cache, queue, remote = f.remote(failure = failure)).task(f.lease, "n")
            assertEquals(DataSource.CACHE, fallback.profiles["pig"]!!.source)
            assertSame(failure, fallback.profiles["pig"]!!.refreshFailure)
        }
        for (failure in listOf(ApiFailure.Protocol(), ApiFailure.Business("SIMULATED"), CancellationException("simulated"))) {
            try { f.reader(cache, queue, remote = f.remote(failure = failure)).task(f.lease, "n"); fail() }
            catch (actual: Exception) { assertSame(failure, actual) }
        }
        val request = PigProfileRequest(filter = PigFilter(pageSize = 100), ids = setOf("pig"), includeCount = false)
        cache.write(f.scope, "pig_profile_page", PigProfileCodec.key(request), StoredSnapshot("{}", 1))
        try { f.reader(cache, queue, true).task(f.lease, "n", true); fail() } catch (_: CacheInvalid) { }
    }

    @Test fun scopeSwitchDuringProfileReadCannotPublishAnOldWorkspaceOrBorrowAnotherScopesCache() = databases { cache, queue, _ ->
        f.reader(cache, queue).task(f.lease, "n")
        var current = f.lease
        val reader = f.reader(cache, queue, remote = f.remote(called = { current = ScopeLease(f.scope.copy(farmId = "other"), 2) }),
            check = { if (it != current) throw RequestContextChanged() })
        try { reader.task(f.lease, "n"); fail() } catch (_: RequestContextChanged) { }
        for (scope in listOf(f.scope.copy(environment = "other"), f.scope.copy(oauthId = "other"), f.scope.copy(userId = "other"), f.scope.copy(farmId = "other"))) {
            try { f.reader(cache, queue, true).task(ScopeLease(scope, 2), "n", true); fail() } catch (_: CacheMiss) { }
        }
        assertEquals(f.pig, f.reader(cache, queue, true).task(f.lease, "n", true).projection.serverProfiles["pig"])
    }

    @Test fun matingWorkspaceLoadsBoarReferencesFromMaterialsRecordsAndPendingInput() = databases { cache, queue, _ ->
        fun material(id: Int, boar: String) = MatingMaterial(id, boar, null, null, null, null, null, null)
        val task = MatingTask("m", f.scope.farmId, "batch", "RUNNING", listOf(MatingPig("pig", null, null, false, null,
            listOf(MatingRecord(null, 1, "raw-reference", null, material(1, "record-boar"), material(2, "semen-boar"))), f.location)))
        val meta = f.metadata.copy(id = "m", kind = ProductionTaskKind.MATING, matingMaterials = listOf(material(3, "material-boar")))
        val catalog = TaskCatalogReads(object : TaskCatalogRemote {
            override suspend fun page(lease: ScopeLease, request: TaskCatalogRequest): TaskCatalogPage = error("Unused")
            override suspend fun metadata(lease: ScopeLease, id: String) = meta
        }, cache, {})
        val tasks = ProductionTaskReads(TaskRemote { _: ScopeLease, _: String -> task }, cache, ProductionTaskCodecs.mating, {})
        queue.enqueue(f.scope, "mate", MateWithBoarInput("m", "pig", "pending-boar"), emptySet(), 0)
        val ids = setOf("pig", "record-boar", "semen-boar", "material-boar", "pending-boar")
        val requests = mutableListOf<PigProfileRequest>()
        val profiles = PigProfileReads(f.remote(ids.map { f.pig.copy(id = it, health = null) }, called = requests::add), cache, {})
        val reader = ProductionWorkspaceReads(catalog, mapOf(ProductionTaskKind.MATING to { lease, id, only -> tasks.task(lease, id, only) }), queue, {}, profiles)
        val result = reader.task(f.lease, "m")
        assertEquals(ids, requests.single().ids); assertEquals(ids, result.profiles.keys)
        assertFalse(result.profiles.containsKey("raw-reference"))
        assertEquals("pending-boar", (result.projection.effects.single().records.single() as LocalProductionRecord.Mating).boarId)
        assertEquals(task, result.projection.serverTasks["m"])
    }

    @Test fun persistedGrowingAndRoleCommandsSharePigOrderingWhileOtherPigsRemainIndependent() = databases { _, queue, name ->
        val input = CheckGrowingInput("n", "Z-S-U-R-1", GrowingStage.NURSERY, GrowingAssessment(1, 0, 0, 1, BigDecimal.ONE, 0),
            listOf(GrowingIdentityInput("pig", "Z-S-U-R-1", true, false, "Case", null, PigletSex.MALE, BigDecimal.ONE)), null)
        val first = queue.enqueue(f.scope, "growing", input, emptySet(), 0)
        queue.enqueue(f.scope, "role", f.role(), emptySet(), 1)
        val other = queue.enqueue(f.scope, "other", input.copy(locationCode = "Z-S-U-R-2", identities = listOf(input.identities!!.single().copy(pigId = "other", locationCode = "Z-S-U-R-2"))), emptySet(), 2)
        val ctx = QueueContext(f.scope, 1, true, true)
        val attempted = queue.claim(ctx, 3)!!; assertEquals(first.record.id, attempted.record.id)
        queue.finish(attempted, CommandOutcome.Uncertain, 4)
        SqliteCommandQueue(context, "$name-queue").use { reopened ->
            assertTrue(reopened.list(f.scope).first().record.groups.contains("pig:pig"))
            val independent = reopened.claim(ctx, 5)!!; assertEquals(other.record.id, independent.record.id)
            reopened.finish(independent, CommandOutcome.Success("simulated"), 6)
            assertNull(reopened.claim(ctx, 7))
            val role = reopened.enqueue(f.scope, "role-two", f.role(listOf(f.role().pigs.single().copy(pigId = "pig-two"))), emptySet(), 8)
            reopened.enqueue(f.scope, "growing-two", input.copy(locationCode = "Z-S-U-R-3", identities = listOf(input.identities!!.single().copy(pigId = "pig-two", locationCode = "Z-S-U-R-3"))), emptySet(), 9)
            val roleAttempt = reopened.claim(ctx, 10)!!; assertEquals(role.record.id, roleAttempt.record.id)
            reopened.finish(roleAttempt, CommandOutcome.Uncertain, 11)
            assertNull(reopened.claim(ctx, 12))
        }
    }

    @Test fun queueChangesDuringProfileFetchAreVisibleInPublishedProjection() = databases { cache, queue, _ ->
        val property = queue.enqueue(f.scope, "property", UpdatePigPropertyInput("pig", remark = "pending"), emptySet(), 0)
        val ctx = QueueContext(f.scope, 1, true, true)
        val value = f.reader(cache, queue, remote = f.remote(called = {
            assertEquals(property.record.id, queue.claim(ctx, 1)!!.record.id)
        })).task(f.lease, "n")
        assertEquals(ProjectionDisposition.WAITING_CONFIRMATION, value.projection.effects.single().disposition)
        assertEquals(f.pig, value.projection.previewProfiles["pig"])
        val role = queue.enqueue(f.scope, "role", f.role(), emptySet(), 2)
        val cancelled = f.reader(cache, queue, remote = f.remote(called = { queue.cancel(f.scope, role.record.id, 3) })).task(f.lease, "n")
        assertEquals(listOf(property.record.id), cancelled.projection.effects.map { it.command.record.id })
        assertEquals(f.pig, cancelled.projection.previewProfiles["pig"])
    }
}
