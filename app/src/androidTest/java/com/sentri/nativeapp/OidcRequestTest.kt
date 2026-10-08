package com.sentri.nativeapp

import android.net.Uri
import com.sentri.nativeapp.auth.createLogtoAuthorizationRequest
import com.sentri.nativeapp.config.AppConfig
import net.openid.appauth.AuthorizationServiceConfiguration
import org.junit.Assert.*
import org.junit.Test

class OidcRequestTest {
    private val config = AppConfig.current()
    private val service = AuthorizationServiceConfiguration(Uri.parse("https://auth.test/auth"), Uri.parse("https://auth.test/token"))
    @Test fun defaultAuthorizationPreservesBrowserConsentAndPkceContract() {
        val request = createLogtoAuthorizationRequest(service, config, false)
        assertEquals("consent", request.prompt)
        assertTrue(request.scope!!.split(' ').containsAll(listOf("openid", "profile", "offline_access")))
        assertEquals(config.resource, request.additionalParameters["resource"])
        assertEquals(config.redirectUri, request.redirectUri.toString())
        assertNotNull(request.state); assertNotNull(request.nonce); assertNotNull(request.codeVerifier)
        assertEquals("S256", request.codeVerifierChallengeMethod)
    }
    @Test fun forcedLoginIncludesLoginAndConsent() {
        assertEquals("login consent", createLogtoAuthorizationRequest(service, config, true).prompt)
    }
}
