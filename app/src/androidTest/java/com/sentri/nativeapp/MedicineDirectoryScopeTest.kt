package com.sentri.nativeapp

import com.sentri.nativeapp.auth.AuthGateway
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.LoginRequest
import com.sentri.nativeapp.auth.Session
import com.sentri.nativeapp.auth.SessionStore
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SnapshotCache
import com.sentri.nativeapp.pigs.StoredAccount
import com.sentri.nativeapp.pigs.StoredSnapshot
import com.sentri.nativeapp.therapy.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class MedicineDirectoryScopeTest {
    @Test fun farmSwitchClearsOldMedicinesBeforeReadingNewFarm() = runBlocking {
        val auth = AuthRepository(object : SessionStore {
            private var session: Session? = Session("{}", "token", Long.MAX_VALUE, "oauth", "farm-1")
            override fun readSession() = session
            override fun saveSession(session: Session?) { this.session = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) { }
        }, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
            override suspend fun refresh(session: Session): Session = session
            override fun logoutUrl(session: Session): String? = null
        })
        val gate = ScopeGate("dev", auth)
        val snapshots = mutableMapOf<Triple<DataScope, String, String>, StoredSnapshot>()
        val cache = object : SnapshotCache {
            override fun read(scope: DataScope, kind: String, key: String) = snapshots[Triple(scope, kind, key)]
            override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) {
                snapshots[Triple(scope, kind, key)] = value
            }
            override fun readAccount(environment: String, oauthId: String, farmId: String, language: String?): StoredAccount? = null
            override fun clear(scope: DataScope) { snapshots.keys.removeAll { it.first == scope } }
        }
        val remote = object : TherapyReadRemote {
            override suspend fun medications(lease: ScopeLease) = listOf(TherapyMedication(
                "medicine-${lease.scope.farmId}", lease.scope.farmId, "药品", "Medicine"))
            override suspend fun catalogs(lease: ScopeLease) = emptyList<TherapyMedicationCatalog>()
            override suspend fun page(lease: ScopeLease, request: TherapyPageRequest): TherapyPage = error("unused")
            override suspend fun detail(lease: ScopeLease, id: String): TherapyRow? = error("unused")
            override suspend fun treatments(lease: ScopeLease) = listOf(TherapyTreatment(
                "method-${lease.scope.farmId}", "注射", "Injection", "肌肉", "Intramuscular"))
            override suspend fun context(lease: ScopeLease, prescriptionId: String): TherapyPrescriptionContext? = error("unused")
        }
        val viewModel = MedicineDirectoryViewModel(TherapyReads(remote, cache, gate::check), gate)
        val first = ScopeLease(DataScope("dev", "oauth", "user", "farm-1"), auth.contextRevision)
        viewModel.open(first)
        withTimeout(3_000) { viewModel.state.first { it.lease == first && !it.loading && it.medications != null } }
        assertEquals("farm-1", viewModel.state.value.medications!!.value.single().farmId)
        assertEquals("method-farm-1", viewModel.state.value.treatments!!.value.single().id)

        auth.refreshForFarm("farm-2")
        val second = ScopeLease(first.scope.copy(farmId = "farm-2"), auth.contextRevision)
        viewModel.open(second)
        assertEquals(second, viewModel.state.value.lease)
        assertNull(viewModel.state.value.medications)
        assertNull(viewModel.state.value.treatments)
        withTimeout(3_000) { viewModel.state.first { it.lease == second && !it.loading && it.medications != null } }
        assertEquals("farm-2", viewModel.state.value.medications!!.value.single().farmId)
        assertEquals("method-farm-2", viewModel.state.value.treatments!!.value.single().id)
        viewModel.clear()
        assertNull(viewModel.state.value.medications)
    }
}
