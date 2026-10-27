package com.sentri.nativeapp.network

import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.graphql.ChooseFarmMutation
import com.sentri.nativeapp.graphql.GetFarmInfoQuery
import com.sentri.nativeapp.graphql.GetFarmTimezoneQuery
import com.sentri.nativeapp.graphql.GetFarmSelectionListQuery
import com.sentri.nativeapp.graphql.GetMeQuery
import com.sentri.nativeapp.graphql.type.BiosafetyChooseFarmInput
import java.util.Locale
import kotlinx.coroutines.CancellationException

/** No implicit Hasura role/farm headers: the existing backend obtains context from the resource JWT. */
class GraphqlFarmRepository(
    private val client: ApolloClient,
    private val auth: AuthRepository,
    private val language: () -> String = { Locale.getDefault().toLanguageTag() },
) : FarmRepository {
    private val executor = GraphqlExecutor(client, auth, language)

    override suspend fun choices(oauthId: String): List<FarmChoice> {
        val response = executor.query(GetFarmSelectionListQuery(oauthId)).biosafety.farm_selection_list
        requireSuccess(response.status.rawValue, response.errors.map { it.err_msg })
        return response.records.map { FarmChoice(it.farm_id, it.oauth_id, it.is_choosed, it.is_default) }
    }

    override suspend fun choose(choice: FarmChoice) {
        val input = BiosafetyChooseFarmInput(choice.farmId, true, choice.isDefault, choice.oauthId)
        val response = executor.mutation(ChooseFarmMutation(input)).biosafety.choose_farm
        requireSuccess(response.status.rawValue, response.errors.map { it.err_msg })
    }

    override suspend fun account(): AccountContext {
        val revision = auth.contextRevision
        val me = executor.query(GetMeQuery()).biosafety.me
        requireSuccess(me.status.rawValue, me.errors.map { it.err_msg })
        val user = me.record ?: throw ApiFailure.Protocol()
        val farm = executor.query(GetFarmInfoQuery()).biosafety.farm_info
        if (revision != auth.contextRevision) throw RequestContextChanged()
        // The current backend exposes timezone, but older deployed schemas may reject this optional query.
        val timezone = try {
            val clock = executor.query(GetFarmTimezoneQuery()).biosafety.farm_info
            if (clock.id == farm.id) FarmTimezone.validated(clock.timezone) else null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: ApiFailure.Business) { null }
        catch (_: ApiFailure.Network) { null }
        catch (_: ApiFailure.Protocol) { null }
        if (revision != auth.contextRevision) throw RequestContextChanged()
        return AccountContext(Identity(user.user_id, user.username, user.display_name, user.role, user.access_modules),
            Farm(farm.id, farm.name, timezone))
    }

    private fun requireSuccess(status: String, errors: List<String>) {
        if (status.lowercase() != "success") throw ApiFailure.Business(errors.firstOrNull().orEmpty())
    }

}
