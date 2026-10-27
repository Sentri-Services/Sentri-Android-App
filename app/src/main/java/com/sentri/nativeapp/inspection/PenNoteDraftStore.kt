package com.sentri.nativeapp.inspection

import com.sentri.nativeapp.pigs.ScopeLease
import com.sentri.nativeapp.pigs.SnapshotCache
import com.sentri.nativeapp.pigs.StoredSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** This is a local form, never the current server pen note or a queued command. */
data class PenNoteDraft(val text: String, val savedAt: Long)

data class PenNoteEditor(val text: String = "", val saved: PenNoteDraft? = null,
    val loaded: Boolean = false, val loading: Boolean = false, val loadFailed: Boolean = false,
    val saving: Boolean = false, val saveFailed: Boolean = false) {
    val unchanged: Boolean get() = saved?.text == text
}

interface PenNoteDraftStore {
    suspend fun read(lease: ScopeLease, unitId: String, penCode: String): PenNoteDraft?
    suspend fun write(lease: ScopeLease, unitId: String, penCode: String, text: String?): PenNoteDraft?
}

/** Dedicated draft kind is excluded from the read-only snapshot eviction list. */
class SnapshotPenNoteDraftStore(private val cache: SnapshotCache, private val check: (ScopeLease) -> Unit,
    private val now: () -> Long = System::currentTimeMillis) : PenNoteDraftStore {
    private val mutex = Mutex()

    override suspend fun read(lease: ScopeLease, unitId: String, penCode: String): PenNoteDraft? =
        mutex.withLock { withContext(Dispatchers.IO) {
            check(lease)
            val result = readSaved(lease, unitId, penCode)
            check(lease)
            result
        } }

    override suspend fun write(lease: ScopeLease, unitId: String, penCode: String, text: String?): PenNoteDraft? =
        mutex.withLock { withContext(Dispatchers.IO) {
            check(lease)
            require(text == null || text.isNotBlank())
            val at = now()
            val payload = JSONObject().put("format", 1).put("unit", unitId).put("pen", penCode)
                .put("text", text ?: JSONObject.NULL).toString()
            cache.write(lease.scope, KIND, key(unitId, penCode), StoredSnapshot(payload, at))
            val saved = readSaved(lease, unitId, penCode)
            kotlin.check(saved?.text == text) { "Local draft commit not confirmed" }
            check(lease)
            saved
        } }

    private fun readSaved(lease: ScopeLease, unitId: String, penCode: String): PenNoteDraft? {
        val row = cache.read(lease.scope, KIND, key(unitId, penCode)) ?: return null
        val json = JSONObject(row.payload)
        require(json.getInt("format") == 1 && json.getString("unit") == unitId && json.getString("pen") == penCode)
        require(json.keys().asSequence().toSet() == setOf("format", "unit", "pen", "text"))
        if (json.isNull("text")) return null
        val text = json.get("text") as? String ?: error("Invalid local note draft")
        require(text.isNotBlank())
        return PenNoteDraft(text, row.fetchedAt)
    }

    private fun key(unitId: String, penCode: String): String {
        require(unitId.isNotBlank() && penCode.isNotBlank())
        return JSONArray().put(unitId).put(penCode).toString()
    }

    private companion object { const val KIND = "local_pen_note_draft" }
}
