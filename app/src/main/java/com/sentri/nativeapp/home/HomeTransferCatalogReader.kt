package com.sentri.nativeapp.home

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.graphql.NativeHomeTransferTasksQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.network.TimestampAdapter
import com.sentri.nativeapp.pigs.CacheInvalid
import com.sentri.nativeapp.pigs.CacheMiss
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SnapshotCache
import com.sentri.nativeapp.pigs.StoredSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class HomeTransferTask(val id: String, val farmId: String, val status: String,
    val transferType: String, val plannedAt: String?, val batchSerial: String?,
    val fromUnitCodes: List<String>, val toUnitCodes: List<String>)

data class HomeTransferPage(val rows: List<HomeTransferTask>, val total: Int)

interface HomeTransferRemote {
    suspend fun page(lease: ScopeLease, offset: Int, limit: Int): HomeTransferPage
}

class GraphqlHomeTransferRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : HomeTransferRemote {
    override suspend fun page(lease: ScopeLease, offset: Int, limit: Int): HomeTransferPage {
        require(offset >= 0 && limit in 1..200)
        check(lease)
        val data = executor.query(NativeHomeTransferTasksQuery(lease.scope.farmId, offset, limit))
        check(lease)
        val total = data.total.aggregate?.count ?: throw ApiFailure.Protocol()
        if (total < 0 || data.rows.size > limit) throw ApiFailure.Protocol()
        val rows = data.rows.map { row ->
            if (row.transfer_out_unit_aggregate.aggregate?.count != row.transfer_out_unit.size ||
                row.transfer_in_unit_aggregate.aggregate?.count != row.transfer_in_unit.size ||
                row.batch?.farm_id?.let { it != lease.scope.farmId } == true ||
                row.transfer_out_unit.any { it.farm_id != lease.scope.farmId || it.transfer_id != row.transfer_id } ||
                row.transfer_in_unit.any { it.farm_id != lease.scope.farmId || it.transfer_id != row.transfer_id })
                throw ApiFailure.Protocol()
            HomeTransferTask(row.transfer_id, row.farm_id, row.status.rawValue,
                row.transfer_type.rawValue, row.planned_transfer_time, row.batch?.sn,
                row.transfer_out_unit.map { it.unit_code }, row.transfer_in_unit.map { it.unit_code })
                .also { validate(lease.scope.farmId, it) }
        }
        if (rows.map(HomeTransferTask::id).distinct().size != rows.size || rows.size > total)
            throw ApiFailure.Protocol()
        return HomeTransferPage(rows, total)
    }
}

interface HomeTransferReads {
    suspend fun load(lease: ScopeLease, cacheOnly: Boolean = false): CachedResult<List<HomeTransferTask>>
}

/** A Home count comes from one complete, scoped transfer catalog; no partial page is published. */
class HomeTransferCatalogReader(private val remote: HomeTransferRemote, private val cache: SnapshotCache,
    private val check: (ScopeLease) -> Unit, private val now: () -> Long = System::currentTimeMillis,
) : HomeTransferReads {
    private val kind = "home_open_transfer"
    private val key = "all"

    override suspend fun load(lease: ScopeLease, cacheOnly: Boolean): CachedResult<List<HomeTransferTask>> =
        withContext(Dispatchers.IO) {
            check(lease)
            fun saved(failure: Exception? = null): CachedResult<List<HomeTransferTask>> {
                val value = cache.read(lease.scope, kind, key) ?: throw CacheMiss()
                val tasks = try { decode(value.payload, lease.scope.farmId) }
                    catch (_: Exception) { throw CacheInvalid() }
                check(lease)
                return CachedResult(tasks, DataSource.CACHE, value.fetchedAt, failure)
            }
            if (cacheOnly) return@withContext saved()
            try {
                val tasks = mutableListOf<HomeTransferTask>()
                val ids = mutableSetOf<String>()
                var offset = 0
                var total: Int? = null
                do {
                    val page = remote.page(lease, offset, 200)
                    check(lease)
                    if (total != null && total != page.total || page.total < 0 ||
                        page.rows.size > 200 || page.rows.isEmpty() && offset < page.total ||
                        tasks.size + page.rows.size > 20_000 || page.rows.any { !ids.add(it.id) })
                        throw ApiFailure.Protocol()
                    page.rows.forEach { validate(lease.scope.farmId, it) }
                    total = page.total
                    tasks += page.rows
                    offset += page.rows.size
                    if (offset > page.total) throw ApiFailure.Protocol()
                } while (offset < total!!)
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
}

private fun validate(farmId: String, task: HomeTransferTask) {
    try {
        require(task.id.isNotBlank() && task.farmId == farmId)
        require(task.status in setOf("PLANNED", "IN_PROGRESS"))
        require(task.transferType.isNotBlank())
        task.plannedAt?.let(TimestampAdapter::validate)
        require(task.fromUnitCodes.all(String::isNotBlank) &&
            task.fromUnitCodes.distinct().size == task.fromUnitCodes.size)
        require(task.toUnitCodes.all(String::isNotBlank) &&
            task.toUnitCodes.distinct().size == task.toUnitCodes.size)
    } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
}

private fun encode(tasks: List<HomeTransferTask>): String = JSONObject().put("format", 1)
    .put("tasks", JSONArray(tasks.map { task -> JSONObject()
        .put("id", task.id).put("farmId", task.farmId).put("status", task.status)
        .put("transferType", task.transferType).put("plannedAt", task.plannedAt)
        .put("batchSerial", task.batchSerial)
        .put("from", JSONArray(task.fromUnitCodes)).put("to", JSONArray(task.toUnitCodes)) })).toString()

private fun decode(text: String, farmId: String): List<HomeTransferTask> {
    val root = JSONObject(text)
    require(root.getInt("format") == 1)
    val array = root.getJSONArray("tasks")
    require(array.length() <= 20_000)
    val tasks = (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        fun codes(key: String) = item.getJSONArray(key).let { values ->
            (0 until values.length()).map(values::getString)
        }
        HomeTransferTask(item.getString("id"), item.getString("farmId"), item.getString("status"),
            item.getString("transferType"), if (item.isNull("plannedAt")) null else item.getString("plannedAt"),
            if (item.isNull("batchSerial")) null else item.getString("batchSerial"), codes("from"), codes("to"))
            .also { validate(farmId, it) }
    }
    require(tasks.map(HomeTransferTask::id).distinct().size == tasks.size)
    return tasks
}
