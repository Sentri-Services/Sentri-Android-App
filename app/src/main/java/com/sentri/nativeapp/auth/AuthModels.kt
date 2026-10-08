package com.sentri.nativeapp.auth

data class Session(
    val authState: String,
    val accessToken: String,
    val expiresAtMillis: Long,
    val oauthId: String,
    val farmId: String? = null,
) {
    override fun toString() = "Session(authState=<redacted>, accessToken=<redacted>, farmSelected=${farmId != null})"
}

data class LoginRequest(val serialized: String, val url: String) {
    override fun toString() = "LoginRequest(<redacted>)"
}

interface SessionStore {
    fun readSession(): Session?
    fun saveSession(session: Session?)
    fun readPendingLogin(): LoginRequest?
    fun savePendingLogin(request: LoginRequest?)
}

interface AuthGateway {
    suspend fun beginLogin(forcePrompt: Boolean): LoginRequest
    suspend fun exchange(request: LoginRequest, callbackUrl: String): Session
    suspend fun refresh(session: Session): Session
    fun logoutUrl(session: Session): String?
    suspend fun revoke(session: Session) { }
}

class SessionExpired : Exception("Session expired")
class AuthUnavailable : Exception("Authentication service unavailable")
class LoginRejected : Exception("Login could not be completed")
