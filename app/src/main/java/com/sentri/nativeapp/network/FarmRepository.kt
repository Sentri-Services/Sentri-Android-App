package com.sentri.nativeapp.network

data class FarmChoice(val farmId: String, val oauthId: String, val isChosen: Boolean, val isDefault: Boolean)
data class Identity(val userId: String, val username: String, val displayName: String?, val role: String, val accessModules: List<String>)
data class Farm(val id: String, val name: String, val timezone: String? = null)
data class AccountContext(val identity: Identity, val farm: Farm, val cachedAtMillis: Long? = null)

interface FarmRepository {
    suspend fun choices(oauthId: String): List<FarmChoice>
    suspend fun choose(choice: FarmChoice)
    suspend fun account(): AccountContext
}
