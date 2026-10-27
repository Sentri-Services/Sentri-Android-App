package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.network.ApiFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared read boundary for independently versioned domain codecs; write commands never enter this cache. */
class ScopedSnapshotReads(private val cache: SnapshotCache, private val check: (ScopeLease) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {
    suspend fun <T> read(lease: ScopeLease, kind: String, key: String, cacheOnly: Boolean, fetch: suspend () -> T,
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
        val at = now(); check(lease)
        cache.write(lease.scope, kind, key, StoredSnapshot(encode(value), at)); check(lease)
        CachedResult(value, DataSource.NETWORK, at)
    }
}
