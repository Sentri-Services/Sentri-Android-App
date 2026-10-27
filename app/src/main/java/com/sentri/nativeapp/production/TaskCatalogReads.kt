package com.sentri.nativeapp.production

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pages and metadata never replace all task details or evict queued commands. */
class TaskCatalogReads(private val remote: TaskCatalogRemote, private val cache: SnapshotCache, private val check: (ScopeLease) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {
    private fun validate(value: TaskMetadata, lease: ScopeLease) {
        require(value.farmId == lease.scope.farmId && value.id.isNotBlank() && value.batch.id.isNotBlank())
        require(value.batch.line == null || value.batch.line.farmId == value.farmId)
        require(value.operator == null || value.operator.id == value.operatorId)
        require(value.pigs.all { it.id.isNotBlank() } && value.pigs.map { it.id }.distinct().size == value.pigs.size)
    }
    private suspend fun <T> read(lease: ScopeLease, kind: String, key: String, cacheOnly: Boolean, fetch: suspend () -> T,
        encode: (T) -> String, decode: (String) -> T, validate: (T) -> Unit): CachedResult<T> = withContext(Dispatchers.IO) {
        check(lease)
        fun saved(failure: Exception? = null): CachedResult<T> {
            val entry = cache.read(lease.scope, kind, key) ?: throw CacheMiss()
            val value = try { decode(entry.payload).also(validate) } catch (_: Exception) { throw CacheInvalid() }
            check(lease); return CachedResult(value, DataSource.CACHE, entry.fetchedAt, failure)
        }
        if (cacheOnly) return@withContext saved()
        val value = try { fetch().also { check(lease); validate(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            check(lease)
            if (failure !is ApiFailure.Network && failure !is AuthUnavailable) throw failure
            return@withContext try { saved(failure) } catch (_: CacheMiss) { throw failure }
        }
        val at = now(); check(lease); cache.write(lease.scope, kind, key, StoredSnapshot(encode(value), at)); check(lease)
        CachedResult(value, DataSource.NETWORK, at)
    }
    suspend fun page(lease: ScopeLease, request: TaskCatalogRequest, cacheOnly: Boolean = false) = read(lease, "production_catalog_page", TaskCatalogCodecs.key(request), cacheOnly,
        { remote.page(lease, request) }, TaskCatalogCodecs::encodePage, TaskCatalogCodecs::decodePage) { value ->
        require(value.request == request && value.total >= value.tasks.size && value.tasks.size <= request.limit && value.tasks.map { it.id }.distinct().size == value.tasks.size)
        value.tasks.forEach { validate(it, lease); require(request.accepts(it)) }
    }
    suspend fun metadata(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<TaskMetadata?> {
        require(id.isNotBlank())
        return read(lease, "production_catalog_detail", id, cacheOnly, { remote.metadata(lease, id) }, TaskCatalogCodecs::encodeMetadata, TaskCatalogCodecs::decodeMetadata) { value ->
            value?.let { validate(it, lease); require(it.id == id) }
        }
    }
}
