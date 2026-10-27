package com.sentri.nativeapp

import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.Farm
import com.sentri.nativeapp.network.Identity
import com.sentri.nativeapp.ui.ConnectionUiState
import com.sentri.nativeapp.ui.Page
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppRoutingTest {
    private val account = AccountContext(
        Identity("user", "name", null, "user", emptyList()),
        Farm("farm", "Farm"),
    )

    @Test fun authenticatedAccountDefaultsToHome() {
        assertTrue(shouldShowHome(ConnectionUiState(page = Page.ACCOUNT, account = account),
            previewOpen = false, integrationOpen = false, businessOpen = false, devicesOpen = false))
    }

    @Test fun signInAndFarmSelectionStayOnConnectionFlow() {
        assertFalse(shouldShowHome(ConnectionUiState(), false, false, false, false))
        assertFalse(shouldShowHome(ConnectionUiState(page = Page.FARMS), false, false, false, false))
        assertFalse(shouldShowHome(ConnectionUiState(page = Page.ACCOUNT, account = account,
            webUrl = "https://example.invalid/callback"), false, false, false, false))
        assertTrue(shouldShowHome(ConnectionUiState(), true, false, false, false))
    }

    @Test fun explicitDiagnosticsAndBusinessScreensTakePrecedence() {
        val state = ConnectionUiState(page = Page.ACCOUNT, account = account)
        assertFalse(shouldShowHome(state, false, true, false, false))
        assertFalse(shouldShowHome(state, false, false, true, false))
        assertFalse(shouldShowHome(state, false, false, false, true))
    }

    @Test fun signedOutPreviewDoesNotBlockHomeAfterLaterLogin() {
        assertFalse(openIntegrationAfterHomeExit(ConnectionUiState()))
        val accountState = ConnectionUiState(page = Page.ACCOUNT, account = account)
        assertTrue(shouldShowHome(accountState, false,
            integrationOpen = openIntegrationAfterHomeExit(ConnectionUiState()),
            businessOpen = false, devicesOpen = false))
        assertTrue(openIntegrationAfterHomeExit(accountState))
    }
}
