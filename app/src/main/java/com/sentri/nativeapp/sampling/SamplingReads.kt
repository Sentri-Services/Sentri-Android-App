package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.pigs.*

class SamplingReads(private val remote: SamplingReadRemote, cache: SnapshotCache,
    check: (ScopeLease) -> Unit, now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)
    suspend fun page(lease: ScopeLease, request: SamplingPageRequest,
        cacheOnly: Boolean = false): CachedResult<SamplingPage> =
        snapshots.read(lease, "sampling_page", SamplingReadCodec.key(request), cacheOnly,
            { remote.page(lease, request) }, SamplingReadCodec::encodePage, SamplingReadCodec::decodePage) {
            SamplingReadValidation.page(lease.scope.farmId, request, it)
        }
    suspend fun detail(lease: ScopeLease, id: String,
        cacheOnly: Boolean = false): CachedResult<SamplingTask?> {
        require(id.isNotBlank())
        return snapshots.read(lease, "sampling_detail", id, cacheOnly,
            { remote.detail(lease, id) }, SamplingReadCodec::encodeDetail, SamplingReadCodec::decodeDetail) {
            it?.let { task -> require(task.id == id); SamplingReadValidation.task(lease.scope.farmId, task) }
        }
    }
}
