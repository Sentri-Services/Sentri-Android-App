package com.sentri.nativeapp.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.*
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.sentri.nativeapp.auth.AuthRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException

class RequestContextChanged : CancellationException("Request context changed")

/** Shared authentication policy. Queries can retry once; writes are never replayed. */
class GraphqlExecutor(
    private val client: ApolloClient,
    private val auth: AuthRepository,
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private suspend fun <D : Operation.Data> execute(isMutation: Boolean, request: suspend (String, String) -> ApolloResponse<D>): D {
        val context = auth.contextRevision
        val requestLanguage = language()
        fun checkContext() { if (context != auth.contextRevision || language() != requestLanguage) throw RequestContextChanged() }
        val token = auth.token(context)
        checkContext()
        var usedToken = token
        var response = request(token, requestLanguage)
        checkContext()
        if (isAuthentication(response)) {
            val refreshed = auth.refreshRejectedToken(token, context)
            checkContext()
            usedToken = refreshed
            // Mutation results can be ambiguous: prepare a new token but never replay the write.
            if (isMutation) throw ApiFailure.Authentication()
            response = request(refreshed, requestLanguage)
        }
        checkContext()
        if (isAuthentication(response)) {
            auth.invalidateRejectedToken(usedToken, context)
            throw com.sentri.nativeapp.auth.SessionExpired()
        }
        (response.exception as? ApolloHttpException)?.statusCode?.let {
            if (it in 400..499) throw ApiFailure.Business("HTTP $it")
        }
        if (response.exception != null) {
            if (response.exception is ApolloNetworkException || response.exception is ApolloHttpException) throw ApiFailure.Network()
            throw ApiFailure.Protocol()
        }
        if (!response.errors.isNullOrEmpty()) throw ApiFailure.Business(response.errors!!.first().message)
        return response.data ?: throw ApiFailure.Protocol()
    }

    private fun <D : Operation.Data> isAuthentication(response: ApolloResponse<D>): Boolean =
        (response.exception as? ApolloHttpException)?.statusCode == 401 || response.errors.orEmpty().any {
            AuthErrorPolicy.isAuthentication(it.message, it.extensions?.get("code") as? String)
        }

    suspend fun <D : Query.Data> query(operation: Query<D>): D = execute(false) { token, requestLanguage ->
        client.query(operation).addHttpHeader("Authorization", "Bearer $token")
            .addHttpHeader("sentri-language", requestLanguage).execute()
    }

    suspend fun <D : Mutation.Data> mutation(operation: Mutation<D>): D = execute(true) { token, requestLanguage ->
        client.mutation(operation).addHttpHeader("Authorization", "Bearer $token")
            .addHttpHeader("sentri-language", requestLanguage).execute()
        }
}
