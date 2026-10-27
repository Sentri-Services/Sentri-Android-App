package com.sentri.nativeapp.home

import com.sentri.nativeapp.production.ProductionTaskKind
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskBatch
import com.sentri.nativeapp.production.TaskMetadata
import com.sentri.nativeapp.production.TaskPlan
import com.sentri.nativeapp.vaccination.VaccinationTask
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeUnitWorkOrderTest {
    private val early = "2026-10-21T08:00:00+08:00"
    private val later = "2026-10-22T08:00:00+08:00"

    @Test fun oneListKeepsEverySourceAndPlacesStartedScheduledWorkBeforeInProgressWork() {
        val items = HomeUnitWorkOrder.order(
            production = listOf(production("running", ProductionTaskStatus.RUNNING, early),
                production("scheduled", ProductionTaskStatus.SCHEDULED, later)),
            vaccination = listOf(vaccination("vax", "PENDING", early)),
            transfer = listOf(transfer("transfer", "IN_PROGRESS", later)))

        assertEquals(listOf("vaccination:vax", "production:scheduled", "production:running",
            "transfer:transfer"), items.map(HomeUnitWorkItem::key))
    }

    @Test fun equalTimesUseSourceAndTaskIdentityForStableOrder() {
        val items = HomeUnitWorkOrder.order(
            production = listOf(production("b", ProductionTaskStatus.SCHEDULED, early),
                production("a", ProductionTaskStatus.SCHEDULED, early)),
            vaccination = listOf(vaccination("a", "PENDING", early)),
            transfer = listOf(transfer("a", "PLANNED", early)))

        assertEquals(listOf("production:a", "production:b", "transfer:a", "vaccination:a"),
            items.map(HomeUnitWorkItem::key))
    }

    @Test fun groupsFollowFirstRankedInstanceAndKeepEveryTaskIdentity() {
        val ranked = HomeUnitWorkOrder.order(
            production = listOf(production("p1", ProductionTaskStatus.RUNNING, later),
                production("p2", ProductionTaskStatus.RUNNING, later)),
            vaccination = listOf(vaccination("v1", "PENDING", early),
                vaccination("v2", "IN_PROGRESS", later)),
            transfer = listOf(transfer("t1", "IN_PROGRESS", later)))
        val groups = HomeWorkGroups.group(ranked)
        assertEquals(5, ranked.size)
        assertEquals(HomeWorkGroups.GROUP_FROM, ranked.size)
        assertEquals(listOf(HomeWorkCategory.VACCINATION, HomeWorkCategory.PRODUCTION,
            HomeWorkCategory.TRANSFER), groups.map(HomeWorkGroup::category))
        assertEquals(ranked.map(HomeUnitWorkItem::key).toSet(),
            groups.flatMap { it.items }.map(HomeUnitWorkItem::key).toSet())
        assertEquals(5, groups.sumOf { it.items.size })
    }

    private fun production(id: String, status: ProductionTaskStatus, at: String) = HomeScopedTask(
        TaskMetadata(id, "farm", ProductionTaskKind.PREGNANCY, status, null, null, null, null,
            null, null, TaskBatch("batch-$id", id, null, null, emptyList()), TaskPlan(at, 2),
            emptyList(), emptyList(), emptyList(), emptyList()),
        setOf("unit"), setOf("section"))

    private fun vaccination(id: String, status: String, at: String) = HomeScopedVaccination(
        VaccinationTask(id, "farm", "vaccine", "brand", status, at, null, null, null, null, null,
            null, "IM", null, null, "mL", 1, BigDecimal.ONE, 0, 0, 0, 1, "PLAN", null,
            null, null, false, null, null, null, emptyList()),
        mapOf("pig-$id" to "unit"), setOf("section"))

    private fun transfer(id: String, status: String, at: String) = HomeScopedTransfer(
        HomeTransferTask(id, "farm", status, "NORMAL", at, null, listOf("u"), emptyList()),
        setOf("unit"), emptySet(), setOf("section"))
}
