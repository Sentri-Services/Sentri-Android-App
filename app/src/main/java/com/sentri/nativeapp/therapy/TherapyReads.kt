package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.pigs.*

class TherapyReads(private val remote: TherapyReadRemote, cache: SnapshotCache, check: (ScopeLease) -> Unit,
    now: () -> Long = System::currentTimeMillis) {
    private val snapshots = ScopedSnapshotReads(cache, check, now)

    suspend fun context(lease: ScopeLease, prescriptionId: String, cacheOnly: Boolean = false): CachedResult<TherapyPrescriptionContext?> {
        require(prescriptionId.isNotBlank())
        return snapshots.read(lease, "therapy_context", prescriptionId, cacheOnly, { remote.context(lease, prescriptionId) },
            TherapyReadCodec::encodeContext, TherapyReadCodec::decodeContext) {
            it?.let { value -> TherapyReadValidation.context(lease.scope.farmId, prescriptionId, value) }
        }
    }

    suspend fun page(lease: ScopeLease, request: TherapyPageRequest, cacheOnly: Boolean = false): CachedResult<TherapyPage> =
        snapshots.read(lease, "therapy_page", TherapyReadCodec.key(request), cacheOnly, { remote.page(lease, request) },
            TherapyReadCodec::encodePage, TherapyReadCodec::decodePage) {
            TherapyReadValidation.page(lease.scope.farmId, request, it)
        }

    suspend fun detail(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<TherapyRow?> {
        require(id.isNotBlank())
        return snapshots.read(lease, "therapy_detail", id, cacheOnly, { remote.detail(lease, id) },
            TherapyReadCodec::encodeDetail, TherapyReadCodec::decodeDetail) {
            it?.let { row -> require(row.id == id); TherapyReadValidation.row(lease.scope.farmId, row) }
        }
    }

    suspend fun medications(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<TherapyMedication>> =
        snapshots.read(lease, "therapy_medications", "all", cacheOnly, { remote.medications(lease) },
            TherapyReadCodec::encodeMedications, TherapyReadCodec::decodeMedications) {
            TherapyReadValidation.medications(lease.scope.farmId, it)
        }

    suspend fun catalogs(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<TherapyMedicationCatalog>> =
        snapshots.read(lease, "therapy_catalogs", "all", cacheOnly, { remote.catalogs(lease) },
            TherapyReadCodec::encodeCatalogs, TherapyReadCodec::decodeCatalogs) {
            TherapyReadValidation.catalogs(lease.scope.farmId, it)
        }

    suspend fun treatments(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<TherapyTreatment>> =
        snapshots.read(lease, "therapy_treatments", "all", cacheOnly, { remote.treatments(lease) },
            TherapyReadCodec::encodeTreatments, TherapyReadCodec::decodeTreatments) {
            TherapyReadValidation.treatments(it)
        }
}
