package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthGateway
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.LoginRequest
import com.sentri.nativeapp.auth.Session
import com.sentri.nativeapp.auth.SessionStore
import com.sentri.nativeapp.inspection.InspectionLens
import com.sentri.nativeapp.inspection.InspectionWalkViewModel
import com.sentri.nativeapp.inspection.visibleWalkPigs
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.PigProfile
import com.sentri.nativeapp.pigs.PigProfilePage
import com.sentri.nativeapp.pigs.PigProfileReads
import com.sentri.nativeapp.pigs.PigProfileRemote
import com.sentri.nativeapp.pigs.PigProfileRequest
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import android.net.Uri
import com.sentri.nativeapp.inspection.DeviceFaultDraft
import com.sentri.nativeapp.home.HomeUnitPenRoster
import com.sentri.nativeapp.home.HomeRegisteredPen
import com.sentri.nativeapp.home.HomePenCount
import org.junit.Test
import java.util.UUID

class InspectionHealthLensNavigationTest {
    @Test fun healthPigAndPenBeyondTheCurrentPageOpenFromTheCompleteRoster() = runBlocking {
        val scope = PigProfileFixture.scope
        val unit = FarmUnit("unit", scope.farmId, "七单元", "Unit 7", "Z-S-U", "AnimalHousing")
        val auth = AuthRepository(object : SessionStore {
            private var saved: Session? = Session("{}", "token", Long.MAX_VALUE, scope.oauthId, scope.farmId)
            override fun readSession() = saved
            override fun saveSession(session: Session?) { saved = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) = Unit
        }, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
            override suspend fun refresh(session: Session): Session = session
            override fun logoutUrl(session: Session): String? = null
        })
        val gate = ScopeGate(scope.environment, auth)
        val lease = ScopeLease(scope, auth.contextRevision)
        val sick = PigProfileFixture.pig.copy(batch = PigProfileFixture.pig.batch?.copy(farmId = scope.farmId))
        val healthy = sick.copy(id = "healthy", earNotch = "HealthyTag",
            health = sick.health!!.copy(diseases = emptyList(), symptoms = emptyList()),
            breederProductionStatus = "PREGNANT",
            currentBatchId = "other-batch", batch = sick.batch?.copy(id = "other-batch", serial = "B-2"),
            semen = sick.semen?.copy(boarId = "healthy"),
            location = sick.location?.copy(penLocation = "another-pen"))
        val penCode = requireNotNull(sick.location?.penLocation)
        val requests = mutableListOf<PigProfileRequest>()
        val remote = object : PigProfileRemote {
            override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage {
                requests += request
                val pigs = when {
                    request.ids != null -> listOf(sick).filter { it.id in request.ids }
                    request.filter.penLocation != null -> listOf(sick).filter {
                        it.location?.penLocation == request.filter.penLocation
                    }
                    request.offset == 0 -> listOf(healthy)
                    request.offset == 1 -> listOf(sick)
                    else -> emptyList()
                }
                return PigProfilePage(request, pigs, if (request.includeCount) {
                    if (request.filter.penLocation != null) pigs.size else 2
                } else null)
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "inspection-health-nav-${UUID.randomUUID()}"
        try {
            SqliteSnapshotCache(context, database).use { cache ->
                val vm = InspectionWalkViewModel(PigProfileReads(remote, cache, gate::check), gate)
                try {
                    vm.open(lease, unit)
                    withTimeout(5_000) { vm.state.first { it.page != null && !it.loading } }
                    vm.openPigDetail(sick.id)
                    vm.openPen(penCode)
                    assertNull(vm.state.value.pigDetailId)
                    assertNull(vm.state.value.penCode)

                    val stock = HomeUnitPenRoster(unit.id, 1, 0,
                        listOf(HomePenCount(penCode, 1, 1)),
                        listOf(HomeRegisteredPen(1, penCode, 1), HomeRegisteredPen(2, "empty-pen", 2)))
                    assertFalse(vm.openPenFromStock(lease.copy(scope = scope.copy(farmId = "another")), penCode, stock))
                    assertFalse(vm.openPenFromStock(lease, penCode, stock.copy(unitId = "another")))
                    assertFalse(vm.openPenFromStock(lease, "unregistered", stock))
                    assertTrue(vm.openPenFromStock(lease, "empty-pen", stock))
                    withTimeout(5_000) { vm.state.first { it.penPage != null && !it.penLoading } }
                    assertEquals("empty-pen", vm.state.value.penCode)
                    assertEquals(emptyList<PigProfile>(), vm.state.value.penPage!!.value.pigs)
                    val faultDraft = DeviceFaultDraft("Other", "Fan", "Noise",
                        listOf(Uri.parse("content://fixture/photo")))
                    vm.updateFaultDraft(lease.copy(scope = scope.copy(farmId = "another")), "empty-pen", faultDraft)
                    vm.updateFaultDraft(lease, "another-pen", faultDraft)
                    assertTrue(vm.state.value.faultDrafts.isEmpty())
                    vm.updateFaultDraft(lease, "empty-pen", faultDraft)
                    assertEquals(faultDraft, vm.state.value.faultDrafts["empty-pen"])
                    vm.closePen()
                    assertEquals(faultDraft, vm.state.value.faultDrafts["empty-pen"])

                    vm.selectLens(InspectionLens.HEALTH)
                    withTimeout(5_000) { vm.state.first { it.completeRoster != null && !it.completeRosterLoading } }
                    vm.openPigDetail(sick.id)
                    withTimeout(5_000) { vm.state.first { it.pigDetail?.value?.id == sick.id } }
                    assertEquals(sick.id, vm.state.value.pigDetailId)
                    vm.openPen(penCode)
                    withTimeout(5_000) { vm.state.first { it.penPage != null && !it.penLoading } }
                    assertEquals(penCode, vm.state.value.penCode)
                    assertEquals(listOf(sick.id), vm.state.value.penPage!!.value.pigs.map(PigProfile::id))
                    assertEquals(setOf(sick.id), requests.last { it.ids != null }.ids)
                    vm.closePen()
                    vm.selectLens(InspectionLens.ALL)
                    vm.applyFilters("MATED", "d:d")
                    assertEquals(listOf(sick.id), visibleWalkPigs(vm.state.value)?.map(PigProfile::id))
                    vm.toggleVisiblePenSelection(penCode)
                    assertEquals(setOf(sick.id), vm.state.value.selectedPigs.keys)
                    vm.applyFilters("PREGNANT", null)
                    assertEquals(listOf(healthy.id), visibleWalkPigs(vm.state.value)?.map(PigProfile::id))
                    vm.applyFilters(null, null, "batch")
                    assertEquals(listOf(sick.id), visibleWalkPigs(vm.state.value)?.map(PigProfile::id))
                    vm.open(lease, unit.copy(id = "another-unit"))
                    assertTrue(vm.state.value.faultDrafts.isEmpty())
                } finally { vm.clear() }
            }
            SqliteSnapshotCache(context, database).use { reopened ->
                val offline = InspectionWalkViewModel(PigProfileReads(object : PigProfileRemote {
                    override suspend fun page(lease: ScopeLease, request: PigProfileRequest): PigProfilePage =
                        throw ApiFailure.Network()
                }, reopened, gate::check), gate)
                try {
                    offline.open(lease, unit)
                    withTimeout(5_000) { offline.state.first { it.page != null && !it.loading } }
                    assertEquals(DataSource.CACHE, offline.state.value.page!!.source)
                    assertEquals(listOf(healthy.id), offline.state.value.page!!.value.pigs.map(PigProfile::id))
                    offline.selectLens(InspectionLens.HEALTH)
                    withTimeout(5_000) { offline.state.first { it.completeRoster != null && !it.completeRosterLoading } }
                    assertEquals(DataSource.CACHE, offline.state.value.completeRoster!!.source)
                    assertEquals(listOf(healthy.id, sick.id),
                        offline.state.value.completeRoster!!.value.pigs.map(PigProfile::id))
                    offline.openPigDetail(sick.id)
                    withTimeout(5_000) { offline.state.first { it.pigDetail != null && !it.pigDetailLoading } }
                    assertEquals(DataSource.CACHE, offline.state.value.pigDetail!!.source)
                    assertEquals(sick.id, offline.state.value.pigDetail!!.value?.id)
                    offline.openPen(penCode)
                    withTimeout(5_000) { offline.state.first { it.penPage != null && !it.penLoading } }
                    assertEquals(DataSource.CACHE, offline.state.value.penPage!!.source)
                    assertEquals(listOf(sick.id), offline.state.value.penPage!!.value.pigs.map(PigProfile::id))
                    offline.closePen()
                    offline.selectLens(InspectionLens.ALL)
                    offline.applyFilters("MATED", "d:d")
                    assertEquals(listOf(sick.id), visibleWalkPigs(offline.state.value)?.map(PigProfile::id))
                    offline.applyFilters(null, null, "other-batch")
                    assertEquals(listOf(healthy.id), visibleWalkPigs(offline.state.value)?.map(PigProfile::id))
                } finally { offline.clear() }
            }
        } finally { context.deleteDatabase(database) }
    }
}
