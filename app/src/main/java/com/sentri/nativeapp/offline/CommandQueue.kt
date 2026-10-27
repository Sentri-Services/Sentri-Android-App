package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.DataScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stable business payload only; no Apollo objects, credentials or display text. */
interface CommandInput {
    val operationName: String
    val productionType: String?
    val taskId: String?
    val targetPigIds: Set<String>
    val groupKeys: Set<String> get() = buildSet {
        taskId?.let { add("task:$it") }
        targetPigIds.forEach { add("pig:$it") }
    }
}
data class QueuedCommand(val record: CommandRecord, val input: CommandInput, val receipt: String? = null, val requestedDependencies: Set<String> = emptySet())
data class QueueContext(val scope: DataScope, val revision: Long, val authenticated: Boolean, val goodNetwork: Boolean)
sealed interface CommandOutcome {
    data class Success(val receipt: String) : CommandOutcome
    data class Rejected(val code: String, val conflict: Boolean = false) : CommandOutcome
    data class NotSent(val code: String) : CommandOutcome
    data object Uncertain : CommandOutcome
}
interface CommandTransport { suspend fun send(command: QueuedCommand): CommandOutcome }
interface CommandQueueStore {
    fun enqueue(scope: DataScope, operationId: String, input: CommandInput, dependsOn: Set<String>, createdAt: Long): QueuedCommand
    fun list(scope: DataScope): List<QueuedCommand>
    fun claim(context: QueueContext, now: Long): QueuedCommand?
    fun finish(attempt: QueuedCommand, outcome: CommandOutcome, now: Long)
    fun recoverInterrupted(scope: DataScope, now: Long): Int
    fun cancel(scope: DataScope, id: String, now: Long)
    fun retryAfterConfirmation(scope: DataScope, id: String, now: Long, outcomeConfirmed: Boolean)
}
/** Caller-driven dispatcher; feature senders must limit claims to operations they implement. */
class CommandDispatcher(private val store: CommandQueueStore, private val transport: CommandTransport,
    private val context: () -> QueueContext?,
    private val claim: (QueueContext, Long) -> QueuedCommand? = store::claim,
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    suspend fun drainOne(): Boolean = withContext(Dispatchers.IO) { mutex.withLock {
        val lease = context() ?: return@withLock false
        val command = claim(lease, now()) ?: return@withLock false
        if (context() != lease) {
            // No transport call occurred, so this attempt can return to queued with a delay.
            store.finish(command, CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND"), now())
            return@withLock false
        }
        val result = try { transport.send(command) }
        catch (cancelled: CancellationException) { store.finish(command, CommandOutcome.Uncertain, now()); throw cancelled }
        catch (_: Exception) { CommandOutcome.Uncertain }
        // A known receipt belongs to the original scope even if the user switched during send.
        store.finish(command, result, now())
        true
    } }
}
