package com.sentri.nativeapp.auth

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.DiagnosticLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.GrantTypeValues
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import org.json.JSONObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Uses the existing public SPA client and redirect URI with native AppAuth PKCE/token handling. */
class LogtoAuthGateway(context: Context, private val config: AppConfig) : AuthGateway {
    private val service = AuthorizationService(context)
    private val callbackPolicy = CallbackPolicy(config.redirectUri)
    private val revokeClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

    override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest {
        DiagnosticLog.event("login_started")
        val discovered = suspendCancellableCoroutine { continuation ->
            AuthorizationServiceConfiguration.fetchFromIssuer(Uri.parse(config.authIssuer)) { result, error ->
                if (continuation.isActive) {
                    if (result != null) continuation.resume(result)
                    else continuation.resumeWithException(AuthUnavailable())
                }
            }
        }
        require(discovered.discoveryDoc?.issuer?.toString() == config.authIssuer)
        val request = createLogtoAuthorizationRequest(discovered, config, forcePrompt)
        return LoginRequest(request.jsonSerializeString(), request.toUri().toString())
    }

    override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session {
        if (!callbackPolicy.matches(callbackUrl)) throw LoginRejected()
        val original = AuthorizationRequest.jsonDeserialize(request.serialized)
        val callback = Uri.parse(callbackUrl)
        if (callback.getQueryParameter("state") != original.state || callback.getQueryParameter("error") != null) throw LoginRejected()
        val response = AuthorizationResponse.Builder(original).fromUri(callback).build()
        if (response.authorizationCode.isNullOrBlank()) throw LoginRejected()
        val tokens = perform(response.createTokenExchangeRequest(mapOf("resource" to config.resource)))
        val state = AuthState(response, tokens, null)
        DiagnosticLog.event("login_exchange_succeeded")
        DiagnosticLog.event(if (state.refreshToken.isNullOrBlank()) "refresh_token_missing" else "refresh_token_available")
        return toSession(state, null)
    }

    override suspend fun refresh(session: Session): Session {
        val state = AuthState.jsonDeserialize(session.authState)
        val refreshToken = state.refreshToken ?: run {
            DiagnosticLog.event("refresh_token_missing")
            throw SessionExpired()
        }
        val request = TokenRequest.Builder(state.authorizationServiceConfiguration!!, config.clientId)
            .setGrantType(GrantTypeValues.REFRESH_TOKEN).setRefreshToken(refreshToken)
            .setAdditionalParameters(mapOf("resource" to config.resource)).build()
        val tokens = perform(request)
        state.update(tokens, null)
        DiagnosticLog.event("token_refresh_succeeded")
        return toSession(state, session.farmId)
    }

    override fun logoutUrl(session: Session): String? {
        val state = AuthState.jsonDeserialize(session.authState)
        val endpoint = state.authorizationServiceConfiguration?.endSessionEndpoint ?: return null
        return endpoint.buildUpon().appendQueryParameter("client_id", config.clientId)
            .appendQueryParameter("post_logout_redirect_uri", config.redirectUri)
            .apply { state.idToken?.let { appendQueryParameter("id_token_hint", it) } }.build().toString()
    }

    override suspend fun revoke(session: Session): Unit = withContext(Dispatchers.IO) {
        val state = AuthState.jsonDeserialize(session.authState)
        val token = state.refreshToken ?: return@withContext
        val endpoint = state.authorizationServiceConfiguration?.discoveryDoc?.docJson
            ?.optString("revocation_endpoint")?.takeIf(String::isNotBlank) ?: return@withContext
        require(Uri.parse(endpoint).scheme == "https" && Uri.parse(endpoint).host == Uri.parse(config.authIssuer).host)
        val body = FormBody.Builder().add("client_id", config.clientId).add("token", token)
            .add("token_type_hint", "refresh_token").build()
        revokeClient.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use {
            DiagnosticLog.event(if (it.isSuccessful) "token_revocation_succeeded" else "token_revocation_unavailable")
        }
    }

    private suspend fun perform(request: TokenRequest): TokenResponse = suspendCancellableCoroutine { continuation ->
        service.performTokenRequest(request) { tokens, error ->
            if (continuation.isActive) {
                when {
                    tokens != null -> continuation.resume(tokens)
                    error?.type == AuthorizationException.TYPE_OAUTH_TOKEN_ERROR &&
                        error.error in setOf("invalid_grant", "invalid_token") -> {
                            DiagnosticLog.event("oauth_${error.error}")
                            continuation.resumeWithException(SessionExpired())
                        }
                    error?.type == AuthorizationException.TYPE_GENERAL_ERROR &&
                        error.code == AuthorizationException.GeneralErrors.NETWORK_ERROR.code -> continuation.resumeWithException(AuthUnavailable())
                    else -> continuation.resumeWithException(LoginRejected())
                }
            }
        }
    }

    private fun toSession(state: AuthState, farmId: String?): Session {
        val accessToken = state.accessToken ?: throw LoginRejected()
        // AppAuth has already checked the code-exchange ID token's issuer, audience, expiry and nonce.
        val idToken = state.idToken ?: throw LoginRejected()
        val claims = JSONObject(String(Base64.decode(idToken.split('.')[1], Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
        val oauthId = claims.optString("oauth_id").ifBlank { claims.optString("oauthId").ifBlank { claims.getString("sub") } }
        return Session(state.jsonSerializeString(), accessToken, state.accessTokenExpirationTime ?: 0, oauthId, farmId)
    }
}

internal fun createLogtoAuthorizationRequest(serviceConfig: AuthorizationServiceConfiguration,
    config: AppConfig, forcePrompt: Boolean): AuthorizationRequest =
    AuthorizationRequest.Builder(serviceConfig, config.clientId, ResponseTypeValues.CODE, Uri.parse(config.redirectUri))
        .setScopes("openid", "profile", "offline_access")
        .setAdditionalParameters(mapOf("resource" to config.resource))
        // This default is part of the existing @logto/browser contract and enables refresh tokens.
        .setPrompt(if (forcePrompt) "login consent" else "consent").build()
