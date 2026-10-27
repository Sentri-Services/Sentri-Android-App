package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.auth.AuthGateway
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.LoginRequest
import com.sentri.nativeapp.auth.Session
import com.sentri.nativeapp.auth.SessionStore
import com.sentri.nativeapp.inspection.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InspectionCheckInServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val unit = FarmUnit("unit", "farm", "单元", "Unit", "Z-S-U", "AnimalHousing")
    private val auth = AuthRepository(object : SessionStore {
        private var session: Session? = Session("{}", "token", Long.MAX_VALUE, "oauth", "farm")
        override fun readSession() = session
        override fun saveSession(session: Session?) { this.session = session }
        override fun readPendingLogin(): LoginRequest? = null
        override fun savePendingLogin(request: LoginRequest?) { }
    }, object : AuthGateway {
        override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
        override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
        override suspend fun refresh(session: Session): Session = error("unused")
        override fun logoutUrl(session: Session): String? = null
    })
    private val gate = ScopeGate("dev", auth)
    private val lease = ScopeLease(scope, auth.contextRevision)

    @Test fun offlineCheckInRestoresThenSendsOnlyItsOwnOperationAndReadsBack() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val queueName = "checkin-$suffix.db"
        val cacheName = "checkin-$suffix-cache.db"
        var online = false
        var sends = 0
        var scheduled = 0
        val records = mutableListOf<UnitInspectionRecord>()
        try {
            SqliteSnapshotCache(context, cacheName).use { cache ->
                val reads = UnitInspectionReads(object : UnitInspectionRemote {
                    override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = records.toList()
                    override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = records.filter { it.id in ids }
                    override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
                }, cache, gate::check)
                val gateway = object : UnitInspectionGateway {
                    override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome {
                        sends++
                        val id = requireNotNull(input.unitInspectionId)
                        records += UnitInspectionRecord(id, "farm", input.unitCode, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null)
                        return CommandOutcome.Success(UnitInspectionWriteReceipt(input.operationName, id).encode())
                    }
                    override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome = error("not dispatched")
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    val service = InspectionCheckInService(queue, gateway, reads, gate, auth, { online },
                        onSaved = { savedLease, saved ->
                            assertEquals(lease, savedLease)
                            assertEquals(CommandStatus.QUEUED, saved.record.status)
                            assertEquals(saved.record.id, queue.list(scope).single().record.id)
                            scheduled++
                        })
                    val saved = service.save(lease, unit)
                    assertEquals(1, scheduled)
                    assertEquals(LocalCheckInReceipt(saved.record.operationId,
                        saved.record.createdAtMillis, CommandStatus.QUEUED), service.latestReceipt(lease, unit))
                    queue.enqueue(scope, "other", SubmitUnitRemarkInput("OTHER-UNIT", "留待后续"), emptySet(), 2)
                    assertNotNull(queue.claimOperations(QueueContext(scope, lease.revision, true, true), 3,
                        setOf("SubmitUnitRemark")))
                    service.sync(lease)
                    assertEquals(CommandStatus.QUEUED, service.latestStatus(lease, unit))
                    assertEquals(CommandStatus.IN_FLIGHT, queue.list(scope).single { it.input is SubmitUnitRemarkInput }.record.status)
                    assertEquals(0, sends)
                }
                SqliteCommandQueue(context, queueName).use { reopened ->
                    online = true
                    val service = InspectionCheckInService(reopened, gateway, reads, gate, auth, { online })
                    assertEquals(CommandStatus.QUEUED, service.latestReceipt(lease, unit)?.status)
                    service.sync(lease)
                    assertEquals(1, sends)
                    assertEquals(CommandStatus.SYNCED, service.latestStatus(lease, unit))
                    assertEquals(CommandStatus.SYNCED, service.latestReceipt(lease, unit)?.status)
                    assertEquals(CommandStatus.IN_FLIGHT, reopened.list(scope).single { it.input is SubmitUnitRemarkInput }.record.status)
                }
            }
        } finally { context.deleteDatabase(queueName); context.deleteDatabase(cacheName) }
    }

    @Test fun uncertainAttemptIsReconciledByRecordIdWithoutSecondMutation() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val queueName = "checkin-unknown-$suffix.db"
        val cacheName = "checkin-unknown-$suffix-cache.db"
        var sends = 0
        val records = mutableListOf<UnitInspectionRecord>()
        try {
            SqliteSnapshotCache(context, cacheName).use { cache ->
                val reads = UnitInspectionReads(object : UnitInspectionRemote {
                    override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = records.toList()
                    override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = records.filter { it.id in ids }
                    override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
                }, cache, gate::check)
                val gateway = object : UnitInspectionGateway {
                    override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome {
                        sends++
                        records += UnitInspectionRecord(requireNotNull(input.unitInspectionId), "farm", input.unitCode,
                            null, null, null, null, null, null, null, null, null, null, null, null, null, null)
                        error("simulated lost response after server write")
                    }
                    override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome = error("not dispatched")
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    val service = InspectionCheckInService(queue, gateway, reads, gate, auth, { true })
                    service.save(lease, unit)
                    service.sync(lease)
                    assertEquals(CommandStatus.UNCERTAIN, service.latestStatus(lease, unit))
                    service.sync(lease)
                    assertEquals(CommandStatus.SYNCED, service.latestStatus(lease, unit))
                    assertEquals(1, sends)
                }
            }
        } finally { context.deleteDatabase(queueName); context.deleteDatabase(cacheName) }
    }

    @Test fun uncertainEarlierCheckInDoesNotBlockLaterIndependentCheckIn() = runBlocking {
        val suffix = UUID.randomUUID().toString()
        val queueName = "checkin-independent-$suffix.db"
        val cacheName = "checkin-independent-$suffix-cache.db"
        val records = mutableListOf<UnitInspectionRecord>()
        var sends = 0
        try {
            SqliteSnapshotCache(context, cacheName).use { cache ->
                val reads = UnitInspectionReads(object : UnitInspectionRemote {
                    override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = records.toList()
                    override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = records.filter { it.id in ids }
                    override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
                }, cache, gate::check)
                val gateway = object : UnitInspectionGateway {
                    override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome {
                        sends++
                        if (sends == 1) return CommandOutcome.Uncertain
                        val id = requireNotNull(input.unitInspectionId)
                        records += UnitInspectionRecord(id, "farm", input.unitCode, null, null,
                            null, null, null, null, null, null, null, null, null, null, null, null)
                        return CommandOutcome.Success(UnitInspectionWriteReceipt(input.operationName, id).encode())
                    }
                    override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome = error("not dispatched")
                }
                SqliteCommandQueue(context, queueName).use { queue ->
                    val service = InspectionCheckInService(queue, gateway, reads, gate, auth, { true },
                        now = { 10L })
                    val first = service.save(lease, unit)
                    val second = service.save(lease, unit)
                    assertTrue(second.record.dependsOn.isEmpty())
                    assertEquals(first.record.createdAtMillis + 1, second.record.createdAtMillis)
                    assertEquals(LocalCheckInOverview(LocalCheckInReceipt(second.record.operationId,
                        second.record.createdAtMillis, CommandStatus.QUEUED), 1, 0,
                        listOf(LocalCheckInReceipt(second.record.operationId,
                            second.record.createdAtMillis, CommandStatus.QUEUED),
                            LocalCheckInReceipt(first.record.operationId,
                                first.record.createdAtMillis, CommandStatus.QUEUED))),
                        service.localOverview(lease, unit))
                    service.sync(lease)
                    val statuses = queue.list(scope).associate { it.record.id to it.record.status }
                    assertEquals(CommandStatus.UNCERTAIN, statuses[first.record.id])
                    assertEquals(CommandStatus.SYNCED, statuses[second.record.id])
                    assertEquals(LocalCheckInOverview(LocalCheckInReceipt(second.record.operationId,
                        second.record.createdAtMillis, CommandStatus.SYNCED), 0, 1,
                        listOf(LocalCheckInReceipt(first.record.operationId,
                            first.record.createdAtMillis, CommandStatus.UNCERTAIN))),
                        service.localOverview(lease, unit))
                    queue.enqueue(scope, "different-unit", SubmitUnitInspectionInput("OTHER-UNIT", "different-unit"),
                        emptySet(), 12L)
                    assertEquals(1, service.localOverview(lease, unit).earlierNeedsReviewCount)
                    service.sync(lease)
                    assertEquals(3, sends)
                }
                SqliteCommandQueue(context, queueName).use { reopened ->
                    val service = InspectionCheckInService(reopened, gateway, reads, gate, auth, { false })
                    assertEquals(1, service.localOverview(lease, unit).earlierNeedsReviewCount)
                    assertEquals(CommandStatus.SYNCED, service.localOverview(lease, unit).latest?.status)
                }
            }
        } finally { context.deleteDatabase(queueName); context.deleteDatabase(cacheName) }
    }
}
