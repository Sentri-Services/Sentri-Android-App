package com.sentri.nativeapp.production

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ProductionTaskSnapshot {
    val id: String
    val farmId: String
    val batchId: String
    val status: String
    val type: String
    val pigIds: List<String>
}
fun interface TaskRemote<T : ProductionTaskSnapshot> { suspend fun task(lease: ScopeLease, id: String): T? }
interface TaskCodec<T : ProductionTaskSnapshot> {
    val type: String
    fun encode(value: T?): String
    fun decode(text: String): T?
}
/** Scoped server snapshots; pending commands remain in their own durable queue. */
class ProductionTaskReads<T : ProductionTaskSnapshot>(private val remote: TaskRemote<T>, private val cache: SnapshotCache,
    private val codec: TaskCodec<T>, private val check: (ScopeLease) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun task(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<T?> = withContext(Dispatchers.IO) {
        require(id.isNotBlank()); check(lease)
        val key = "${codec.type.length}:${codec.type}${id.length}:$id"
        fun validate(value: T?) {
            require(value == null || (value.id == id && value.farmId == lease.scope.farmId && value.type == codec.type &&
                value.batchId.isNotBlank() && value.status.isNotBlank() && value.pigIds.all(String::isNotBlank) && value.pigIds.distinct().size == value.pigIds.size))
        }
        fun cached(failure: Exception? = null): CachedResult<T?> {
            val saved = cache.read(lease.scope, "production_task", key) ?: throw CacheMiss()
            val value = try { codec.decode(saved.payload).also(::validate) } catch (_: Exception) { throw CacheInvalid() }
            check(lease)
            return CachedResult(value, DataSource.CACHE, saved.fetchedAt, failure)
        }
        if (cacheOnly) return@withContext cached()
        val value = try { remote.task(lease, id).also { check(lease); validate(it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            check(lease)
            if (failure !is ApiFailure.Network && failure !is AuthUnavailable) throw failure
            return@withContext try { cached(failure) } catch (_: CacheMiss) { throw failure }
        }
        val at = now()
        check(lease); cache.write(lease.scope, "production_task", key, StoredSnapshot(codec.encode(value), at)); check(lease)
        CachedResult(value, DataSource.NETWORK, at)
    }
}
