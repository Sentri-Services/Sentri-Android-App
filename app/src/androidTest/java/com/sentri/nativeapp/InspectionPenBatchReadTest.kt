package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.home.*
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.*
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Uses isolated SQLite and fake auth, never the user's logged-in session. */
class InspectionPenBatchReadTest {
    @Test fun batchLabelsLoadWithoutPigBatchRelationAndRestoreAfterSQLiteReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pen-batch-test-${UUID.randomUUID()}"
        val scope = PigProfileFixture.scope
        val auth = AuthRepository(object : SessionStore {
            private var value: Session? = Session("{}", "token", Long.MAX_VALUE, scope.oauthId, scope.farmId)
            override fun readSession() = value
            override fun saveSession(session: Session?) { value = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) = Unit
        }, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
            override suspend fun refresh(session: Session) = session
            override fun logoutUrl(session: Session): String? = null
        })
        val gate = ScopeGate(scope.environment, auth)
        val lease = ScopeLease(scope, auth.contextRevision)
        val unit = FarmUnit("unit", scope.farmId, "单元", "Unit", "Z-S-U", "AnimalHousing")
        val pig = PigProfileFixture.pig.copy(batch = null)
        val code = requireNotNull(pig.location?.penLocation)
        val number = requireNotNull(pig.location?.penNumber)
        val batch = ProductionBatch("inventory-batch", scope.farmId, "31", ProductionBatchStatus.STARTED,
            "line", null, emptyList())
        val inventory = LocationInventorySnapshot(code, listOf(
            LocationInventoryPig("unknown", scope.farmId, code, "ACTIVE", null, null, null),
            LocationInventoryPig("assigned", scope.farmId, code, "ACTIVE", null, null, batch.id)))
        var batchMode = "online"
        var cache = SqliteSnapshotCache(context, name)
        val models = mutableListOf<InspectionWalkViewModel>()
        suspend fun walk(): InspectionWalkViewModel {
            val profiles = PigProfileReads(object : PigProfileRemote {
                override suspend fun page(lease: ScopeLease, request: PigProfileRequest) =
                    PigProfilePage(request, listOf(pig), if (request.includeCount) 1 else null)
            }, cache, gate::check)
            val batches = ProductionAuxiliaryReads(object : ProductionAuxiliaryRemote {
                override suspend fun batch(lease: ScopeLease, id: String): ProductionBatch? {
                    assertEquals(batch.id, id)
                    return when (batchMode) {
                        "offline" -> throw ApiFailure.Network()
                        "foreign" -> batch.copy(farmId = "another-farm")
                        "missing" -> null
                        else -> batch
                    }
                }
                override suspend fun batches(lease: ScopeLease, request: BatchRequest): BatchPage = error("unused")
                override suspend fun rechecks(lease: ScopeLease, request: RecheckRequest): RecheckPage = error("unused")
            }, cache, gate::check)
            val vm = withContext(Dispatchers.Main) {
                InspectionWalkViewModel(profiles, gate,
                    penRosters = HomeUnitPenRosterReads(HomeUnitPenRosterRemote { _, _ ->
                        HomeUnitPenRoster(unit.id, 2, 0, listOf(HomePenCount(code, number, 2)),
                            listOf(HomeRegisteredPen(1, code, number)))
                    }, cache, gate::check),
                    locationInventory = LocationInventoryReads(object : LocationInventoryRemote {
                        override suspend fun current(lease: ScopeLease, unitId: String, locationCode: String) = inventory
                    }, cache, gate::check),
                    readBatch = { requestedLease, id -> batches.batch(requestedLease, id) })
                    .also { models += it; it.open(lease, unit) }
            }
            withTimeout(5_000) { vm.state.first { it.page != null && !it.loading } }
            withContext(Dispatchers.Main) { vm.openPen(code) }
            withTimeout(5_000) { vm.state.first { it.penInventory != null && !it.penInventoryLoading &&
                !it.penBatchesLoading && (batch.id in it.penBatches || it.penBatchesFailed) } }
            return vm
        }
        try {
            val online = walk()
            assertEquals("31", online.state.value.penBatches[batch.id]?.value?.serial)
            assertEquals(DataSource.NETWORK, online.state.value.penBatches[batch.id]?.source)
            assertNull(online.state.value.penPage?.value?.pigs?.single()?.batch)
            assertEquals(inventory, online.state.value.penInventory?.value)
            withContext(Dispatchers.Main) { online.clear() }
            cache.close()
            cache = SqliteSnapshotCache(context, name)
            batchMode = "offline"
            val restored = walk()
            assertEquals(DataSource.CACHE, restored.state.value.penBatches[batch.id]?.source)
            assertEquals("31", restored.state.value.penBatches[batch.id]?.value?.serial)
            assertEquals(DataSource.NETWORK, restored.state.value.penInventory?.source)
            withContext(Dispatchers.Main) { restored.clear() }
            batchMode = "foreign"
            val rejected = walk()
            assertTrue(rejected.state.value.penBatchesFailed)
            assertTrue(rejected.state.value.penBatches.isEmpty())
            assertEquals(inventory, rejected.state.value.penInventory?.value)
            assertFalse(rejected.state.value.penInventoryFailed)
            withContext(Dispatchers.Main) { rejected.clear() }
            batchMode = "missing"
            val missing = walk()
            assertFalse(missing.state.value.penBatchesFailed)
            assertTrue(batch.id in missing.state.value.penBatches)
            assertNull(missing.state.value.penBatches[batch.id]?.value)
            withContext(Dispatchers.Main) { missing.closePen() }
            assertTrue(missing.state.value.penBatches.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { models.forEach(InspectionWalkViewModel::clear) }
            cache.close()
            context.deleteDatabase(name)
        }
    }
}
