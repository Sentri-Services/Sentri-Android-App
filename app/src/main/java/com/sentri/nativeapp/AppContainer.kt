package com.sentri.nativeapp

import android.content.Context
import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.EncryptedSessionStore
import com.sentri.nativeapp.auth.LogtoAuthGateway
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.network.GraphqlFarmRepository
import com.sentri.nativeapp.update.ApkUpdateRepository

/** Explicit constructor injection; UI and tests depend on interfaces rather than a global locator. */
class AppContainer(context: Context) {
    val config = AppConfig.current()
    val auth = AuthRepository(EncryptedSessionStore(context), LogtoAuthGateway(context, config))
    val graphql = ApolloClient.Builder().serverUrl(config.graphqlUrl).build()
    val farms = GraphqlFarmRepository(graphql, auth)
    val updates = ApkUpdateRepository(context)
}
