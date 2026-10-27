package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.SnapshotPenNoteDraftStore
import com.sentri.nativeapp.pigs.SqliteSnapshotCache
import com.sentri.nativeapp.pigs.StoredSnapshot
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class PenNoteDraftStoreTest {
    @Test fun localNoteReopensInSQLiteAndClearingNeverTouchesAnotherScopeOrPen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pen-note-test-${UUID.randomUUID()}"
        val lease = PigProfileFixture.lease
        var cache = SqliteSnapshotCache(context, name)
        try {
            var store = SnapshotPenNoteDraftStore(cache, {}, { 123 })
            val text = "  Fan noisy\n检查线路  "
            store.write(lease, "unit", "pen-1", text)
            store.write(lease, "unit", "pen-2", "Another draft")
            cache.close()
            cache = SqliteSnapshotCache(context, name)
            store = SnapshotPenNoteDraftStore(cache, {})
            assertEquals(text, store.read(lease, "unit", "pen-1")?.text)
            assertEquals(123L, store.read(lease, "unit", "pen-1")?.savedAt)
            assertNull(store.read(lease, "another-unit", "pen-1"))
            val scope = lease.scope
            listOf(scope.copy(environment = "another"), scope.copy(oauthId = "another"),
                scope.copy(userId = "another"), scope.copy(farmId = "another")).forEach {
                assertNull(store.read(lease.copy(scope = it), "unit", "pen-1"))
            }
            try { store.write(lease, "unit", "pen-1", " \n "); fail("Blank notes must not replace a draft") }
            catch (_: IllegalArgumentException) { }
            assertEquals(text, store.read(lease, "unit", "pen-1")?.text)
            store.write(lease, "unit", "pen-1", null)
            assertNull(store.read(lease, "unit", "pen-1"))
            assertEquals("Another draft", store.read(lease, "unit", "pen-2")?.text)
            cache.write(scope, "local_pen_note_draft", JSONArray().put("unit").put("bad-pen").toString(),
                StoredSnapshot("{broken", 1))
            try { store.read(lease, "unit", "bad-pen"); fail("Corrupt draft must not become an empty note") }
            catch (_: org.json.JSONException) { }
            store.write(lease, "unit", "bad-pen", null)
            assertNull(store.read(lease, "unit", "bad-pen"))
        } finally {
            cache.close()
            context.deleteDatabase(name)
        }
    }
}
