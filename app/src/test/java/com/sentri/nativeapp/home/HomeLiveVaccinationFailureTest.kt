package com.sentri.nativeapp.home

import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.FakeAuthGateway
import com.sentri.nativeapp.auth.MemorySessionStore
import com.sentri.nativeapp.auth.validSession
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.Farm
import com.sentri.nativeapp.network.Identity
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.vaccination.VaccinationTask
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeLiveVaccinationFailureTest {
    private val dispatcher = StandardTestDispatcher()
    private var model: HomeLiveViewModel? = null
    private val account = AccountContext(Identity("user", "name", null, "user", emptyList()),
        Farm("test-farm", "Farm"))
    private val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
    private val vaccination = object : HomeVaccinationReads {
        var fail = false
        override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<List<VaccinationTask>> {
            if (fail) throw ApiFailure.Protocol()
            return CachedResult(emptyList(), DataSource.NETWORK, 1)
        }
    }
    private val queue = object : CommandQueueStore {
        override fun enqueue(scope: DataScope, operationId: String, input: CommandInput,
            dependsOn: Set<String>, createdAt: Long): QueuedCommand = error("unused")
        override fun list(scope: DataScope): List<QueuedCommand> = emptyList()
        override fun claim(context: QueueContext, now: Long): QueuedCommand? = error("unused")
        override fun finish(attempt: QueuedCommand, outcome: CommandOutcome, now: Long) = error("unused")
        override fun recoverInterrupted(scope: DataScope, now: Long): Int = error("unused")
        override fun cancel(scope: DataScope, id: String, now: Long) = error("unused")
        override fun retryAfterConfirmation(scope: DataScope, id: String, now: Long,
            outcomeConfirmed: Boolean) = error("unused")
    }
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() = runTest(dispatcher) {
        try {
            // advanceUntilIdle cannot finish work on Dispatchers.IO. Join before restoring Main.
            model?.viewModelScope?.coroutineContext?.get(Job)?.cancelAndJoin()
        } finally { Dispatchers.resetMain() }
    }

    @Test fun inconsistentRefreshRemovesPreviouslyPublishedVaccinationCatalog() = runTest(dispatcher) {
        val places = object : HomePlaceReads {
            override suspend fun load(lease: ScopeLease, cacheOnly: Boolean) =
                CachedResult(HomePlaces(emptyList(), emptyList()), DataSource.NETWORK, 1)
        }
        val production = object : HomeProductionReads {
            override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<List<TaskMetadata>> =
                CachedResult(emptyList(), DataSource.NETWORK, 1)
        }
        val model = HomeLiveViewModel(places, production, ScopeGate("dev", auth), queue, vaccination)
        this@HomeLiveVaccinationFailureTest.model = model
        model.open(account)
        advanceUntilIdle()
        assertNotNull(model.state.value.vaccination)

        vaccination.fail = true
        model.refresh()
        advanceUntilIdle()
        assertNull(model.state.value.vaccination)
        assertEquals(HomeReadIssue.INVALID_DATA, model.state.value.vaccinationIssue)
    }
}
