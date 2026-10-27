package com.sentri.nativeapp.inspection

import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.offline.CommandDispatcher
import com.sentri.nativeapp.offline.CommandOutcome
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.offline.CommandTransport
import com.sentri.nativeapp.offline.QueueContext
import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.offline.SqliteCommandQueue
import com.sentri.nativeapp.pigs.FarmUnit
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class LocalCheckInReceipt(val operationId: String, val savedAtMillis: Long,
    val status: CommandStatus)
data class LocalCheckInOverview(val latest: LocalCheckInReceipt?,
    val earlierWaitingCount: Int, val earlierNeedsReviewCount: Int,
    val outstanding: List<LocalCheckInReceipt> = emptyList())

/** First real write slice: a unit check-in has one client-generated ID and never replays an uncertain attempt. */
class InspectionCheckInService(
    private val queue: SqliteCommandQueue,
    private val gateway: UnitInspectionGateway,
    private val reads: UnitInspectionReads,
    private val gate: ScopeGate,
    private val auth: AuthRepository,
    private val online: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val onSaved: (ScopeLease, QueuedCommand) -> Unit = { _, _ -> },
) {
    private val mutex = Mutex()
    private val recovered = mutableSetOf<ScopeLease>()

    suspend fun save(lease: ScopeLease, unit: FarmUnit): QueuedCommand = withContext(Dispatchers.IO) {
        gate.check(lease)
        require(unit.farmId == lease.scope.farmId && unit.locationCode.isNotBlank())
        val id = UUID.randomUUID().toString()
        val clockTime = now()
        val previousTime = queue.list(lease.scope).filter {
            (it.input as? SubmitUnitInspectionInput)?.unitCode == unit.locationCode
        }.maxOfOrNull { it.record.createdAtMillis }
        val savedAt = if (previousTime != null && previousTime >= clockTime) {
            check(previousTime < Long.MAX_VALUE)
            previousTime + 1
        } else clockTime
        val saved = queue.enqueue(lease.scope, id,
            SubmitUnitInspectionInput(unitCode = unit.locationCode, unitInspectionId = id),
            emptySet(), savedAt)
        try { onSaved(lease, saved) } catch (_: Exception) {
            // The command is already durable. Foreground sync and the next launch can schedule it again.
        }
        saved
    }

    suspend fun localOverview(lease: ScopeLease, unit: FarmUnit): LocalCheckInOverview = withContext(Dispatchers.IO) {
        gate.check(lease)
        require(unit.farmId == lease.scope.farmId && unit.locationCode.isNotBlank())
        val records = queue.list(lease.scope).filter {
            (it.input as? SubmitUnitInspectionInput)?.unitCode == unit.locationCode
        }
        val latest = records.maxWithOrNull(compareBy<QueuedCommand>({ it.record.createdAtMillis }, { it.record.id }))
        val earlier = records.filter { it.record.id != latest?.record?.id }
        val outstanding = records.asSequence().filter { it.record.status in setOf(CommandStatus.QUEUED,
            CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN, CommandStatus.FAILED,
            CommandStatus.CONFLICTED) }
            .sortedWith(compareByDescending<QueuedCommand> { it.record.createdAtMillis }
                .thenByDescending { it.record.id })
            .map { LocalCheckInReceipt(it.record.operationId, it.record.createdAtMillis,
                it.record.status) }.toList()
        LocalCheckInOverview(latest?.let {
            LocalCheckInReceipt(it.record.operationId, it.record.createdAtMillis, it.record.status)
        }, earlier.count { it.record.status == CommandStatus.QUEUED || it.record.status == CommandStatus.IN_FLIGHT },
            earlier.count { it.record.status == CommandStatus.UNCERTAIN ||
                it.record.status == CommandStatus.FAILED || it.record.status == CommandStatus.CONFLICTED },
            outstanding)
    }

    suspend fun latestReceipt(lease: ScopeLease, unit: FarmUnit): LocalCheckInReceipt? =
        localOverview(lease, unit).latest

    suspend fun latestStatus(lease: ScopeLease, unit: FarmUnit): CommandStatus? =
        latestReceipt(lease, unit)?.status

    suspend fun sync(lease: ScopeLease) = mutex.withLock {
        val current = context(lease) ?: return@withLock
        withContext(Dispatchers.IO) {
            if (lease !in recovered) {
                queue.recoverInterruptedOperations(lease.scope, now(), setOf("SubmitUnitInspection"))
                recovered.add(lease)
            }
            if (!current.goodNetwork) return@withContext
            reconcile(lease)
            val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                override suspend fun send(command: QueuedCommand): CommandOutcome {
                    val input = command.input as? SubmitUnitInspectionInput ?: error("Unsupported check-in operation")
                    val outcome = gateway.submit(input)
                    if (outcome !is CommandOutcome.Success) return outcome
                    return if (find(lease, input)) outcome else CommandOutcome.Uncertain
                }
            }, { context(lease) }, claim = { ctx, at -> queue.claimOperations(ctx, at, setOf("SubmitUnitInspection")) }, now = now)
            while (dispatcher.drainOne()) { /* Each successful claim is already scoped and read-confirmed. */ }
        }
    }

    private suspend fun reconcile(lease: ScopeLease) {
        val pending = queue.list(lease.scope).filter {
            it.record.status == CommandStatus.UNCERTAIN && it.input is SubmitUnitInspectionInput
        }
        if (pending.isEmpty()) return
        val expected = pending.associate { command ->
            val input = command.input as SubmitUnitInspectionInput
            requireNotNull(input.unitInspectionId) to input.unitCode
        }
        val confirmed = try { reads.confirmedIds(lease, expected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return }
        pending.forEach { command ->
            gate.check(lease)
            val input = command.input as SubmitUnitInspectionInput
            if (input.unitInspectionId in confirmed)
                queue.finish(command, CommandOutcome.Success(UnitInspectionWriteReceipt(input.operationName,
                    requireNotNull(input.unitInspectionId)).encode()), now())
        }
    }

    private suspend fun find(lease: ScopeLease, input: SubmitUnitInspectionInput): Boolean {
        val id = requireNotNull(input.unitInspectionId)
        val confirmed = reads.confirmedIds(lease, mapOf(id to input.unitCode))
        gate.check(lease)
        return id in confirmed
    }

    private fun context(lease: ScopeLease): QueueContext? {
        try { gate.check(lease) } catch (_: RequestContextChanged) { return null }
        val session = auth.session.value ?: return null
        return QueueContext(lease.scope, lease.revision, authenticated = session.farmId == lease.scope.farmId,
            goodNetwork = online())
    }
}
