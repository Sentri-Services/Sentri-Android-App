package com.sentri.nativeapp.home

import com.sentri.nativeapp.network.TimestampInstant
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.vaccination.VaccinationTask
import java.math.BigDecimal

data class HomeScopedVaccination(val task: VaccinationTask, val pigUnitIds: Map<String, String>,
    val sectionIds: Set<String>) {
    val unitIds: Set<String> get() = pigUnitIds.values.toSet()
    fun targetCount(unitId: String?): Int = if (unitId == null) pigUnitIds.size
        else pigUnitIds.count { it.value == unitId }
}

data class HomeVaccinationScope(val visible: List<HomeScopedVaccination>,
    val withoutReliableScope: List<String>, val notYetStarted: List<String>) {
    fun inSection(id: String) = visible.filter { id in it.sectionIds }
    fun inUnit(id: String) = visible.filter { id in it.unitIds }
}

/** A task is visible only when every target pig can be assigned to one known unit. */
object HomeVaccinationScopeProjector {
    fun project(tasks: List<VaccinationTask>, places: HomePlaces, nowMillis: Long): HomeVaccinationScope {
        val units = places.sections.flatMap(HomePlaceSection::units)
        val sectionByUnit = places.sections.flatMap { section -> section.units.map { it.id to section.id } }.toMap()
        val now = BigDecimal.valueOf(nowMillis).movePointLeft(3)
        val visible = mutableListOf<HomeScopedVaccination>()
        val withoutScope = mutableListOf<String>()
        val notStarted = mutableListOf<String>()
        tasks.forEach { task ->
            if (task.status !in setOf("PENDING", "IN_PROGRESS") || task.isDeleted) return@forEach
            if (task.status == "PENDING" && (task.scheduledAt == null ||
                    TimestampInstant.seconds(task.scheduledAt) > now)) {
                notStarted += task.id
                return@forEach
            }
            val targets = task.targets.mapNotNull { target -> target.pig?.let { target.targetId to it } }
                .distinctBy { it.first }
            if (targets.size != task.targetCount || targets.isEmpty()) {
                withoutScope += task.id
                return@forEach
            }
            val matched = targets.map { (id, pig) ->
                val byRelation = pig.location?.unitLocation?.let { code ->
                    units.singleOrNull { it.locationCode == code }
                }
                val byCode = pig.locationCode?.let { code -> matchUnit(code, units) }
                val unit = if (byRelation != null && byCode != null && byRelation.id != byCode.id) null
                    else byRelation ?: byCode
                id to unit
            }
            if (matched.any { it.second == null }) {
                withoutScope += task.id
                return@forEach
            }
            val pigUnits = matched.associate { (id, unit) -> id to requireNotNull(unit).id }
            val sections = pigUnits.values.mapNotNull(sectionByUnit::get).toSet()
            if (sections.isEmpty()) withoutScope += task.id
            else visible += HomeScopedVaccination(task, pigUnits, sections)
        }
        return HomeVaccinationScope(visible, withoutScope, notStarted)
    }

    private fun matchUnit(locationCode: String, units: List<FarmUnit>): FarmUnit? {
        val matches = units.filter { locationCode == it.locationCode || locationCode.startsWith("${it.locationCode}-") }
            .sortedByDescending { it.locationCode.length }
        val best = matches.firstOrNull() ?: return null
        return best.takeIf { matches.count { it.locationCode.length == best.locationCode.length } == 1 }
    }
}
