package com.sentri.nativeapp.auth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class MemorySessionStore(var value: Session? = null) : SessionStore {
    var pending: LoginRequest? = null
    override fun readSession() = value
    override fun saveSession(session: Session?) { value = session }
    override fun readPendingLogin() = pending
    override fun savePendingLogin(request: LoginRequest?) { pending = request }
}

class FakeAuthGateway : AuthGateway {
    var refreshCalls = 0
    var failure: Exception? = null
    var gate: CompletableDeferred<Unit>? = null
    var revocationCalls = 0
    var revocationFailure: Exception? = null
    override suspend fun beginLogin(forcePrompt: Boolean) = LoginRequest("request", "https://auth.test/login")
    override suspend fun exchange(request: LoginRequest, callbackUrl: String) = validSession()
    override suspend fun refresh(session: Session): Session {
        refreshCalls++
        gate?.await()
        failure?.let { throw it }
        return session.copy(accessToken = "fresh-$refreshCalls", expiresAtMillis = 1_000_000)
    }
    override fun logoutUrl(session: Session) = "https://auth.test/logout"
    override suspend fun revoke(session: Session) {
        revocationCalls++
        revocationFailure?.let { throw it }
    }
}

fun validSession() = Session("serialized-test-state", "test-token", 1_000_000, "test-user", "test-farm")

class AuthRepositoryTest {
    @Test fun `restores existing session without refresh`() = runTest {
        val store = MemorySessionStore(validSession())
        val gateway = FakeAuthGateway()
        val repo = AuthRepository(store, gateway) { 0 }
        assertEquals("test-token", repo.token())
        assertEquals("test-farm", repo.session.value?.farmId)
        assertEquals(0, gateway.refreshCalls)
    }

    @Test fun `concurrent expired requests share one refresh`() = runTest {
        val gateway = FakeAuthGateway()
        val repo = AuthRepository(MemorySessionStore(validSession().copy(expiresAtMillis = 0)), gateway) { 0 }
        val tokens = List(12) { async { repo.token() } }.awaitAll()
        assertEquals(setOf("fresh-1"), tokens.toSet())
        assertEquals(1, gateway.refreshCalls)
    }

    @Test fun `concurrent server rejections share refreshed token`() = runTest {
        val gateway = FakeAuthGateway()
        val repo = AuthRepository(MemorySessionStore(validSession()), gateway) { 0 }
        List(12) { async { repo.refreshRejectedToken("test-token") } }.awaitAll()
        assertEquals(1, gateway.refreshCalls)
    }

    @Test fun `invalid grant clears persistent session and farm`() = runTest {
        val store = MemorySessionStore(validSession().copy(expiresAtMillis = 0))
        val gateway = FakeAuthGateway().apply { failure = SessionExpired() }
        val repo = AuthRepository(store, gateway) { 0 }
        try { repo.token(); fail("Expected expired session") } catch (_: SessionExpired) { }
        assertNull(repo.session.value)
        assertNull(store.value)
    }

    @Test fun `network failure preserves persistent session`() = runTest {
        val store = MemorySessionStore(validSession().copy(expiresAtMillis = 0))
        val gateway = FakeAuthGateway().apply { failure = AuthUnavailable() }
        val repo = AuthRepository(store, gateway) { 0 }
        try { repo.token(); fail("Expected network failure") } catch (_: AuthUnavailable) { }
        assertNotNull(store.value)
        assertNotNull(repo.session.value)
    }

    @Test fun `logout after in-flight refresh cannot resurrect session`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val store = MemorySessionStore(validSession().copy(expiresAtMillis = 0))
        val gateway = FakeAuthGateway().apply { this.gate = gate }
        val repo = AuthRepository(store, gateway) { 0 }
        val refresh = async { repo.token() }
        testScheduler.runCurrent()
        val logout = async { repo.signOut() }
        gate.complete(Unit)
        refresh.await(); logout.await()
        assertNull(repo.session.value)
        assertNull(store.value)
    }

    @Test fun `login completion requires pending request and clears selection`() = runTest {
        val store = MemorySessionStore()
        val repo = AuthRepository(store, FakeAuthGateway()) { 0 }
        try { repo.completeLogin("callback"); fail("Expected rejected callback") } catch (_: LoginRejected) { }
        repo.beginLogin()
        assertNotNull(store.pending)
        repo.completeLogin("callback")
        assertNull(store.pending)
        assertNull(repo.session.value?.farmId)
        assertEquals("test-user", repo.session.value?.oauthId)
    }

    @Test fun `farm selection forces token refresh before persisting context`() = runTest {
        val store = MemorySessionStore(validSession())
        val gateway = FakeAuthGateway()
        val repo = AuthRepository(store, gateway) { 0 }
        repo.clearFarm()
        repo.refreshForFarm("new-farm")
        assertEquals("new-farm", store.value?.farmId)
        assertEquals("fresh-1", repo.token())
        assertEquals(1, gateway.refreshCalls)
    }

    @Test fun `logout removes pending login and prevents token access`() = runTest {
        val store = MemorySessionStore(validSession())
        val repo = AuthRepository(store, FakeAuthGateway()) { 0 }
        repo.beginLogin()
        assertEquals("https://auth.test/logout", repo.signOut())
        assertNull(store.pending)
        assertNull(store.value)
        try { repo.token(); fail("Expected no token") } catch (_: SessionExpired) { }
    }

    @Test fun `logout revokes refresh grant after clearing local state`() = runTest {
        val store = MemorySessionStore(validSession())
        val gateway = FakeAuthGateway()
        val repo = AuthRepository(store, gateway) { 0 }
        repo.signOut()
        assertEquals(1, gateway.revocationCalls)
        assertNull(store.value)
    }

    @Test fun `remote revocation failure cannot retain local session`() = runTest {
        val store = MemorySessionStore(validSession())
        val gateway = FakeAuthGateway().apply { revocationFailure = AuthUnavailable() }
        val repo = AuthRepository(store, gateway) { 0 }
        assertEquals("https://auth.test/logout", repo.signOut())
        assertNull(store.value)
        assertNull(repo.session.value)
    }
}
