package com.sentri.nativeapp.home

import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskMetadata
import java.math.BigDecimal

data class HomeScopedTask(val task: TaskMetadata, val unitIds: Set<String>, val sectionIds: Set<String>,
    val pigUnitIds: Map<String, String> = emptyMap())
data class HomeTaskScope(val visible: List<HomeScopedTask>, val withoutReliableScope: List<String>, val notYetStarted: List<String>) {
    fun inSection(id: String) = visible.filter { id in it.sectionIds }
    fun inUnit(id: String) = visible.filter { id in it.unitIds }
}

/** A partial pig-location match never yields a section count or card. */
object HomeTaskScopeProjector {
    fun project(tasks: List<TaskMetadata>, places: HomePlaces, nowMillis: Long): HomeTaskScope {
        val units = places.sections.flatMap(HomePlaceSection::units)
        val sectionByUnit = places.sections.flatMap { section -> section.units.map { it.id to section.id } }.toMap()
        val visible = mutableListOf<HomeScopedTask>()
        val withoutScope = mutableListOf<String>()
        val notStarted = mutableListOf<String>()
        val now = BigDecimal.valueOf(nowMillis).movePointLeft(3)
        tasks.forEach { task ->
            if (task.status == ProductionTaskStatus.SCHEDULED) {
                val start = task.plan?.startedAt
                if (start == null) {
                    notStarted += task.id
                    return@forEach
                }
                if (TimestampInstant.seconds(start) > now) {
                    notStarted += task.id
                    return@forEach
                }
            }
            val active = task.pigs.filterNot { it.disabled }
            if (active.isEmpty()) {
                withoutScope += task.id
                return@forEach
            }
            val matched = active.map { pig -> pig.id to pig.locationCode?.let { matchUnit(it, units) } }
            if (matched.any { it.second == null } || matched.map { it.first }.distinct().size != matched.size) {
                withoutScope += task.id
                return@forEach
            }
            val pigUnits = matched.associate { (pigId, unit) -> pigId to requireNotNull(unit).id }
            val ids = pigUnits.values.toSet()
            val sections = ids.mapNotNull(sectionByUnit::get).toSet()
            if (sections.isEmpty()) withoutScope += task.id else visible += HomeScopedTask(task, ids, sections, pigUnits)
        }
        return HomeTaskScope(visible, withoutScope, notStarted)
    }

    private fun matchUnit(locationCode: String, units: List<FarmUnit>): FarmUnit? {
        val matches = units.filter { locationCode == it.locationCode || locationCode.startsWith("${it.locationCode}-") }
            .sortedByDescending { it.locationCode.length }
        val best = matches.firstOrNull() ?: return null
        return best.takeIf { matches.count { it.locationCode.length == best.locationCode.length } == 1 }
    }
}
