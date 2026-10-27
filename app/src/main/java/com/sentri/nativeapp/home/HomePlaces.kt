package com.sentri.nativeapp.home

import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.Locations
import com.sentri.nativeapp.pigs.PigReads
import com.sentri.nativeapp.pigs.ScopeLease

/** A backend section is identified by its ID, never by a preview stage or a displayed name. */
data class HomePlaceSection(
    val id: String,
    val locationCode: String,
    val nameCn: String,
    val nameEn: String,
    val zoneNameCn: String,
    val zoneNameEn: String,
    val units: List<FarmUnit>,
)

data class HomePlaces(val sections: List<HomePlaceSection>, val unitsMissingSection: List<FarmUnit>) {
    companion object {
        fun from(locations: Locations): HomePlaces {
            val complete = locations.units.partition { unit ->
                listOf(unit.sectionId, unit.sectionCode, unit.sectionNameCn, unit.sectionNameEn,
                    unit.zoneCode, unit.zoneNameCn, unit.zoneNameEn).all { !it.isNullOrBlank() }
            }
            val sections = complete.first.groupBy { it.sectionId!! }.map { (id, units) ->
                val first = units.first()
                require(units.all {
                    it.sectionCode == first.sectionCode && it.zoneCode == first.zoneCode &&
                        it.sectionNameCn == first.sectionNameCn && it.sectionNameEn == first.sectionNameEn &&
                        it.zoneNameCn == first.zoneNameCn && it.zoneNameEn == first.zoneNameEn
                }) { "Inconsistent section metadata" }
                HomePlaceSection(
                    id, "${first.zoneCode}-${first.sectionCode}", first.sectionNameCn!!, first.sectionNameEn!!,
                    first.zoneNameCn!!, first.zoneNameEn!!, units.sortedWith(compareBy({ it.locationCode }, { it.id })),
                )
            }.sortedWith(compareBy({ it.locationCode }, { it.id }))
            return HomePlaces(sections, complete.second)
        }
    }
}

interface HomePlaceReads {
    suspend fun load(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<HomePlaces>
}

class HomePlacesReader(private val pigs: PigReads) : HomePlaceReads {
    override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<HomePlaces> {
        val result = pigs.locations(lease, cacheOnly)
        return CachedResult(HomePlaces.from(result.value), result.source, result.fetchedAt, result.refreshFailure)
    }
}
