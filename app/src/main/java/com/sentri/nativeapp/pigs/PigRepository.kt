package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface PigReads {
    suspend fun locations(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<Locations>
    suspend fun page(lease: ScopeLease, filter: PigFilter, offset: Int, cacheOnly: Boolean = false): CachedResult<PigPage>
    suspend fun detail(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<PigDetail?>
    suspend fun restore(lease: ScopeLease): BrowsePosition?
    suspend fun remember(lease: ScopeLease, position: BrowsePosition)
    suspend fun clear(lease: ScopeLease)
}
class PigRepository(private val remote: PigRemote, private val cache: SnapshotCache, private val check: (ScopeLease) -> Unit, private val now: () -> Long = System::currentTimeMillis) : PigReads {
    private suspend fun <T> load(lease: ScopeLease, kind: String, key: String, cacheOnly: Boolean,
        decode: (String) -> T, encode: (T) -> String, validate: (T) -> Unit, fetch: suspend () -> T): CachedResult<T> = withContext(Dispatchers.IO) {
        check(lease)
        fun cached(failure: Exception? = null): CachedResult<T> {
            val saved = cache.read(lease.scope, kind, key) ?: throw CacheMiss()
            val value = try { decode(saved.payload).also(validate) } catch (_: Exception) { throw CacheInvalid() }
            check(lease)
            return CachedResult(value, DataSource.CACHE, saved.fetchedAt, failure)
        }
        if (cacheOnly) return@withContext cached()
        val value = try { fetch().also { check(lease); validate(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            check(lease)
            if (error !is ApiFailure.Network && error !is AuthUnavailable) throw error
            return@withContext try { cached(error) } catch (_: CacheMiss) { throw error }
        }
        val at = now()
        check(lease)
        cache.write(lease.scope, kind, key, StoredSnapshot(encode(value), at))
        check(lease)
        CachedResult(value, DataSource.NETWORK, at)
    }
    override suspend fun locations(lease: ScopeLease, cacheOnly: Boolean) = load(lease, "locations", "all", cacheOnly, SnapshotCodec::locations, SnapshotCodec::locations,
        { data -> require(data.units.all { it.farmId == lease.scope.farmId } && data.pens.all { it.farmId == lease.scope.farmId }) }) { remote.locations(lease.scope) }
    override suspend fun page(lease: ScopeLease, filter: PigFilter, offset: Int, cacheOnly: Boolean): CachedResult<PigPage> {
        require(offset >= 0)
        return load(lease, "page", filter.cacheKey(offset), cacheOnly, SnapshotCodec::page, SnapshotCodec::page,
            { data -> require(data.offset == offset && data.limit == filter.pageSize && data.total >= 0 && data.pigs.size <= filter.pageSize && data.pigs.all { it.farmId == lease.scope.farmId } && data.pigs.map { it.id }.distinct().size == data.pigs.size) }) { remote.page(lease.scope, filter, offset) }
    }
    override suspend fun detail(lease: ScopeLease, id: String, cacheOnly: Boolean): CachedResult<PigDetail?> {
        require(id.isNotBlank())
        return load(lease, "detail", id, cacheOnly, SnapshotCodec::detail, SnapshotCodec::detail,
            { data -> require(data == null || (data.pig.id == id && data.pig.farmId == lease.scope.farmId)) }) { remote.detail(lease.scope, id) }
    }
    override suspend fun restore(lease: ScopeLease): BrowsePosition? = withContext(Dispatchers.IO) {
        check(lease)
        val value = cache.read(lease.scope, "browse", "last")?.let {
            try { SnapshotCodec.browse(it.payload) } catch (_: Exception) { throw CacheInvalid() }
        }
        check(lease); value
    }
    override suspend fun remember(lease: ScopeLease, position: BrowsePosition) = withContext(Dispatchers.IO) {
        check(lease); cache.write(lease.scope, "browse", "last", StoredSnapshot(SnapshotCodec.browse(position), now())); check(lease)
    }
    override suspend fun clear(lease: ScopeLease) = withContext(Dispatchers.IO) { check(lease); cache.clear(lease.scope); check(lease) }
}
