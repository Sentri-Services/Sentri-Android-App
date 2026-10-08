package com.sentri.nativeapp.network

sealed class ApiFailure : Exception() {
    class Network : ApiFailure()
    class Authentication : ApiFailure()
    class Business(val detail: String) : ApiFailure()
    class Protocol : ApiFailure()
}

object AuthErrorPolicy {
    fun isAuthentication(message: String, code: String?): Boolean {
        val text = message.lowercase()
        val normalizedCode = code?.lowercase()
        return normalizedCode in setOf("invalid-jwt", "unauthenticated", "unauthorized", "invalid_grant") ||
            listOf("jwtexpired", "invalid-jwt", "invalid_grant").any(text::contains) ||
            (text.contains("missing") && text.contains("authorization") && text.contains("jwt authentication"))
    }
}
