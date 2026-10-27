package com.sentri.nativeapp.home

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.CacheInvalid
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SnapshotCache
import com.sentri.nativeapp.pigs.StoredSnapshot
import com.sentri.nativeapp.vaccination.VaccinationReadCodec
import com.sentri.nativeapp.vaccination.VaccinationReadMode
import com.sentri.nativeapp.vaccination.VaccinationReadValidation
import com.sentri.nativeapp.vaccination.VaccinationReads
import com.sentri.nativeapp.vaccination.VaccinationPageRequest
import com.sentri.nativeapp.vaccination.VaccinationTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface HomeVaccinationReads {
    suspend fun load(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<VaccinationTask>>
}

/** Home only publishes a complete active catalog, never a partial page count. */
class HomeVaccinationCatalogReader(private val reads: VaccinationReads, private val cache: SnapshotCache,
    private val check: (ScopeLease) -> Unit, private val now: () -> Long = System::currentTimeMillis,
) : HomeVaccinationReads {
    private val kind = "home_open_vaccination"
    private val key = "all"

    override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<List<VaccinationTask>> =
        withContext(Dispatchers.IO) {
            check(lease)
            fun saved(failure: Exception? = null): CachedResult<List<VaccinationTask>> {
                val stored = cache.read(lease.scope, kind, key) ?: throw CacheMiss()
                val tasks = try { VaccinationReadCodec.decodeCatalog(stored.payload).also { validate(lease, it) } }
                    catch (_: Exception) { throw CacheInvalid() }
                check(lease)
                return CachedResult(tasks, DataSource.CACHE, stored.fetchedAt, failure)
            }
            if (cacheOnly) return@withContext saved()
            try {
                val tasks = mutableListOf<VaccinationTask>()
                val ids = mutableSetOf<String>()
                var offset = 0
                var hasMore: Boolean
                do {
                    val page = reads.page(lease, VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE,
                        offset = offset, limit = 200))
                    check(lease)
                    if (page.source == DataSource.CACHE) return@withContext try { saved(page.refreshFailure) }
                        catch (_: CacheMiss) { throw page.refreshFailure ?: CacheMiss() }
                    val value = page.value
                    if (value.rows.any { !ids.add(it.id) } || tasks.size + value.rows.size > 20_000)
                        throw ApiFailure.Protocol()
                    tasks += value.rows
                    offset += value.rows.size
                    hasMore = value.hasMore
                } while (hasMore)
                validate(lease, tasks)
                check(lease)
                val at = now()
                cache.write(lease.scope, kind, key, StoredSnapshot(VaccinationReadCodec.encodeCatalog(tasks), at))
                check(lease)
                CachedResult(tasks, DataSource.NETWORK, at)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                check(lease)
                if (failure !is ApiFailure.Network && failure !is AuthUnavailable) throw failure
                try { saved(failure) } catch (_: CacheMiss) { throw failure }
            }
        }

    private fun validate(lease: ScopeLease, tasks: List<VaccinationTask>) {
        require(tasks.map { it.id }.distinct().size == tasks.size)
        tasks.forEach {
            VaccinationReadValidation.task(lease.scope.farmId, it)
            require(it.status in setOf("PENDING", "IN_PROGRESS") && !it.isDeleted)
        }
    }
}
