package com.sentri.nativeapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.ui.ConnectionScreen
import com.sentri.nativeapp.ui.ConnectionUiState
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
}
