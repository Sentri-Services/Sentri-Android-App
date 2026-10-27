package com.sentri.nativeapp.pigs

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Serialized business snapshots; tokens and the legacy Web database are never stored here. */
data class StoredSnapshot(val payload: String, val fetchedAt: Long)
data class StoredAccount(val userId: String, val snapshot: StoredSnapshot)
interface SnapshotCache {
    fun read(scope: DataScope, kind: String, key: String): StoredSnapshot?
    fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot)
    fun readAccount(environment: String, oauthId: String, farmId: String, language: String? = null): StoredAccount?
    fun clear(scope: DataScope)
}
class SqliteSnapshotCache(context: Context, name: String = "native-business-cache.db") : SQLiteOpenHelper(context.applicationContext, name, null, 2), SnapshotCache {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshots (environment TEXT NOT NULL, oauth_id TEXT NOT NULL, user_id TEXT NOT NULL, farm_id TEXT NOT NULL, kind TEXT NOT NULL, cache_key TEXT NOT NULL, payload TEXT NOT NULL, fetched_at INTEGER NOT NULL, format INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(environment,oauth_id,user_id,farm_id,kind,cache_key))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 had the same scoped keys and payload. Add metadata without dropping snapshots.
        check(oldVersion == 1 && newVersion == 2) { "Unsupported cache schema migration" }
        db.execSQL("ALTER TABLE snapshots ADD COLUMN format INTEGER NOT NULL DEFAULT 1")
    }
    private fun args(scope: DataScope) = arrayOf(scope.environment, scope.oauthId, scope.userId, scope.farmId)
    private val scopeWhere = "environment=? AND oauth_id=? AND user_id=? AND farm_id=?"
    private fun readWhere(where: String, values: Array<String>): StoredSnapshot? {
        val db = readableDatabase
        // Keep the row stable while reading large health-history payloads in CursorWindow-sized chunks.
        db.beginTransactionNonExclusive()
        try {
            val result = db.query("snapshots", arrayOf("rowid", "CASE WHEN length(payload)<=65536 THEN payload ELSE NULL END AS payload",
                "fetched_at", "format", "length(payload) AS payload_length"), where, values, null, null, "fetched_at DESC", "1").use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    if (cursor.getInt(3) != 1) throw CacheInvalid()
                    val payload = if (!cursor.isNull(1)) cursor.getString(1) else buildString {
                        var offset = 1L; val length = cursor.getLong(4); val rowId = cursor.getLong(0).toString()
                        while (offset <= length) {
                            db.rawQuery("SELECT substr(payload,?,65536) FROM snapshots WHERE rowid=?", arrayOf(offset.toString(), rowId)).use { chunk ->
                                check(chunk.moveToFirst()); append(chunk.getString(0))
                            }
                            offset += 65536
                        }
                    }
                    StoredSnapshot(payload, cursor.getLong(2))
                }
            }
            db.setTransactionSuccessful(); return result
        } finally { db.endTransaction() }
    }
    override fun read(scope: DataScope, kind: String, key: String) = readWhere("$scopeWhere AND kind=? AND cache_key=?", args(scope) + arrayOf(kind, key))
    override fun readAccount(environment: String, oauthId: String, farmId: String, language: String?): StoredAccount? = readableDatabase.query(
        "snapshots", arrayOf("user_id", "payload", "fetched_at", "format"),
        "environment=? AND oauth_id=? AND farm_id=? AND kind='account' AND cache_key=?",
        arrayOf(environment, oauthId, farmId, if (language == null) "current" else "current:$language"), null, null, "fetched_at DESC", "1",
    ).use {
        if (!it.moveToFirst()) null else {
            if (it.getInt(3) != 1) throw CacheInvalid()
            StoredAccount(it.getString(0), StoredSnapshot(it.getString(1), it.getLong(2)))
        }
    }
    override fun write(scope: DataScope, kind: String, key: String, value: StoredSnapshot) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (kind == "account" && (key == "current" || key.startsWith("current:"))) {
                // Keep a single verified OAuth→backend-user binding for this environment/farm.
                // Clearing the newest scope must not resurrect an older user's account metadata.
                db.delete("snapshots", "environment=? AND oauth_id=? AND farm_id=? AND kind='account' AND user_id<>?", arrayOf(scope.environment, scope.oauthId, scope.farmId, scope.userId))
            }
            db.delete("snapshots", "$scopeWhere AND kind=? AND cache_key=?", args(scope) + arrayOf(kind, key))
            db.insertOrThrow("snapshots", null, ContentValues().apply {
                put("environment", scope.environment); put("oauth_id", scope.oauthId); put("user_id", scope.userId); put("farm_id", scope.farmId)
                put("kind", kind); put("cache_key", key); put("payload", value.payload); put("fetched_at", value.fetchedAt); put("format", 1)
            })
            // Bound read-only pages/details/tasks per scope, keeping account and location metadata.
            db.execSQL("DELETE FROM snapshots WHERE rowid IN (SELECT rowid FROM snapshots WHERE $scopeWhere AND kind IN ('page','detail','production_task','production_catalog_page','production_catalog_detail','production_batch_page','production_batch_detail','production_recheck_page','pig_profile_page','pig_profile_detail','pig_profile_scan','therapy_page','therapy_detail','therapy_medications','therapy_catalogs','therapy_treatments','therapy_context','vaccination_page','vaccination_detail','sampling_page','sampling_detail','device_inspections','device_inspections_pen','unit_inspections','unit_inspection_detail','unit_remarks','health_references','pig_measurements','location_inventory') ORDER BY fetched_at DESC, rowid DESC LIMIT -1 OFFSET 256)", args(scope))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    override fun clear(scope: DataScope) { writableDatabase.delete("snapshots", scopeWhere, args(scope)) }
}
