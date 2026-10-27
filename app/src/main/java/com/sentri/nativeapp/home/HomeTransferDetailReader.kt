package com.sentri.nativeapp.home

import com.sentri.nativeapp.auth.AuthUnavailable
import com.sentri.nativeapp.graphql.NativeTransferTaskQuery
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
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

data class TransferUnitProgress(val code: String, val recorded: Int?)
data class TransferPigRecord(val id: String, val earTag: String?, val earNotch: String?, val status: String)
data class HomeTransferDetail(val id: String, val farmId: String, val status: String,
    val batchSerial: String?, val from: List<TransferUnitProgress>,
    val to: List<TransferUnitProgress>, val pigs: List<TransferPigRecord>)

interface HomeTransferDetailRemote {
    suspend fun task(lease: ScopeLease, id: String): HomeTransferDetail?
}

class GraphqlHomeTransferDetailRemote(private val executor: GraphqlExecutor,
    private val check: (ScopeLease) -> Unit) : HomeTransferDetailRemote {
    override suspend fun task(lease: ScopeLease, id: String): HomeTransferDetail? {
        require(id.isNotBlank())
        check(lease)
        val row = executor.query(NativeTransferTaskQuery(id)).farm_view_transfer_task_by_pk
        check(lease)
        if (row == null) return null
        if (row.transfer_id != id || row.farm_id != lease.scope.farmId ||
            row.batch?.farm_id?.let { it != lease.scope.farmId } == true ||
            row.transfer_out_unit.any { it.farm_id != lease.scope.farmId || it.transfer_id != id } ||
            row.transfer_in_unit.any { it.farm_id != lease.scope.farmId || it.transfer_id != id } ||
            row.items.any { it.farm_id != lease.scope.farmId || it.transfer_id != id ||
                it.pig?.farm_id?.let { farm -> farm != lease.scope.farmId } == true ||
                it.pig?.id?.let { pigId -> pigId != it.pig_id } == true })
            throw ApiFailure.Protocol()
        val result = HomeTransferDetail(id, row.farm_id, row.status.rawValue, row.batch?.sn,
            row.transfer_out_unit.map { TransferUnitProgress(it.unit_code, it.transfer_out_count) },
            row.transfer_in_unit.map { TransferUnitProgress(it.unit_code, it.transfer_in_count) },
            row.items.map { TransferPigRecord(it.pig_id, it.pig?.ear_tag, it.pig?.ear_notch,
                it.status.rawValue) })
        validate(lease.scope.farmId, result)
        return result
    }
}

class HomeTransferDetailReader(private val remote: HomeTransferDetailRemote,
    private val cache: SnapshotCache, private val check: (ScopeLease) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) {
    suspend fun task(lease: ScopeLease, id: String, cacheOnly: Boolean = false): CachedResult<HomeTransferDetail?> =
        withContext(Dispatchers.IO) {
            require(id.isNotBlank())
            check(lease)
            fun saved(failure: Exception? = null): CachedResult<HomeTransferDetail?> {
                val snapshot = cache.read(lease.scope, "home_transfer_detail", id) ?: throw CacheMiss()
                val detail = try { decode(snapshot.payload, lease.scope.farmId, id) }
                    catch (_: Exception) { throw CacheInvalid() }
                check(lease)
                return CachedResult(detail, DataSource.CACHE, snapshot.fetchedAt, failure)
            }
            if (cacheOnly) return@withContext saved()
            try {
                val detail = remote.task(lease, id)
                check(lease)
                val at = now()
                // A missing task is not cached: it may be a transient permission or lifecycle change.
                if (detail != null) {
                    validate(lease.scope.farmId, detail)
                    cache.write(lease.scope, "home_transfer_detail", id, StoredSnapshot(encode(detail), at))
                }
                check(lease)
                CachedResult(detail, DataSource.NETWORK, at)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                check(lease)
                if (failure !is ApiFailure.Network && failure !is AuthUnavailable) throw failure
                try { saved(failure) } catch (_: CacheMiss) { throw failure }
            }
        }
}

private fun validate(farmId: String, detail: HomeTransferDetail) {
    try {
        require(detail.id.isNotBlank() && detail.farmId == farmId && detail.status.isNotBlank())
        require(detail.from.all { it.code.isNotBlank() && (it.recorded == null || it.recorded >= 0) })
        require(detail.to.all { it.code.isNotBlank() && (it.recorded == null || it.recorded >= 0) })
        require(detail.from.map { it.code }.distinct().size == detail.from.size)
        require(detail.to.map { it.code }.distinct().size == detail.to.size)
        require(detail.pigs.size <= 20_000 && detail.pigs.all { it.id.isNotBlank() && it.status.isNotBlank() })
        require(detail.pigs.map { it.id }.distinct().size == detail.pigs.size)
    } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
}

private fun encode(detail: HomeTransferDetail): String = JSONObject().put("format", 1)
    .put("id", detail.id).put("farm", detail.farmId).put("status", detail.status)
    .put("batch", detail.batchSerial)
    .put("from", JSONArray(detail.from.map { JSONObject().put("code", it.code).put("count", it.recorded) }))
    .put("to", JSONArray(detail.to.map { JSONObject().put("code", it.code).put("count", it.recorded) }))
    .put("pigs", JSONArray(detail.pigs.map { JSONObject().put("id", it.id).put("tag", it.earTag)
        .put("notch", it.earNotch).put("status", it.status) })).toString()

private fun decode(text: String, farmId: String, id: String): HomeTransferDetail {
    val root = JSONObject(text)
    require(root.getInt("format") == 1 && root.getString("id") == id)
    fun units(key: String): List<TransferUnitProgress> = root.getJSONArray(key).let { array ->
        require(array.length() <= 1_000)
        (0 until array.length()).map { index -> array.getJSONObject(index).let {
            TransferUnitProgress(it.getString("code"), if (it.isNull("count")) null else it.getInt("count"))
        } }
    }
    val pigArray = root.getJSONArray("pigs")
    require(pigArray.length() <= 20_000)
    val pigs = (0 until pigArray.length()).map { index -> pigArray.getJSONObject(index).let {
        TransferPigRecord(it.getString("id"), if (it.isNull("tag")) null else it.getString("tag"),
            if (it.isNull("notch")) null else it.getString("notch"), it.getString("status"))
    } }
    return HomeTransferDetail(root.getString("id"), root.getString("farm"), root.getString("status"),
        if (root.isNull("batch")) null else root.getString("batch"), units("from"), units("to"), pigs)
        .also { validate(farmId, it) }
}
