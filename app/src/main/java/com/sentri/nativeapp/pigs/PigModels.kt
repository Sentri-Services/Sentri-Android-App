package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.network.JsonValue
import java.math.BigDecimal

data class DataScope(val environment: String, val oauthId: String, val userId: String, val farmId: String) {
    init { require(listOf(environment, oauthId, userId, farmId).all { it.isNotBlank() }) }
}
data class ScopeLease(val scope: DataScope, val revision: Long)
data class FarmUnit(
    val id: String, val farmId: String, val nameCn: String, val nameEn: String,
    val locationCode: String, val areaType: String,
    val sectionId: String? = null, val sectionCode: String? = null,
    val sectionNameCn: String? = null, val sectionNameEn: String? = null,
    val zoneCode: String? = null, val zoneNameCn: String? = null, val zoneNameEn: String? = null,
)
data class FarmPen(val id: Int, val farmId: String, val unitId: String, val locationCode: String?, val number: Int)
data class Locations(val units: List<FarmUnit>, val pens: List<FarmPen>)
data class NamedReference(val id: String, val nameCn: String, val nameEn: String)
data class Pig(
    val id: String, val farmId: String, val earTag: String?, val earNotch: String?, val locationCode: String?,
    val role: String, val sex: String?, val lifecycle: String, val productionStatus: String?, val parity: Int?,
    val weight: BigDecimal?, val birthday: String?, val entryDate: String?, val remark: String?,
    val batchId: String?, val batchSn: String?, val unitId: String?, val penLocation: String?,
    val triage: String?, val temperature: BigDecimal?, val symptoms: List<NamedReference>, val diseases: List<NamedReference>,
) { val label: String get() = earTag?.takeIf(String::isNotBlank) ?: earNotch?.takeIf(String::isNotBlank) ?: id }
data class HealthRecord(val id: Int, val occurredAt: String?, val event: String, val remark: String?, val symptoms: JsonValue?, val diseases: JsonValue?)
data class PigDetail(val pig: Pig, val deathImages: JsonValue?, val healthRecords: List<HealthRecord>)
data class PigPage(val pigs: List<Pig>, val total: Int, val offset: Int, val limit: Int) {
    val hasNext: Boolean get() = offset + pigs.size < total
}
data class PigFilter(val unitCode: String = "", val penLocation: String? = null, val search: String = "", val pageSize: Int = 20) {
    init { require(pageSize in 1..100) }
    // Length prefixes preserve exact case/whitespace and avoid collisions for delimiter characters.
    fun cacheKey(offset: Int): String = listOf(unitCode, penLocation, search, pageSize.toString(), offset.toString())
        .joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }
}
data class BrowsePosition(val filter: PigFilter, val offset: Int, val detailId: String?, val cacheOnly: Boolean)
enum class DataSource { NETWORK, CACHE }
data class CachedResult<T>(val value: T, val source: DataSource, val fetchedAt: Long, val refreshFailure: Exception? = null)
class CacheMiss : Exception("No cached data for this request")
class CacheInvalid : Exception("Cached data is invalid")
interface PigRemote {
    suspend fun locations(scope: DataScope): Locations
    suspend fun page(scope: DataScope, filter: PigFilter, offset: Int): PigPage
    suspend fun detail(scope: DataScope, id: String): PigDetail?
}
