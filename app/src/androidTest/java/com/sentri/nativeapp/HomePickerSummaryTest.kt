package com.sentri.nativeapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.ConnectedHomeScreen
import com.sentri.nativeapp.home.HomeLiveState
import com.sentri.nativeapp.home.HomePickerSummaryViewModel
import com.sentri.nativeapp.home.HomePlaces
import com.sentri.nativeapp.home.HomeUnitStock
import com.sentri.nativeapp.home.HomeUnitStockReads
import com.sentri.nativeapp.home.HomeUnitStockRemote
import com.sentri.nativeapp.inspection.UnitCheckInHistoryReads
import com.sentri.nativeapp.inspection.UnitCheckInHistoryRemote
import com.sentri.nativeapp.inspection.UnitCheckInSummary
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SnapshotCache
import com.sentri.nativeapp.pigs.StoredAccount
import com.sentri.nativeapp.pigs.StoredSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ConcurrentHashMap

@RunWith(AndroidJUnit4::class)
class HomePickerSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unitPickerKeepsEachReadIndependentAndLabelsOldSnapshot() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val units = listOf("7", "8").map { number ->
            FarmUnit("unit-$number", "farm", "${number}单元", "Unit $number", "Z-A-$number",
                "AnimalHousing", "section-a", "A", "怀孕舍", "Gestation", "Z", "生产区", "Production zone")
        }
        val lease = ScopeLease(DataScope("dev", "oauth", "user", "farm"), 1)
        var failUnit8 = true
        var offline = false
        val cache = MemorySnapshots()
        val stock = HomeUnitStockReads(HomeUnitStockRemote { _, id ->
            if (offline || (id == "unit-8" && failUnit8)) throw ApiFailure.Network()
            HomeUnitStock(id, if (id == "unit-7") 6 else 3, 1)
        }, cache, {}, { 10 })
        val checks = UnitCheckInHistoryReads(UnitCheckInHistoryRemote { _, code ->
            if (offline) throw ApiFailure.Network()
            UnitCheckInSummary("check-$code", "farm", code, "2026-10-21T09:41:00+08:00", "Worker")
        }, cache, {}, { 10 })
        val picker = HomePickerSummaryViewModel(stock, checks, {})
        val places = HomePlaces.from(Locations(units, emptyList()))
        var state by mutableStateOf(HomeLiveState(lease = lease,
            places = CachedResult(places, DataSource.NETWORK, 1),
            production = CachedResult(emptyList(), DataSource.NETWORK, 1),
            vaccination = CachedResult(emptyList(), DataSource.NETWORK, 1),
            transfer = CachedResult(emptyList(), DataSource.NETWORK, 1)))
        compose.setContent { SentriAppTheme { ConnectedHomeScreen(state, onRefresh = {}, onExit = {},
            pickerSummaries = picker) } }
        compose.onNodeWithText(context.getString(R.string.home_choose_unit)).performClick()
        compose.waitUntil(10_000) { picker.state.value.units.size == 2 &&
            picker.state.value.units.values.all { !it.stockLoading && !it.checkInLoading } }
        assertEquals(6, picker.state.value.units.getValue("unit-7").stock?.value?.pigCount)
        assertEquals(true, picker.state.value.units.getValue("unit-8").stockFailed)
        assertEquals(false, picker.state.value.units.getValue("unit-8").checkInFailed)
        compose.onNodeWithText(context.getString(R.string.connected_picker_stock_count, 6),
            substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.connected_unit_stock_unavailable),
            substring = true).assertIsDisplayed()

        failUnit8 = false
        compose.onNodeWithText(context.getString(R.string.connected_unit_retry)).performClick()
        compose.waitUntil(10_000) { picker.state.value.units["unit-8"]?.stock?.value?.pigCount == 3 }
        compose.onNodeWithText(context.getString(R.string.connected_picker_stock_count, 3),
            substring = true).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.home_back)).performClick()
        val sectionName = if (context.resources.configuration.locales[0].language == "zh")
            "怀孕舍" else "Gestation"
        compose.onNodeWithText(sectionName).performClick()
        compose.onNodeWithText(context.getString(R.string.connected_picker_stock_count, 9),
            substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription(context.getString(R.string.home_back)).performClick()
        offline = true
        compose.onNodeWithText(sectionName).performClick()
        compose.waitUntil(10_000) { picker.state.value.units.size == 2 &&
            picker.state.value.units.values.all {
            it.stock?.source == DataSource.CACHE && it.lastCheckIn?.source == DataSource.CACHE
        } }
        compose.onNodeWithText(context.getString(R.string.connected_offline_snapshot)).assertIsDisplayed()
        picker.clear()
    }

    @Test fun lateResultFromOldFarmCannotReplaceNewFarmSummary() {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        var currentFarm = "old-farm"
        val stock = HomeUnitStockReads(HomeUnitStockRemote { lease, id ->
            if (lease.scope.farmId == "old-farm") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await(); returned.complete(Unit) }
                HomeUnitStock(id, 99, 1)
            } else HomeUnitStock(id, 3, 1)
        }, MemorySnapshots(), {}, { 10 })
        val checks = UnitCheckInHistoryReads(UnitCheckInHistoryRemote { _, _ -> null },
            MemorySnapshots(), {}, { 10 })
        val picker = HomePickerSummaryViewModel(stock, checks,
            { lease -> if (lease.scope.farmId != currentFarm) throw RequestContextChanged() })
        val oldLease = ScopeLease(DataScope("dev", "oauth", "user", "old-farm"), 1)
        val newLease = ScopeLease(oldLease.scope.copy(farmId = "new-farm"), 2)
        val oldUnit = FarmUnit("unit", "old-farm", "旧单元", "Old unit", "Z-A-1", "AnimalHousing")
        val newUnit = oldUnit.copy(farmId = "new-farm", nameCn = "新单元", nameEn = "New unit")
        compose.setContent { Text("Picker scope") }
        compose.runOnIdle { picker.open(oldLease, listOf(oldUnit), 1) }
        compose.waitUntil(10_000) { started.isCompleted }
        compose.runOnIdle {
            currentFarm = "new-farm"
            picker.open(newLease, listOf(newUnit), 1)
        }
        compose.waitUntil(10_000) { picker.state.value.units["unit"]?.stock?.value?.pigCount == 3 }
        release.complete(Unit)
        compose.waitUntil(10_000) { returned.isCompleted }
        compose.waitForIdle()
        assertEquals(newLease, picker.state.value.lease)
        assertEquals(3, picker.state.value.units.getValue("unit").stock?.value?.pigCount)
        picker.clear()
    }

    private class MemorySnapshots : SnapshotCache {
        private val items = ConcurrentHashMap<Triple<DataScope, String, String>, StoredSnapshot>()
        override fun read(scope: DataScope, kind: String, key: String): StoredSnapshot? =
            items[Triple(scope, kind, key)]
        override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) {
            items[Triple(scope, kind, key)] = value
        }
        override fun readAccount(environment: String, oauthId: String, farmId: String,
            language: String?): StoredAccount? = null
        override fun clear(scope: DataScope) { items.keys.removeAll { it.first == scope } }
    }
}
