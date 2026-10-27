package com.sentri.nativeapp

import android.os.Process
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
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Host runs prepare, force-stops the app process, then runs restore. No backend writes. */
class InspectionCheckInProcessRestoreTest {
    @Test fun queuedAndInterruptedCheckInsRecoverWithoutDuplicateMutation() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("checkInStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "checkin-process-test"
        val preferences = context.getSharedPreferences(name, 0)
        val scope = DataScope("dev", "oauth", "user", "farm")
        val unit = FarmUnit("unit", "farm", "单元", "Unit", "Z-S-U", "AnimalHousing")
        val auth = AuthRepository(object : SessionStore {
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
        val gate = ScopeGate("dev", auth)
        val lease = ScopeLease(scope, auth.contextRevision)

        if (stage == "prepare") {
            context.deleteDatabase(name)
            context.deleteDatabase("$name-cache")
            preferences.edit().clear().commit()
            SqliteSnapshotCache(context, "$name-cache").use { cache ->
                val reads = UnitInspectionReads(object : UnitInspectionRemote {
                    override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = emptyList<UnitInspectionRecord>()
                    override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = emptyList<UnitInspectionRecord>()
                    override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
                }, cache, gate::check)
                SqliteCommandQueue(context, name).use { queue ->
                    var time = 1L
                    val service = InspectionCheckInService(queue, object : UnitInspectionGateway {
                        override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome = error("offline")
                        override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome = error("unused")
                    }, reads, gate, auth, { false }, { time++ })
                    val interrupted = service.save(lease, unit)
                    val queued = service.save(lease, unit)
                    val claimed = requireNotNull(queue.claimOperations(
                        QueueContext(scope, lease.revision, true, true), time++, setOf("SubmitUnitInspection")))
                    assertEquals(interrupted.record.id, claimed.record.id)
                    assertEquals(CommandStatus.QUEUED, queue.list(scope).single { it.record.id == queued.record.id }.record.status)
                    check(preferences.edit().putInt("pid", Process.myPid())
                        .putString("interrupted", interrupted.record.id)
                        .putString("queued", queued.record.id)
                        .putString("interruptedInput", (interrupted.input as SubmitUnitInspectionInput).unitInspectionId)
                        .putString("queuedInput", (queued.input as SubmitUnitInspectionInput).unitInspectionId).commit())
                }
            }
            println("CHECKIN_PROCESS_PREPARED queued=1 interrupted=1")
        } else {
            try {
                assertNotEquals(preferences.getInt("pid", -1), Process.myPid())
                val interruptedId = requireNotNull(preferences.getString("interrupted", null))
                val queuedId = requireNotNull(preferences.getString("queued", null))
                val interruptedInputId = requireNotNull(preferences.getString("interruptedInput", null))
                val queuedInputId = requireNotNull(preferences.getString("queuedInput", null))
                var sends = 0
                val records = mutableListOf<UnitInspectionRecord>()
                SqliteSnapshotCache(context, "$name-cache").use { cache ->
                    val reads = UnitInspectionReads(object : UnitInspectionRemote {
                        override suspend fun inspections(lease: ScopeLease, sinceInclusive: String?) = records.toList()
                        override suspend fun inspectionsByIds(lease: ScopeLease, ids: Set<String>) = records.filter { it.id in ids }
                        override suspend fun remarks(lease: ScopeLease, unitCode: String) = emptyList<UnitRemarkRecord>()
                    }, cache, gate::check)
                    val gateway = object : UnitInspectionGateway {
                        override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome {
                            sends++
                            val id = requireNotNull(input.unitInspectionId)
                            assertEquals(queuedInputId, id)
                            records += record(id, input.unitCode)
                            return CommandOutcome.Success(UnitInspectionWriteReceipt(input.operationName, id).encode())
                        }
                        override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome = error("unused")
                    }
                    SqliteCommandQueue(context, name).use { queue ->
                        val before = queue.list(scope).associateBy { it.record.id }
                        assertEquals(CommandStatus.IN_FLIGHT, before[interruptedId]?.record?.status)
                        assertEquals(CommandStatus.QUEUED, before[queuedId]?.record?.status)
                        val service = InspectionCheckInService(queue, gateway, reads, gate, auth, { true })
                        service.sync(lease)
                        assertEquals(1, sends)
                        val unresolved = queue.list(scope).associateBy { it.record.id }
                        assertEquals(CommandStatus.UNCERTAIN, unresolved[interruptedId]?.record?.status)
                        assertEquals(CommandStatus.SYNCED, unresolved[queuedId]?.record?.status)
                        records += record(interruptedInputId, unit.locationCode)
                        service.sync(lease)
                        assertEquals(1, sends)
                        val confirmed = queue.list(scope).associateBy { it.record.id }
                        assertEquals(CommandStatus.SYNCED, confirmed[interruptedId]?.record?.status)
                        assertEquals(CommandStatus.SYNCED, confirmed[queuedId]?.record?.status)
                    }
                }
                println("CHECKIN_PROCESS_RESTORED newProcess=true queuedSent=1 interruptedResent=0 confirmed=2")
            } finally {
                context.deleteDatabase(name)
                context.deleteDatabase("$name-cache")
                preferences.edit().clear().commit()
            }
        }
    }

    private fun record(id: String, unitCode: String) = UnitInspectionRecord(id, "farm", unitCode,
        null, null, null, null, null, null, null, null, null, null, null, null, null, null)
}
