package com.sentri.nativeapp.pigs

import org.json.JSONArray
import org.json.JSONObject

/** Full profiles use separate snapshot kinds; a basic PigDetail can never satisfy this read. */
class PigProfileReads(private val remote: PigProfileRemote, cache: SnapshotCache, check: (ScopeLease) -> Unit,
    now: () -> Long = System::currentTimeMillis) {
    private val reads = ScopedSnapshotReads(cache, check, now)
    suspend fun page(lease: ScopeLease, request: PigProfileRequest, cacheOnly: Boolean = false): CachedResult<PigProfilePage> =
        reads.read(lease, "pig_profile_page", PigProfileCodec.key(request), cacheOnly, { remote.page(lease, request) },
            PigProfileCodec::encodePage, PigProfileCodec::decodePage, { PigProfileValidation.page(lease.scope, request, it) })
    suspend fun completeUnitRoster(lease: ScopeLease, unitId: String,
        cacheOnly: Boolean = false): CachedResult<UnitPigProfileRoster> {
        require(unitId.isNotBlank())
        fun validate(roster: UnitPigProfileRoster) {
            require(roster.unitId == unitId && roster.total == roster.pigs.size && roster.total <= 10_000)
            require(roster.pigs.map(PigProfile::id).distinct().size == roster.total)
            roster.pigs.forEach { pig ->
                PigProfileValidation.profile(lease.scope, pig)
                require(pig.location?.unitId == unitId && pig.lifecycle == PigLifecycle.ACTIVE.name)
            }
        }
        return reads.read(lease, "pig_profile_unit_complete", unitId, cacheOnly, {
            val pigs = mutableListOf<PigProfile>()
            var total: Int? = null
            do {
                val request = PigProfileRequest(PigFilter(pageSize = 100),
                    lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY,
                    offset = pigs.size, unitId = unitId)
                val page = remote.page(lease, request)
                PigProfileValidation.page(lease.scope, request, page)
                val expected = requireNotNull(page.total)
                require(expected <= 10_000 && (total == null || expected == total))
                total = expected
                require(page.pigs.isNotEmpty() || pigs.size == expected)
                pigs += page.pigs
                require(pigs.size <= expected)
            } while (pigs.size < requireNotNull(total))
            UnitPigProfileRoster(unitId, pigs, requireNotNull(total))
        }, { roster -> JSONObject().put("format", 1).put("unitId", roster.unitId)
            .put("total", roster.total)
            .put("pigs", JSONArray(roster.pigs.map(PigProfileCodec::encodeDetail))).toString()
        }, { encoded -> JSONObject(encoded).let { root ->
            require(root.keys().asSequence().toSet() == setOf("format", "unitId", "total", "pigs"))
            require(root.getInt("format") == 1)
            val pigs = root.getJSONArray("pigs")
            UnitPigProfileRoster(root.getString("unitId"),
                (0 until pigs.length()).map { index ->
                    requireNotNull(PigProfileCodec.decodeDetail(pigs.getString(index)))
                }, root.getInt("total"))
        } }, ::validate)
    }
    suspend fun detail(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<PigProfile?> {
        require(id.isNotBlank())
        val request = PigProfileRequest(filter = PigFilter(pageSize = 1), ids = setOf(id), includeCount = false)
        return reads.read(lease, "pig_profile_detail", id, cacheOnly, {
            val page = remote.page(lease, request)
            PigProfileValidation.page(lease.scope, request, page)
            page.pigs.singleOrNull()
        }, PigProfileCodec::encodeDetail, PigProfileCodec::decodeDetail, { value ->
            value?.let { require(it.id == id); PigProfileValidation.profile(lease.scope, it) }
        })
    }
    suspend fun exactEarCode(lease: ScopeLease, code: String,
        cacheOnly: Boolean = false): CachedResult<List<PigProfile>> {
        require(code.isNotBlank())
        val request = PigProfileRequest(PigFilter(search = code, pageSize = 2),
            lifecycles = setOf(PigLifecycle.ACTIVE), includeCount = false)
        return reads.read(lease, "pig_profile_scan", PigProfileCodec.key(request), cacheOnly,
            { remote.exactEarCode(lease, code).let { PigProfilePage(request, it, null) } },
            PigProfileCodec::encodePage, PigProfileCodec::decodePage, { page ->
                PigProfileValidation.page(lease.scope, request, page)
                require(page.pigs.all { it.lifecycle == PigLifecycle.ACTIVE.name &&
                    code in listOf(it.earTag, it.earNotch, it.extendedEarTag) })
            }).let { result -> CachedResult(result.value.pigs, result.source,
                result.fetchedAt, result.refreshFailure) }
    }
}
object PigProfileValidation {
    fun page(scope: DataScope, request: PigProfileRequest, value: PigProfilePage) {
        require(value.request == request && value.pigs.size <= request.filter.pageSize)
        require(if (request.includeCount) value.total != null && value.total >= 0 else value.total == null)
        require(value.pigs.map { it.id }.distinct().size == value.pigs.size)
        require(value.pigs.all { request.ids == null || it.id in request.ids })
        value.pigs.forEach {
            profile(scope, it)
            require(request.unitId == null || it.location?.unitId == request.unitId)
            require(request.filter.penLocation == null || it.location?.penLocation == request.filter.penLocation)
            require(request.roles.isEmpty() || it.role in request.roles.map { role -> role.name })
            require(request.lifecycles.isEmpty() || it.lifecycle in request.lifecycles.map { lifecycle -> lifecycle.name })
            require(request.batchId == null || it.currentBatchId == request.batchId)
        }
    }
    fun profile(scope: DataScope, value: PigProfile) {
        require(value.id.isNotBlank() && value.farmId == scope.farmId)
        require(value.health == null || value.health.farmId == scope.farmId)
        require(value.semen == null || (value.semen.farmId == scope.farmId && value.semen.boarId == value.id))
        require(value.batch == null || value.currentBatchId == null || value.batch.id == value.currentBatchId)
        require(value.batch?.farmId == null || value.batch.farmId == scope.farmId)
        require(value.health?.prescriptionCount?.let { it >= 0 } != false)
        require(value.health?.diseases?.all { it.detail == null || it.detail.id == it.id } != false)
        require(value.health?.symptoms?.all { it.detail == null || it.detail.id == it.id } != false)
    }
}
