package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.RequestContextChanged

class ScopeGate(private val environment: String, private val auth: AuthRepository) {
    fun lease(account: AccountContext): ScopeLease {
        val session = auth.session.value ?: throw RequestContextChanged()
        if (session.farmId != account.farm.id) throw RequestContextChanged()
        return ScopeLease(DataScope(environment, session.oauthId, account.identity.userId, account.farm.id), auth.contextRevision)
    }
    fun check(lease: ScopeLease) {
        val current = auth.session.value
        if (lease.revision != auth.contextRevision || lease.scope.environment != environment ||
            current?.oauthId != lease.scope.oauthId || current.farmId != lease.scope.farmId) throw RequestContextChanged()
    }
}
