package com.sentri.nativeapp.auth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns session writes and serializes refreshes, login completion and logout. */
class AuthRepository(
    private val store: SessionStore,
    private val gateway: AuthGateway,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableSession = MutableStateFlow(store.readSession())
    val session = mutableSession.asStateFlow()
    @Volatile var contextRevision: Long = 0
        private set
    val pendingLogin: LoginRequest? get() = store.readPendingLogin()

    suspend fun beginLogin(forcePrompt: Boolean = false): LoginRequest = mutex.withLock {
        gateway.beginLogin(forcePrompt).also(store::savePendingLogin)
    }

    suspend fun completeLogin(callbackUrl: String): Unit = mutex.withLock {
        val request = store.readPendingLogin() ?: throw LoginRejected()
        try {
            val result = gateway.exchange(request, callbackUrl)
            update(result.copy(farmId = null), newLogin = true)
        } finally {
            store.savePendingLogin(null)
        }
    }

    suspend fun cancelLogin(): Unit = mutex.withLock { store.savePendingLogin(null) }

    suspend fun token(expectedContextRevision: Long? = null): String = mutex.withLock {
        checkContext(expectedContextRevision)
        val current = mutableSession.value ?: throw SessionExpired()
        if (current.expiresAtMillis > now() + 60_000) current.accessToken else refreshLocked(current).accessToken
    }

    /** A rejected token is refreshed at most once across concurrent callers. */
    suspend fun refreshRejectedToken(rejectedToken: String, expectedContextRevision: Long? = null): String = mutex.withLock {
        checkContext(expectedContextRevision)
        val current = mutableSession.value ?: throw SessionExpired()
        if (current.accessToken != rejectedToken) current.accessToken else refreshLocked(current).accessToken
    }

    suspend fun invalidateRejectedToken(rejectedToken: String, expectedContextRevision: Long? = null): Unit = mutex.withLock {
        checkContext(expectedContextRevision)
        if (mutableSession.value?.accessToken == rejectedToken) {
            store.savePendingLogin(null)
            update(null)
        }
    }

    suspend fun refreshForFarm(farmId: String): Unit = mutex.withLock {
        val current = mutableSession.value ?: throw SessionExpired()
        update(refreshLocked(current).copy(farmId = farmId))
    }

    suspend fun clearFarm(): Unit = mutex.withLock {
        mutableSession.value?.let { update(it.copy(farmId = null)) }
    }

    suspend fun signOut(): String? = mutex.withLock {
        val current = mutableSession.value
        val url = current?.let { runCatching { gateway.logoutUrl(it) }.getOrNull() }
        store.savePendingLogin(null)
        update(null)
        try {
            current?.let { gateway.revoke(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Match the browser SDK: failed remote revocation must not undo local logout.
        }
        url
    }

    private suspend fun refreshLocked(current: Session): Session {
        try {
            return gateway.refresh(current).also(::update)
        } catch (error: SessionExpired) {
            store.savePendingLogin(null)
            update(null)
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        }
        // Network failures preserve the session; they are not an invalid grant.
    }

    private fun checkContext(expected: Long?) {
        if (expected != null && expected != contextRevision) throw CancellationException("Authentication context changed")
    }

    private fun update(value: Session?, newLogin: Boolean = false) {
        val previous = mutableSession.value
        if (newLogin || previous?.oauthId != value?.oauthId || previous?.farmId != value?.farmId) contextRevision++
        store.saveSession(value)
        mutableSession.value = value
    }
}
