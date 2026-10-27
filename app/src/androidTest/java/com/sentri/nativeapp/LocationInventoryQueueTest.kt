package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LocationInventoryQueueTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val lease = QueueContext(scope, 1, true, true)

    @Test fun nullableAndEmptyAllocationsRemainDistinctAfterSqliteReopen() {
        val name = "location-inventory-${UUID.randomUUID()}"
        val nullable = ReconcileLocationInventoryInput("pen", 2, null)
        val empty = ReconcileLocationInventoryInput("pen", 2, emptyList())
        assertNotEquals(CommandPayloadCodec.encode(nullable), CommandPayloadCodec.encode(empty))
        try {
            SqliteCommandQueue(context, name).use { queue ->
                val first = queue.enqueue(scope, "first", nullable, emptySet(), 100)
                val second = queue.enqueue(scope, "second", empty, emptySet(), 0)
                assertEquals(setOf(first.record.id), second.record.dependsOn)
            }
            SqliteCommandQueue(context, name).use { reopened ->
                assertEquals(nullable, reopened.list(scope).single { it.record.operationId == "first" }.input)
                assertEquals(empty, reopened.list(scope).single { it.record.operationId == "second" }.input)
                val first = reopened.claim(lease, 101)!!
                reopened.finish(first, CommandOutcome.Uncertain, 102)
                assertNull(reopened.claim(lease, 103))
                try { reopened.finish(first, CommandOutcome.Success(LocationInventoryReceipt("other").encode()), 104); fail() }
                catch (_: IllegalArgumentException) { }
                reopened.finish(first, CommandOutcome.Success(LocationInventoryReceipt("pen").encode()), 105)
                assertEquals("second", reopened.claim(lease, 106)?.record?.operationId)
            }
        } finally { context.deleteDatabase(name) }
    }
    @Test fun headcountSnapshotSurvivesReopenAndDoesNotCrossFarm() = runBlocking {
        val name = "location-inventory-cache-${UUID.randomUUID()}"
        val expected = LocationInventorySnapshot("pen", listOf(
            LocationInventoryPig("a", "farm", "pen", "ACTIVE", "tag", null, "b1"),
            LocationInventoryPig("b", "farm", "pen", "ACTIVE", null, null, "b1")))
        var calls = 0
        val remote = object : LocationInventoryRemote {
            override suspend fun current(lease: ScopeLease, unitId: String, locationCode: String) =
                expected.also { calls++ }
        }
        try {
            SqliteSnapshotCache(context, name).use { cache -> LocationInventoryReads(remote, cache, { }, { 100 })
                .current(ScopeLease(scope, 1), "unit-a", "pen") }
            SqliteSnapshotCache(context, name).use { cache ->
                val reads = LocationInventoryReads(remote, cache, { }, { 200 })
                assertEquals(expected, reads.current(ScopeLease(scope, 1), "unit-a", "pen", true).value)
                assertEquals(1, calls)
                try { reads.current(ScopeLease(scope, 1), "unit-b", "pen", true); fail() }
                catch (_: CacheMiss) { }
                try { reads.current(ScopeLease(scope.copy(farmId = "other"), 1), "unit-a", "pen", true); fail() }
                catch (_: CacheMiss) { }
            }
        } finally { context.deleteDatabase(name) }
    }
}
