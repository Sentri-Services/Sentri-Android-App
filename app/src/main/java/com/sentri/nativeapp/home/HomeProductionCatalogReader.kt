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
import com.sentri.nativeapp.production.ProductionTaskStatus
import com.sentri.nativeapp.production.TaskCatalogCodecs
import com.sentri.nativeapp.production.TaskCatalogReads
import com.sentri.nativeapp.production.TaskCatalogRequest
import com.sentri.nativeapp.production.TaskMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface HomeProductionReads {
    suspend fun load(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<TaskMetadata>>
}

/** Full open-production list. Page caches are never combined into an offline Home count. */
class HomeProductionCatalogReader(
    private val catalog: TaskCatalogReads,
    private val cache: SnapshotCache,
    private val check: (ScopeLease) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : HomeProductionReads {
    private val kind = "home_open_production"
    private val key = "all"
    private val open = setOf(ProductionTaskStatus.SCHEDULED, ProductionTaskStatus.RUNNING)

    override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<List<TaskMetadata>> = withContext(Dispatchers.IO) {
        check(lease)
        fun saved(failure: Exception? = null): CachedResult<List<TaskMetadata>> {
            val stored = cache.read(lease.scope, kind, key) ?: throw CacheMiss()
            val tasks = try { decode(stored.payload, lease) } catch (_: Exception) { throw CacheInvalid() }
            check(lease)
            return CachedResult(tasks, DataSource.CACHE, stored.fetchedAt, failure)
        }
        if (cacheOnly) return@withContext saved()
        try {
            var offset = 0
            var total: Int? = null
            val tasks = mutableListOf<TaskMetadata>()
            do {
                val request = TaskCatalogRequest(statuses = open, offset = offset, limit = 200)
                val page = catalog.page(lease, request)
                check(lease)
                if (page.source == DataSource.CACHE) return@withContext try { saved(page.refreshFailure) }
                    catch (_: CacheMiss) { throw page.refreshFailure ?: CacheMiss() }
                val value = page.value
                if (total != null && total != value.total || value.tasks.isEmpty() && offset < value.total) throw ApiFailure.Protocol()
                total = value.total
                tasks += value.tasks
                offset += value.tasks.size
                if (offset > value.total) throw ApiFailure.Protocol()
            } while (offset < total!!)
            if (tasks.map { it.id }.distinct().size != tasks.size || tasks.size != total) throw ApiFailure.Protocol()
            check(lease)
            val at = now()
            cache.write(lease.scope, kind, key, StoredSnapshot(encode(tasks), at))
            check(lease)
            CachedResult(tasks, DataSource.NETWORK, at)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            check(lease)
            if (failure !is ApiFailure.Network && failure !is AuthUnavailable) throw failure
            try { saved(failure) } catch (_: CacheMiss) { throw failure }
        }
    }

    private fun encode(tasks: List<TaskMetadata>): String = JSONObject().put("format", 1)
        .put("tasks", JSONArray(tasks.map(TaskCatalogCodecs::encodeMetadata))).toString()

    private fun decode(text: String, lease: ScopeLease): List<TaskMetadata> {
        val root = JSONObject(text)
        require(root.length() == 2 && root.getInt("format") == 1)
        val items = root.getJSONArray("tasks")
        val tasks = (0 until items.length()).map { requireNotNull(TaskCatalogCodecs.decodeMetadata(items.getString(it))) }
        require(tasks.map { it.id }.distinct().size == tasks.size && tasks.all {
            it.farmId == lease.scope.farmId && it.status in open
        })
        return tasks
    }
}
