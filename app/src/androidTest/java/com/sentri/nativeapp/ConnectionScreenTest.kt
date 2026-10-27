package com.sentri.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.ui.ConnectionScreen
import com.sentri.nativeapp.ui.ConnectionUiState
import com.sentri.nativeapp.ui.Page
import com.sentri.nativeapp.network.AccountContext
import com.sentri.nativeapp.network.Identity
import com.sentri.nativeapp.network.Farm
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ConnectionScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun signedOutScreenExposesLoginAction() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        var clicked = false
        compose.setContent {
            MaterialTheme {
                ConnectionScreen(ConnectionUiState(), AppConfig.current(), { clicked = true }, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.signed_out)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.login)).performClick()
        assertTrue(clicked)
    }
    @Test fun offlineAccountIsLabelledAsCacheInsteadOfSuccessfulNetworkQuery() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val account = AccountContext(Identity("u", "name", null, "user", emptyList()), Farm("farm", "Farm"), cachedAtMillis = 1)
        compose.setContent {
            MaterialTheme {
                ConnectionScreen(ConnectionUiState(page = Page.ACCOUNT, account = account), AppConfig.current(), {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText(context.getString(R.string.cached_account)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.query_success)).assertDoesNotExist()
    }
}
