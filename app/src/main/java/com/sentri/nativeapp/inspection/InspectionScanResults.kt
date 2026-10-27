package com.sentri.nativeapp.inspection

import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.PigLifecycle
import com.sentri.nativeapp.pigs.PigProfile

data class ScannedPig(val pig: PigProfile, val source: DataSource, val sequence: Long)
data class ScanPenGroup(val penCode: String?, val entries: List<ScannedPig>)
data class ScanUnitGroup(val unitId: String?, val pens: List<ScanPenGroup>)
enum class ScanProblem { NO_MATCH, NO_CACHED_MATCH, AMBIGUOUS, READ_FAILED }

object InspectionScanResults {
    fun add(current: List<ScannedPig>, pig: PigProfile, source: DataSource,
        sequence: Long): List<ScannedPig> =
        (current.filterNot { it.pig.id == pig.id } + ScannedPig(pig, source, sequence))
            .sortedByDescending(ScannedPig::sequence)

    /** Current unit first; within each unit the latest pen and latest read come first. */
    fun grouped(entries: List<ScannedPig>, currentUnitId: String): List<ScanUnitGroup> =
        entries.sortedByDescending(ScannedPig::sequence)
            .groupBy { it.pig.location?.unitId }
            .entries.sortedBy { if (it.key == currentUnitId) 0 else 1 }
            .map { (unitId, members) ->
                ScanUnitGroup(unitId, members.groupBy { it.pig.location?.penLocation?.takeIf(String::isNotBlank) }
                    .map { (penCode, pigs) -> ScanPenGroup(penCode, pigs) })
            }

    fun selectable(pig: PigProfile, farmId: String, unitId: String): Boolean =
        pig.farmId == farmId && pig.lifecycle == PigLifecycle.ACTIVE.name && pig.location?.unitId == unitId
}
