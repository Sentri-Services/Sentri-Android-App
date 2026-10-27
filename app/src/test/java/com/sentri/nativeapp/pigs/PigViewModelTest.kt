package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PigViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val account = AccountContext(Identity("user", "name", null, "user", emptyList()), Farm("test-farm", "Farm"))
    private val reads = Reads()
    private val gate = ScopeGate("dev", auth)
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun model() = PigViewModel(reads, gate, auth)
    private class Reads : PigReads {
        var position: BrowsePosition? = null
        var pageFailure: Exception? = null
        var detailFailure: Exception? = null
        var slow: CompletableDeferred<Unit>? = null
        val offsets = mutableListOf<Int>()
        override suspend fun locations(lease: ScopeLease, cacheOnly: Boolean) = CachedResult(Locations(listOf(FarmUnit("unit", lease.scope.farmId, "Unit", "Unit", "Z-S-U", "sow")), emptyList()), DataSource.NETWORK, 1)
        override suspend fun page(lease: ScopeLease, filter: PigFilter, offset: Int, cacheOnly: Boolean): CachedResult<PigPage> {
            offsets += offset
            if (filter.search == "slow") withContext(NonCancellable) { slow!!.await() }
            pageFailure?.let { throw it }
            val total = if (filter.search == "empty") 0 else if (filter.search == "slow") 99 else 40
            return CachedResult(PigPage(emptyList(), total, offset, filter.pageSize), if (cacheOnly) DataSource.CACHE else DataSource.NETWORK, 1)
        }
        override suspend fun detail(lease: ScopeLease, id: String, cacheOnly: Boolean): CachedResult<PigDetail?> {
            detailFailure?.let { throw it }; return CachedResult(null, DataSource.NETWORK, 1)
        }
        override suspend fun restore(lease: ScopeLease) = position
        override suspend fun remember(lease: ScopeLease, position: BrowsePosition) { this.position = position }
        override suspend fun clear(lease: ScopeLease) { position = null }
    }
    @Test fun `cancelled filter cannot overwrite newer response even if dependency ignores cancellation`() = runTest(dispatcher) {
        val vm = model(); vm.open(account); advanceUntilIdle()
        reads.slow = CompletableDeferred()
        vm.applyFilter("Z-S-U", null, "slow"); runCurrent()
        assertTrue(vm.state.value.loading)
        vm.applyFilter("Z-S-U", null, "empty"); runCurrent()
        assertEquals(0, vm.state.value.page?.value?.total)
        reads.slow!!.complete(Unit); advanceUntilIdle()
        assertEquals("empty", vm.state.value.filter.search)
        assertEquals(0, vm.state.value.page?.value?.total)
        assertFalse(vm.state.value.loading)
        assertNull(vm.state.value.failure)
    }
    @Test fun `pagination failure keeps current page and retry uses failed offset`() = runTest(dispatcher) {
        val vm = model(); vm.open(account); advanceUntilIdle()
        reads.pageFailure = ApiFailure.Network()
        vm.next(); advanceUntilIdle()
        assertEquals(PigFailure.NETWORK, vm.state.value.failure)
        assertEquals(0, vm.state.value.page?.value?.offset)
        assertEquals(20, vm.state.value.requestedOffset)
        reads.pageFailure = null; vm.retry(); advanceUntilIdle()
        assertEquals(20, vm.state.value.page?.value?.offset)
        assertEquals(listOf(0, 20, 20), reads.offsets)
    }
    @Test fun `restored detail and cache mode remain saved and detail failure can retry`() = runTest(dispatcher) {
        reads.position = BrowsePosition(PigFilter("Z-S-U"), 20, "pig", true)
        val vm = model(); vm.open(account); advanceUntilIdle()
        assertEquals("pig", vm.state.value.detailId)
        assertTrue(vm.state.value.cacheOnly)
        assertEquals(DataSource.CACHE, vm.state.value.page?.source)
        assertEquals("pig", reads.position?.detailId)
        reads.detailFailure = ApiFailure.Business("denied")
        vm.refresh(); advanceUntilIdle()
        assertEquals(PigFailure.BUSINESS, vm.state.value.failure)
        assertNull(vm.state.value.detail)
        reads.detailFailure = null; vm.retry(); advanceUntilIdle()
        assertNotNull(vm.state.value.detail)
        assertNull(vm.state.value.detail?.value)
        vm.closeDetail(); advanceUntilIdle()
        assertNull(reads.position?.detailId)
        assertEquals(20, reads.position?.offset)
    }
    @Test fun `logout invalidates displayed data and late responses`() = runTest(dispatcher) {
        val vm = model(); vm.open(account); advanceUntilIdle()
        reads.slow = CompletableDeferred()
        vm.applyFilter("Z-S-U", null, "slow"); runCurrent()
        auth.signOut(); runCurrent()
        assertNull(vm.state.value.lease)
        assertNull(vm.state.value.page)
        reads.slow!!.complete(Unit); advanceUntilIdle()
        assertNull(vm.state.value.page)
        assertNull(vm.state.value.detail)
    }
}
