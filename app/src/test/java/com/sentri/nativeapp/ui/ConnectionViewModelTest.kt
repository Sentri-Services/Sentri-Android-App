package com.sentri.nativeapp.ui

import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.FakeAuthGateway
import com.sentri.nativeapp.auth.MemorySessionStore
import com.sentri.nativeapp.auth.validSession
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private class FakeFarms : FarmRepository {
        var list = listOf(FarmChoice("test-farm", "test-user", true, false))
        var chosen: FarmChoice? = null
        var accountFarm = "test-farm"
        override suspend fun choices(oauthId: String) = list
        override suspend fun choose(choice: FarmChoice) { chosen = choice }
        override suspend fun account() = AccountContext(Identity("u", "user", null, "user", emptyList()), Farm(accountFarm, "Farm"))
    }

    @Test fun `restored session fetches native account`() = runTest(dispatcher) {
        val model = ConnectionViewModel(AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }, FakeFarms())
        advanceUntilIdle()
        assertEquals(Page.ACCOUNT, model.state.value.page)
        assertEquals("test-farm", model.state.value.account?.farm?.id)
    }

    @Test fun `single farm automatically follows existing choose refresh query flow`() = runTest(dispatcher) {
        val auth = AuthRepository(MemorySessionStore(validSession().copy(farmId = null)), FakeAuthGateway()) { 0 }
        val farms = FakeFarms()
        val model = ConnectionViewModel(auth, farms)
        advanceUntilIdle()
        assertNotNull(farms.chosen)
        assertEquals(Page.ACCOUNT, model.state.value.page)
        assertEquals("test-farm", auth.session.value?.farmId)
    }

    @Test fun `multiple farms require explicit selection`() = runTest(dispatcher) {
        val auth = AuthRepository(MemorySessionStore(validSession().copy(farmId = null)), FakeAuthGateway()) { 0 }
        val farms = FakeFarms().apply { list = list + FarmChoice("other", "test-user", false, false) }
        val model = ConnectionViewModel(auth, farms)
        advanceUntilIdle()
        assertEquals(Page.FARMS, model.state.value.page)
        assertNull(farms.chosen)
    }

    @Test fun `mismatched restored farm is not shown as successful`() = runTest(dispatcher) {
        val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
        val model = ConnectionViewModel(auth, FakeFarms().apply { accountFarm = "other" })
        advanceUntilIdle()
        assertNull(model.state.value.account)
        assertNull(auth.session.value?.farmId)
        assertEquals(Page.FARMS, model.state.value.page)
        assertNotNull(model.state.value.failure)
    }

    @Test fun `logout clears account while provider logout is pending`() = runTest(dispatcher) {
        val auth = AuthRepository(MemorySessionStore(validSession()), FakeAuthGateway()) { 0 }
        val model = ConnectionViewModel(auth, FakeFarms())
        advanceUntilIdle()
        model.signOut(); advanceUntilIdle()
        assertNull(model.state.value.account)
        assertNull(auth.session.value)
        assertEquals(Page.LOGIN, model.state.value.page)
        assertTrue(model.state.value.loggingOut)
        model.closeWeb(); advanceUntilIdle()
        assertNull(model.state.value.webUrl)
    }
}
