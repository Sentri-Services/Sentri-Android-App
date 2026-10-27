package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.ConnectedHomeScreen
import com.sentri.nativeapp.home.HomeLiveState
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.therapy.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MedicineDirectoryNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun toolboxOpensCurrentFarmMedicinePageAndReturns() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val auth = AuthRepository(object : SessionStore {
            private var session: Session? = Session("{}", "token", Long.MAX_VALUE, "oauth", "farm")
            override fun readSession() = session
            override fun saveSession(session: Session?) { this.session = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) { }
        }, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
            override suspend fun refresh(session: Session): Session = error("unused")
            override fun logoutUrl(session: Session): String? = null
        })
        val gate = ScopeGate("dev", auth)
        val cache = object : SnapshotCache {
            private val saved = mutableMapOf<Triple<DataScope, String, String>, StoredSnapshot>()
            override fun read(scope: DataScope, kind: String, key: String) = saved[Triple(scope, kind, key)]
            override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) {
                saved[Triple(scope, kind, key)] = value
            }
            override fun readAccount(environment: String, oauthId: String, farmId: String, language: String?): StoredAccount? = null
            override fun clear(scope: DataScope) { saved.keys.removeAll { it.first == scope } }
        }
        val remote = object : TherapyReadRemote {
            override suspend fun medications(lease: ScopeLease) = listOf(TherapyMedication("medicine", "farm", "阿莫西林", "Amoxicillin"))
            override suspend fun catalogs(lease: ScopeLease) = emptyList<TherapyMedicationCatalog>()
            override suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage = error("unused")
            override suspend fun detail(lease: ScopeLease, id: String): TherapyRow? = error("unused")
            override suspend fun treatments(lease: ScopeLease): List<TherapyTreatment> = error("unused")
            override suspend fun context(lease: ScopeLease, prescriptionId: String): TherapyPrescriptionContext? = error("unused")
        }
        val medicines = MedicineDirectoryViewModel(TherapyReads(remote, cache, gate::check), gate)
        val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), auth.contextRevision)
        val state = HomeLiveState(lease = lease)
        val medicineName = if (context.resources.configuration.locales[0].language == "zh") "阿莫西林" else "Amoxicillin"
        compose.setContent { SentriAppTheme {
            ConnectedHomeScreen(state, medicines = medicines, onRefresh = {}, onExit = {})
        } }
        compose.onNodeWithText(context.getString(R.string.home_tab_toolbox)).performClick()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_title)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText(medicineName).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(medicineName).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_read_only)).assertIsDisplayed()
        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        compose.onNodeWithText(context.getString(R.string.medicine_directory_title)).assertIsDisplayed()
    }
}
