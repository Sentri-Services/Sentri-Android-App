package com.sentri.nativeapp.config

import com.sentri.nativeapp.BuildConfig

data class AppConfig(
    val environment: String,
    val graphqlUrl: String,
    val authIssuer: String,
    val clientId: String,
    val redirectUri: String,
) {
    val resource: String get() = graphqlUrl
    companion object {
        fun current() = AppConfig(BuildConfig.ENVIRONMENT, BuildConfig.GRAPHQL_URL,
            BuildConfig.AUTH_ENDPOINT, BuildConfig.AUTH_CLIENT_ID, BuildConfig.AUTH_REDIRECT_URI)
    }
}
