package com.sentri.nativeapp.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.ApolloResponse
import com.apollographql.apollo.api.Operation
import com.apollographql.apollo.api.Query
import com.apollographql.apollo.api.Mutation
import com.apollographql.apollo.exception.ApolloHttpException
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.graphql.ChooseFarmMutation
import com.sentri.nativeapp.graphql.GetFarmInfoQuery
import com.sentri.nativeapp.graphql.GetFarmSelectionListQuery
import com.sentri.nativeapp.graphql.GetMeQuery
import com.sentri.nativeapp.graphql.type.BiosafetyChooseFarmInput
import java.util.Locale

/** No implicit Hasura role/farm headers: the existing backend obtains context from the resource JWT. */
class GraphqlFarmRepository(
    private val client: ApolloClient,
    private val auth: AuthRepository,
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) : FarmRepository {
    override suspend fun choices(oauthId: String): List<FarmChoice> {
        val response = query(GetFarmSelectionListQuery(oauthId)).biosafety.farm_selection_list
        requireSuccess(response.status.rawValue, response.errors.map { it.err_msg })
        return response.records.map { FarmChoice(it.farm_id, it.oauth_id, it.is_choosed, it.is_default) }
    }

    override suspend fun choose(choice: FarmChoice) {
        val input = BiosafetyChooseFarmInput(choice.farmId, true, choice.isDefault, choice.oauthId)
        val response = mutation(ChooseFarmMutation(input)).biosafety.choose_farm
        requireSuccess(response.status.rawValue, response.errors.map { it.err_msg })
    }

    override suspend fun account(): AccountContext {
        val me = query(GetMeQuery()).biosafety.me
        requireSuccess(me.status.rawValue, me.errors.map { it.err_msg })
        val user = me.record ?: throw ApiFailure.Protocol()
        val farm = query(GetFarmInfoQuery()).biosafety.farm_info
        return AccountContext(Identity(user.user_id, user.username, user.display_name, user.role, user.access_modules), Farm(farm.id, farm.name))
    }

    private fun requireSuccess(status: String, errors: List<String>) {
        if (status.lowercase() != "success") throw ApiFailure.Business(errors.firstOrNull().orEmpty())
    }

    private suspend fun <D : Operation.Data> execute(isMutation: Boolean, request: suspend (String) -> ApolloResponse<D>): D {
        val token = auth.token()
        var usedToken = token
        var response = request(token)
        if (isAuthentication(response)) {
            val refreshed = auth.refreshRejectedToken(token)
            usedToken = refreshed
            // Mutation results can be ambiguous: prepare a new token but never replay the write.
            if (isMutation) throw ApiFailure.Authentication()
            response = request(refreshed)
        }
        if (isAuthentication(response)) {
            auth.invalidateRejectedToken(usedToken)
            throw com.sentri.nativeapp.auth.SessionExpired()
        }
        if (response.exception != null) throw ApiFailure.Network()
        if (!response.errors.isNullOrEmpty()) throw ApiFailure.Business(response.errors!!.first().message)
        return response.data ?: throw ApiFailure.Protocol()
    }

    private fun <D : Operation.Data> isAuthentication(response: ApolloResponse<D>): Boolean =
        (response.exception as? ApolloHttpException)?.statusCode == 401 || response.errors.orEmpty().any {
            AuthErrorPolicy.isAuthentication(it.message, it.extensions?.get("code") as? String)
        }

    private suspend fun <D : Query.Data> query(operation: Query<D>): D = execute(false) { token ->
        client.query(operation).addHttpHeader("Authorization", "Bearer $token")
            .addHttpHeader("sentri-language", language()).execute()
    }

    private suspend fun <D : Mutation.Data> mutation(operation: Mutation<D>): D = execute(true) { token ->
        client.mutation(operation).addHttpHeader("Authorization", "Bearer $token")
            .addHttpHeader("sentri-language", language()).execute()
        }
}
