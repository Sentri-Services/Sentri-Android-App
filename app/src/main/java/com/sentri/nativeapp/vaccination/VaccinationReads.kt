package com.sentri.nativeapp.vaccination

import com.sentri.nativeapp.pigs.*

/** Full history and the old active/today prefetch use distinct scoped keys. */
class VaccinationReads(private val remote: VaccinationReadRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun page(lease: ScopeLease, request: VaccinationPageRequest,
        cacheOnly: Boolean = false): CachedResult<VaccinationPage> =
        snapshots.read(lease, "vaccination_page", VaccinationReadCodec.key(request), cacheOnly,
            { remote.page(lease, request) }, VaccinationReadCodec::encodePage, VaccinationReadCodec::decodePage) {
            VaccinationReadValidation.page(lease.scope.farmId, request, it)
        }
    suspend fun detail(lease: ScopeLease, id: String,
        cacheOnly: Boolean = false): CachedResult<VaccinationTask?> {
        require(id.isNotBlank())
        return snapshots.read(lease, "vaccination_detail", id, cacheOnly,
            { remote.detail(lease, id) }, VaccinationReadCodec::encodeDetail, VaccinationReadCodec::decodeDetail) {
            it?.let { task -> require(task.id == id); VaccinationReadValidation.task(lease.scope.farmId, task) }
        }
    }
}
