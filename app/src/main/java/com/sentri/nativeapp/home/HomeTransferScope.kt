package com.sentri.nativeapp.home

import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.FarmUnit
import java.math.BigDecimal

data class HomeScopedTransfer(val task: HomeTransferTask, val fromUnitIds: Set<String>,
    val toUnitIds: Set<String>, val sectionIds: Set<String>) {
    val unitIds: Set<String> get() = fromUnitIds + toUnitIds
    fun direction(unitId: String): HomeTransferDirection = when {
        unitId in fromUnitIds && unitId in toUnitIds -> HomeTransferDirection.BOTH
        unitId in fromUnitIds -> HomeTransferDirection.OUT
        else -> HomeTransferDirection.IN
    }
}

enum class HomeTransferDirection { OUT, IN, BOTH }

data class HomeTransferScope(val visible: List<HomeScopedTransfer>, val withoutReliableScope: List<String>,
    val notYetStarted: List<String>) {
    fun inSection(id: String) = visible.filter { id in it.sectionIds }
    fun inUnit(id: String) = visible.filter { id in it.unitIds }
}

/** Transfer units are explicit codes; a prefix or a pig's later location is not a task route. */
object HomeTransferScopeProjector {
    fun project(tasks: List<HomeTransferTask>, places: HomePlaces, nowMillis: Long): HomeTransferScope {
        val allUnits = places.sections.flatMap(HomePlaceSection::units)
        val byCode = allUnits.groupBy(FarmUnit::locationCode)
        val sectionByUnit = places.sections.flatMap { section -> section.units.map { it.id to section.id } }.toMap()
        val now = BigDecimal.valueOf(nowMillis).movePointLeft(3)
        val visible = mutableListOf<HomeScopedTransfer>()
        val withoutScope = mutableListOf<String>()
        val notStarted = mutableListOf<String>()
        tasks.forEach { task ->
            if (task.status == "PLANNED" && (task.plannedAt == null ||
                    TimestampInstant.seconds(task.plannedAt) > now)) {
                notStarted += task.id
                return@forEach
            }
            val from = task.fromUnitCodes.map { byCode[it]?.singleOrNull() }
            val to = task.toUnitCodes.map { byCode[it]?.singleOrNull() }
            if (from.any { it == null } || to.any { it == null } || from.isEmpty() && to.isEmpty()) {
                withoutScope += task.id
                return@forEach
            }
            val fromIds = from.filterNotNull().map(FarmUnit::id).toSet()
            val toIds = to.filterNotNull().map(FarmUnit::id).toSet()
            val sections = (fromIds + toIds).mapNotNull(sectionByUnit::get).toSet()
            if (sections.isEmpty()) withoutScope += task.id
            else visible += HomeScopedTransfer(task, fromIds, toIds, sections)
        }
        return HomeTransferScope(visible, withoutScope, notStarted)
    }
}
