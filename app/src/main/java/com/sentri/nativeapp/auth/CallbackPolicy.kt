package com.sentri.nativeapp.auth

import java.net.URI

/** Exact callback origin/path matching; subdomains and lookalike hosts cannot complete login. */
class CallbackPolicy(private val redirectUri: String) {
    fun matches(url: String): Boolean = runCatching {
        val expected = URI(redirectUri)
        val actual = URI(url)
        // The existing synthetic host contains underscores, so java.net.URI.host is null.
        actual.scheme == expected.scheme && actual.rawAuthority.equals(expected.rawAuthority, ignoreCase = true) &&
            actual.rawPath == expected.rawPath && actual.rawUserInfo == null && actual.rawFragment == null
    }.getOrDefault(false)
}
