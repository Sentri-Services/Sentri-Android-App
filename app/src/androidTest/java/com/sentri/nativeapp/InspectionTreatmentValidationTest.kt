package com.sentri.nativeapp

import com.sentri.nativeapp.auth.AuthGateway
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.LoginRequest
import com.sentri.nativeapp.auth.Session
import com.sentri.nativeapp.auth.SessionStore
import com.sentri.nativeapp.inspection.InspectionWalkViewModel
import com.sentri.nativeapp.inspection.TreatmentExclusionReason
import com.sentri.nativeapp.inspection.TreatmentSelectionProblem
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class InspectionTreatmentValidationTest {
    private val scope = PigProfileFixture.scope
    private val unit = FarmUnit("unit", scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
    private val auth = AuthRepository(object : SessionStore {
        private var value: Session? = Session("{}", "token", Long.MAX_VALUE, scope.oauthId, scope.farmId)
        override fun readSession() = value
        override fun saveSession(session: Session?) { value = session }
        override fun readPendingLogin(): LoginRequest? = null
        override fun savePendingLogin(request: LoginRequest?) { }
    }, object : AuthGateway {
        override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
        override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
        override suspend fun refresh(session: Session): Session = session
        override fun logoutUrl(session: Session): String? = null
    })
    private val gate = ScopeGate(scope.environment, auth)
    private val lease = ScopeLease(scope, auth.contextRevision)
    private val cache = object : SnapshotCache {
        private val rows = mutableMapOf<Triple<DataScope, String, String>, StoredSnapshot>()
        override fun read(scope: DataScope, kind: String, key: String) = rows[Triple(scope, kind, key)]
        override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) {
            rows[Triple(scope, kind, key)] = value
        }
        override fun readAccount(environment: String, oauthId: String, farmId: String,
            language: String?): StoredAccount? = null
        override fun clear(scope: DataScope) { rows.keys.removeAll { it.first == scope } }
    }

    @Test fun currentServerBatchExcludesMovedPigAndOfflineCacheCannotReauthorizePreview() = runBlocking {
        val original = PigProfileFixture.pig
        val second = original.copy(id = "second", earNotch = "Notch:B",
            semen = original.semen?.copy(boarId = "second"))
        var failNetwork = false
        val requests = mutableListOf<PigProfileRequest>()
        val remote = object : PigProfileRemote {
            override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
                requests += request
                if (failNetwork) throw ApiFailure.Network()
                val pigs = if (request.ids == null) listOf(original, second) else listOf(
                    original.copy(earNotch = "Latest:A"),
                    second.copy(location = second.location!!.copy(unitId = "other")))
                return PigProfilePage(request, pigs, if (request.includeCount) pigs.size else null)
            }
        }
        val vm = InspectionWalkViewModel(PigProfileReads(remote, cache, gate::check), gate)
        try {
            vm.open(lease, unit)
            withTimeout(5_000) { vm.state.first { it.page != null && !it.loading } }
            vm.togglePigSelection(original.id)
            vm.togglePigSelection(second.id)
            vm.validateTreatmentSelection()
            val checked = withTimeout(5_000) { vm.state.first { it.treatmentValidation != null } }
            assertEquals(listOf(original.id), checked.treatmentValidation!!.eligible.map { it.id })
            assertEquals("Latest:A", checked.treatmentValidation!!.eligible.single().earNotch)
            assertEquals(listOf(TreatmentExclusionReason.MOVED),
                checked.treatmentValidation!!.excluded.map { it.reason })
            assertEquals(setOf(original.id, second.id), requests.last().ids)
            assertFalse(requests.last().includeCount)

            failNetwork = true
            vm.validateTreatmentSelection()
            val offline = withTimeout(5_000) { vm.state.first { it.treatmentValidationProblem != null } }
            assertEquals(TreatmentSelectionProblem.OFFLINE, offline.treatmentValidationProblem)
            assertNull(offline.treatmentValidation)
            assertEquals(setOf(original.id, second.id), offline.selectedPigs.keys)
        } finally { vm.clear() }
    }
}
