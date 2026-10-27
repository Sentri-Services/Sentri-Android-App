package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID

internal object PigProfileFixture {
    val scope = TaskCatalogFixture.scope
    val lease = TaskCatalogFixture.lease
    val at = "2026-10-10T10:00:00.1234567890123+08:00"
    val operator = PigOperator("operator", "名字Case", "")
    val health = PigHealthProfile("health", scope.farmId, "Priority", at, operator, BigDecimal("38.123456789012345678900"), "operator", at, operator, at, null,
        listOf(PigPrescriptionReference("hidden", null), PigPrescriptionReference("empty", emptyList()), PigPrescriptionReference("rx", listOf(PigTherapyReference(
            PigTreatmentReference("t", "治疗", "Treatment", "详情", "Detail"), NamedReference("m", "药", "Medication"), NamedReference("catalog", "目录", "Catalog"))))),
        listOf(PigDisease("d", at, operator, DiseaseInfo("d", "病", "Disease", "缩", "D", "类", "Category", "描述", "Description"))),
        listOf(PigSymptom("pig", "s", at, operator, SymptomInfo("s", "症", "Symptom", "部位", "Part", "描述", "Description")), PigSymptom("pig", "hidden", null, null, null)),
        listOf(PigHealthHistory(0, at, "FUTURE_EVENT", "FUTURE_TRIAGE", "", JsonValue("{\"number\":0.1234567890123456789}"), JsonValue("[]"), "operator", operator)))
    val pig = PigProfile(id = "pig", farmId = scope.farmId, earTag = "", earNotch = "Notch:A", extendedEarTag = "TagEx:A", role = "PRODUCTION_BOAR", sex = "MALE", lifecycle = "ACTIVE", origin = "SELFBRED",
        locationCode = "Z-S-U-R-1", birthday = at, entryDate = null, remark = "", weight = BigDecimal("123456789.12345678900"), parity = 0,
        breedType = "YLL", breederCode = "B:Case", teatCount = 0, firstEstrusAt = null, lastEstrusAt = at, lastFarrowingAt = null,
        lastLactationAt = null, lastMatingAt = at, productionStatus = null, currentBatchId = "batch", breederProductionStatus = "MATED",
        batch = PigProfileBatch("batch", "SN:Case", "STARTED", "LineCase", listOf(PigBatchTask("task", "MATING", "RUNNING"))),
        semen = PigSemenControl("pig", scope.farmId, "COOLDOWN", at, at, false, null, at, true, at),
        location = PigProfileLocation("zone", "unit", "Z", "Z-S-U", null, "Z-S-U-R", "Z-S-U-R-1", 0,
            PigLocationName("Z", "区", "Zone"), PigLocationName("U", "单元", "Unit"), null, "R", "01"), health = health, deathImages = JsonValue("[\"https://fixture.invalid/death\"]"))
    val request = PigProfileRequest()
    fun remote(failure: Exception? = null, called: () -> Unit = {}) = object : PigProfileRemote {
        override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
            called(); failure?.let { throw it }
            val pigs = if (request.ids == null || pig.id in request.ids) listOf(pig) else emptyList()
            return PigProfilePage(request, pigs, if (request.includeCount) pigs.size else null)
        }
    }
    suspend fun warm(reads: PigProfileReads) { reads.page(lease, request); reads.detail(lease, "pig"); reads.detail(lease, "missing") }
}
class PigProfileCacheTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val f = PigProfileFixture
    private fun database(block: suspend (SqliteSnapshotCache, String) -> Unit) = runBlocking {
        val name = "pig-profile-" + UUID.randomUUID()
        try { SqliteSnapshotCache(context, name).use { block(it, name) } } finally { context.deleteDatabase(name) }
    }
    @Test fun completeUnitRosterCachesOnlyAllPagesAndRestoresOneSnapshot() = database { cache, _ ->
        val pigs = (0 until 101).map { index ->
            val id = "pig-$index"
            f.pig.copy(id = id, semen = f.pig.semen?.copy(boarId = id))
        }
        var failSecondPage = true
        val remote = object : PigProfileRemote {
            override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
                assertEquals("unit", request.unitId)
                assertEquals(setOf(PigLifecycle.ACTIVE), request.lifecycles)
                if (failSecondPage && request.offset == 100) throw ApiFailure.Network()
                return PigProfilePage(request, pigs.drop(request.offset).take(request.filter.pageSize), pigs.size)
            }
        }
        val reads = PigProfileReads(remote, cache, {})
        try { reads.completeUnitRoster(f.lease, "unit"); fail("A partial roster must not be cached") }
        catch (_: ApiFailure.Network) { }
        assertNull(cache.read(f.scope, "pig_profile_unit_complete", "unit"))
        failSecondPage = false
        val network = reads.completeUnitRoster(f.lease, "unit")
        assertEquals(DataSource.NETWORK, network.source)
        assertEquals(101, network.value.total)
        assertEquals(pigs.map(PigProfile::id), network.value.pigs.map(PigProfile::id))
        failSecondPage = true
        val saved = reads.completeUnitRoster(f.lease, "unit")
        assertEquals(DataSource.CACHE, saved.source)
        assertEquals(network.value, saved.value)
        try { reads.completeUnitRoster(ScopeLease(f.scope.copy(farmId = "other"), 2), "unit", true)
            fail("Another farm cannot reuse this roster") } catch (_: CacheMiss) { }
    }
    @Test fun completeUnitRosterSurvivesDatabaseReopenWithoutNetwork() = runBlocking {
        val name = "pig-profile-complete-reopen-${UUID.randomUUID()}"
        val pigs = listOf(f.pig, f.pig.copy(id = "second", earNotch = "Notch:B",
            semen = f.pig.semen?.copy(boarId = "second")))
        try {
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = PigProfileReads(object : PigProfileRemote {
                    override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage =
                        PigProfilePage(request, pigs.drop(request.offset).take(1), pigs.size)
                }, cache, {})
                assertEquals(pigs, reads.completeUnitRoster(f.lease, "unit").value.pigs)
            }
            SqliteSnapshotCache(context, name).use { reopened ->
                val reads = PigProfileReads(object : PigProfileRemote {
                    override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage =
                        error("The reopened offline read must not call the network")
                }, reopened, {})
                val restored = reads.completeUnitRoster(f.lease, "unit", cacheOnly = true)
                assertEquals(DataSource.CACHE, restored.source)
                assertEquals(pigs, restored.value.pigs)
                try { reads.completeUnitRoster(ScopeLease(f.scope.copy(farmId = "other"), 2), "unit", true)
                    fail("Another farm cannot reuse the reopened roster") } catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun farmWideFindSnapshotKeepsOtherUnitResultsInTheCurrentAccountAndFarm() = database { cache, name ->
        val other = f.pig.copy(id = "other", location = f.pig.location!!.copy(unitId = "other"),
            semen = f.pig.semen?.copy(boarId = "other"))
        val request = PigProfileRequest(PigFilter(search = "Notch", pageSize = 30),
            lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY)
        val remote = object : PigProfileRemote {
            override suspend fun page(lease: ScopeLease, request: PigProfileRequest) =
                PigProfilePage(request, listOf(other), 1)
        }
        assertEquals(listOf(other), PigProfileReads(remote, cache, {}).page(f.lease, request).value.pigs)
        SqliteSnapshotCache(context, name).use { reopened ->
            val reads = PigProfileReads(object : PigProfileRemote {
                override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage =
                    error("Offline read must use the saved page")
            }, reopened, {})
            val saved = reads.page(f.lease, request, true)
            assertEquals(DataSource.CACHE, saved.source)
            assertEquals(listOf(other), saved.value.pigs)
            try { reads.page(ScopeLease(f.scope.copy(farmId = "other"), f.lease.revision), request, true); fail() }
            catch (_: CacheMiss) { }
        }
    }
    @Test fun exactScanCacheRestoresOnlyTheSameFarmAndCode() = database { cache, name ->
        val remote = object : PigProfileRemote {
            override suspend fun page(lease: ScopeLease, request: PigProfileRequest) = error("unused")
            override suspend fun exactEarCode(lease: ScopeLease, code: String) = listOf(f.pig)
        }
        val reads = PigProfileReads(remote, cache, {}, { 123 })
        assertEquals(listOf(f.pig), reads.exactEarCode(f.lease, "Notch:A").value)
        try { reads.exactEarCode(f.lease, "Notch:AB"); fail() }
        catch (_: IllegalArgumentException) { }
        SqliteSnapshotCache(context, name).use { reopened ->
            val offline = PigProfileReads(remote, reopened, {})
            assertEquals(listOf(f.pig), offline.exactEarCode(f.lease, "Notch:A", true).value)
            try { offline.exactEarCode(f.lease, "Notch:AB", true); fail() }
            catch (_: CacheMiss) { }
            try { offline.exactEarCode(ScopeLease(f.scope.copy(farmId = "other"), 1), "Notch:A", true); fail() }
            catch (_: CacheMiss) { }
        }
    }
    @Test fun completeProfilesReopenWithExactValuesAbsenceAndIndependentBasicSchema() = database { cache, name ->
        f.warm(PigProfileReads(f.remote(), cache, {}) { 123 })
        SqliteSnapshotCache(context, name).use { reopened ->
            val reads = PigProfileReads(f.remote(ApiFailure.Network()), reopened, {})
            val detail = reads.detail(f.lease, "pig")
            assertEquals(f.pig, detail.value); assertEquals(123L, detail.fetchedAt); assertEquals(DataSource.CACHE, detail.source)
            assertTrue(detail.refreshFailure is ApiFailure.Network)
            assertNull(reads.detail(f.lease, "missing", true).value)
            assertEquals(PigProfilePage(f.request, listOf(f.pig), 1), reads.page(f.lease, f.request, true).value)
            try { reads.detail(f.lease, "never", true); fail() } catch (_: CacheMiss) { }
            assertNull(reopened.read(f.scope, "detail", "pig"))
        }
        cache.write(f.scope, "detail", "basic-only", StoredSnapshot("{}", 1))
        try { PigProfileReads(f.remote(), cache, {}).detail(f.lease, "basic-only", true); fail() } catch (_: CacheMiss) { }
        val nulls = f.pig.copy(id = "nulls", batch = null, semen = null, location = null, health = null, deathImages = null, weight = BigDecimal("1E+3"))
        assertEquals(nulls, PigProfileCodec.decodeDetail(PigProfileCodec.encodeDetail(nulls)))
        val zero = f.pig.copy(health = f.health.copy(prescriptionCount = 0, prescriptions = emptyList(), diseases = emptyList(), symptoms = emptyList(), history = emptyList()), deathImages = JsonValue("[]"))
        assertEquals(zero, PigProfileCodec.decodeDetail(PigProfileCodec.encodeDetail(zero)))
        val stage = f.pig.copy(breederStatusUpdatedAt = f.at)
        assertEquals(stage, PigProfileCodec.decodeDetail(PigProfileCodec.encodeDetail(stage)))
        val scopedBatch = f.pig.copy(batch = f.pig.batch?.copy(farmId = f.scope.farmId))
        assertEquals(scopedBatch, PigProfileCodec.decodeDetail(PigProfileCodec.encodeDetail(scopedBatch)))
        val legacy = JSONObject(PigProfileCodec.encodeDetail(f.pig)).apply {
            getJSONObject("value").remove("breederStatusUpdatedAt")
            getJSONObject("value").getJSONObject("batch").remove("farmId")
        }.toString()
        assertEquals(f.pig, PigProfileCodec.decodeDetail(legacy))
    }
    @Test fun allScopesRequestFieldsAndSetOrderHaveCorrectCacheIdentity() = database { cache, _ ->
        f.warm(PigProfileReads(f.remote(), cache, {}))
        val reads = PigProfileReads(f.remote(), cache, {})
        for (scope in listOf(f.scope.copy(environment = "other"), f.scope.copy(oauthId = "other"), f.scope.copy(userId = "other"), f.scope.copy(farmId = "other"))) {
            try { reads.page(ScopeLease(scope, 2), f.request, true); fail() } catch (_: CacheMiss) { }
            try { reads.detail(ScopeLease(scope, 2), "pig", true); fail() } catch (_: CacheMiss) { }
        }
        for (request in listOf(f.request.copy(offset = 1), f.request.copy(filter = PigFilter(search = "Case")),
            f.request.copy(filter = PigFilter(search = "case")), f.request.copy(filter = PigFilter(unitCode = "Unit")), f.request.copy(filter = PigFilter(penLocation = "Pen")),
            f.request.copy(filter = PigFilter(pageSize = 1)), f.request.copy(ids = emptySet()), f.request.copy(roles = setOf(PigRole.PRODUCTION_BOAR)),
            f.request.copy(lifecycles = setOf(PigLifecycle.ACTIVE)), f.request.copy(batchId = "batch"), f.request.copy(order = PigProfileOrder.LOCATION_IDENTITY), f.request.copy(includeCount = false), f.request.copy(unitId = "unit"))) {
            try { reads.page(f.lease, request, true); fail() } catch (_: CacheMiss) { }
        }
        assertEquals(PigProfileCodec.key(f.request.copy(ids = linkedSetOf("B", "A"))), PigProfileCodec.key(f.request.copy(ids = linkedSetOf("A", "B"))))
        val exact = f.request.copy(unitId = "unit")
        assertEquals(exact, PigProfileCodec.decodePage(PigProfileCodec.encodePage(PigProfilePage(exact, listOf(f.pig), 1))).request)
    }
    @Test fun malformedCompletePayloadCannotFallBackToApparentlyValidBasicData() = database { cache, _ ->
        val encoded = PigProfileCodec.encodeDetail(f.pig)
        val invalid = listOf(encoded.replace("\"format\":1", "\"format\":2"), encoded.replace("\"weight\":\"123456789.12345678900\"", "\"weight\":123.0"),
            encoded.replace("\"teatCount\":0", "\"teatCount\":0.5"), encoded.replace(f.at, "bad"), encoded.replace("\"farmId\":\"farm\"", "\"farmId\":\"foreign\""),
            encoded.replace("\"boarId\":\"pig\"", "\"boarId\":\"foreign\""), encoded.replace("\"manuallyMarked\":false", "\"manuallyMarked\":\"false\""),
            JSONObject(encoded).apply { getJSONObject("value").getJSONObject("health").put("unexpected", 1) }.toString())
        val reads = PigProfileReads(f.remote(), cache, {})
        for (bad in invalid) {
            assertNotEquals(encoded, bad); cache.write(f.scope, "pig_profile_detail", "pig", StoredSnapshot(bad, 1))
            try { reads.detail(f.lease, "pig", true); fail() } catch (_: CacheInvalid) { }
        }
        val page = PigProfileCodec.encodePage(PigProfilePage(f.request, listOf(f.pig), 1))
        for (bad in listOf(JSONObject(page).apply { getJSONArray("pigs").put(getJSONArray("pigs").getJSONObject(0)) }.toString(),
            page.replace("\"count\":true", "\"count\":false"), page.replace("\"offset\":0", "\"offset\":1"))) {
            cache.write(f.scope, "pig_profile_page", PigProfileCodec.key(f.request), StoredSnapshot(bad, 1))
            try { reads.page(f.lease, f.request, true); fail() } catch (_: CacheInvalid) { }
        }
    }
    @Test fun onlyNetworkOrAuthUnavailableFallsBackAndContextChangePreventsWrite() = database { cache, _ ->
        f.warm(PigProfileReads(f.remote(), cache, {}))
        assertTrue(PigProfileReads(f.remote(AuthUnavailable()), cache, {}).detail(f.lease, "pig").refreshFailure is AuthUnavailable)
        for (failure in listOf(ApiFailure.Protocol(), ApiFailure.Business("fixture"), ApiFailure.Authentication())) {
            try { PigProfileReads(f.remote(failure), cache, {}).detail(f.lease, "pig"); fail() } catch (actual: Exception) { assertSame(failure, actual) }
        }
        var active = true
        val reads = PigProfileReads(f.remote(called = { active = false }), cache, { if (!active) throw CancellationException("Fixture context change") })
        try { reads.page(f.lease, f.request.copy(includeCount = false)); fail() } catch (_: CancellationException) { }
        assertNull(cache.read(f.scope, "pig_profile_page", PigProfileCodec.key(f.request.copy(includeCount = false))))
    }
    @Test fun completeHealthHistoryBeyondCursorWindowIsRestoredExactly() = database { cache, name ->
        val text = "{\"text\":\"" + "x".repeat(2_700_000) + "🐷\"}"
        val large = f.pig.copy(health = f.health.copy(history = listOf(f.health.history.single().copy(symptoms = JsonValue(text)))))
        cache.write(f.scope, "pig_profile_detail", "pig", StoredSnapshot(PigProfileCodec.encodeDetail(large), 123))
        SqliteSnapshotCache(context, name).use { reopened ->
            val restored = PigProfileReads(f.remote(), reopened, {}).detail(f.lease, "pig", true).value
            val actual = restored?.health?.history?.single()?.symptoms?.encoded
            assertEquals(text.length, actual?.length); assertTrue(text == actual)
        }
    }
    @Test fun profileKindsShareReadBudgetAndCacheClearLeavesCommandsIntact() = database { cache, name ->
        for (i in 0..256) cache.write(f.scope, if (i % 2 == 0) "pig_profile_page" else "pig_profile_detail", "key$i", StoredSnapshot("{}", i.toLong()))
        assertNull(cache.read(f.scope, "pig_profile_page", "key0")); assertNotNull(cache.read(f.scope, "pig_profile_detail", "key1"))
        val commandName = "$name-commands"
        try { SqliteCommandQueue(context, commandName).use { queue ->
            queue.enqueue(f.scope, "property", UpdatePigPropertyInput("pig", remark = ""), emptySet(), 0)
            cache.clear(f.scope); assertNull(cache.read(f.scope, "pig_profile_detail", "key1")); assertEquals(1, queue.list(f.scope).size)
        } } finally { context.deleteDatabase(commandName) }
    }
}
