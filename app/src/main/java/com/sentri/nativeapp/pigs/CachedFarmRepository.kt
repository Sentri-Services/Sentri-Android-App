package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Enables offline restart only for an existing authenticated, selected-farm session. */
class CachedFarmRepository(private val delegate: FarmRepository, private val cache: SnapshotCache, private val auth: AuthRepository, private val environment: String, private val language: () -> String? = { null }) : FarmRepository {
    override suspend fun choices(oauthId: String) = delegate.choices(oauthId)
    override suspend fun choose(choice: FarmChoice) = delegate.choose(choice)

    /** Only for rescheduling durable work; the worker must still verify the live account before sending. */
    fun cachedAccountOrNull(): AccountContext? {
        val session = auth.session.value ?: return null
        val farm = session.farmId ?: return null
        val revision = auth.contextRevision
        val requestLanguage = language()
        val saved = cache.readAccount(environment, session.oauthId, farm, requestLanguage) ?: return null
        val account = try { SnapshotCodec.account(saved.snapshot.payload) } catch (_: Exception) { return null }
        if (account.farm.id != farm || account.identity.userId != saved.userId ||
            auth.contextRevision != revision || language() != requestLanguage) return null
        return account.copy(cachedAtMillis = saved.snapshot.fetchedAt)
    }

    override suspend fun account(): AccountContext = withContext(Dispatchers.IO) {
        val session = auth.session.value ?: throw com.sentri.nativeapp.auth.SessionExpired()
        val farm = session.farmId ?: throw RequestContextChanged()
        val revision = auth.contextRevision
        val requestLanguage = language()
        fun check() { if (revision != auth.contextRevision || language() != requestLanguage) throw RequestContextChanged() }
        val account = try { delegate.account() } catch (error: Exception) {
            check()
            if (error !is ApiFailure.Network && error !is AuthUnavailable) throw error
            val saved = cache.readAccount(environment, session.oauthId, farm, requestLanguage) ?: throw error
            val value = try {
                SnapshotCodec.account(saved.snapshot.payload).also { require(it.farm.id == farm && it.identity.userId == saved.userId) }
            } catch (_: Exception) { throw CacheInvalid() }
            check()
            return@withContext value.copy(cachedAtMillis = saved.snapshot.fetchedAt)
        }
        check(); require(account.farm.id == farm)
        val scope = DataScope(environment, session.oauthId, account.identity.userId, farm)
        cache.write(scope, "account", if (requestLanguage == null) "current" else "current:$requestLanguage", StoredSnapshot(SnapshotCodec.account(account), System.currentTimeMillis()))
        check(); account
    }
}
