package com.sentri.nativeapp.network

import org.junit.Assert.*
import org.junit.Test

class AuthErrorPolicyTest {
    @Test fun `recognizes existing Hasura and Logto expiry errors`() {
        assertTrue(AuthErrorPolicy.isAuthentication("JWTExpired", null))
        assertTrue(AuthErrorPolicy.isAuthentication("token invalid", "invalid-jwt"))
        assertTrue(AuthErrorPolicy.isAuthentication("oidc.invalid_grant", null))
        assertTrue(AuthErrorPolicy.isAuthentication("missing Authorization header or Cookie in JWT authentication mode", null))
    }
    @Test fun `permission and business failures do not trigger token refresh`() {
        assertFalse(AuthErrorPolicy.isAuthentication("Permission denied", "access-denied"))
        assertFalse(AuthErrorPolicy.isAuthentication("Invalid field", "validation-failed"))
        assertFalse(AuthErrorPolicy.isAuthentication("Gateway timeout", null))
    }
}
