package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.DataScope

enum class CommandStatus { QUEUED, IN_FLIGHT, UNCERTAIN, SYNCED, FAILED, CONFLICTED, CANCELLED }
data class CommandRecord(
    val id: String, val operationId: String, val scope: DataScope, val operationName: String,
    val productionType: String? = null, val groupKeys: Set<String> = emptySet(), val dependsOn: Set<String> = emptySet(),
    val createdAtMillis: Long, val status: CommandStatus = CommandStatus.QUEUED,
    val attempts: Int = 0, val nextRetryAtMillis: Long? = null,
) {
    init { require(id.isNotBlank() && operationId.isNotBlank() && operationName.isNotBlank() && attempts >= 0) }
    val groups: Set<String> get() = groupKeys.filter(String::isNotBlank).toSet().ifEmpty { setOf("command:$id") }
}
enum class CommandBlock { CONTEXT, NETWORK, STATUS, DELAY, DEPENDENCY, GROUP }

/** Scheduling contract only: no HTTP sender, automatic replay, or business payload migration. */
object CommandPolicy {
    private val resolved = setOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)
    fun before(left: CommandRecord, right: CommandRecord): Boolean {
        if (left.createdAtMillis != right.createdAtMillis) return left.createdAtMillis < right.createdAtMillis
        if (left.productionType == "estrus" && right.productionType == "estrus") {
            if (left.operationName == "CheckEstrus" && right.operationName == "SubmitEstrusStatus") return true
            if (left.operationName == "SubmitEstrusStatus" && right.operationName == "CheckEstrus") return false
        }
        if (left.productionType == "estrus" && right.productionType == "mating") return true
        if (left.productionType == "mating" && right.productionType == "estrus") return false
        return left.id < right.id
    }
    fun blocked(command: CommandRecord, all: List<CommandRecord>, current: DataScope?, authenticated: Boolean,
                goodNetwork: Boolean, nowMillis: Long): CommandBlock? {
        if (!authenticated || current != command.scope) return CommandBlock.CONTEXT
        if (!goodNetwork) return CommandBlock.NETWORK
        if (command.status != CommandStatus.QUEUED) return CommandBlock.STATUS
        if ((command.nextRetryAtMillis ?: Long.MIN_VALUE) > nowMillis) return CommandBlock.DELAY
        // Dependencies never resolve through another user's or farm's command with the same ID.
        val scoped = all.filter { it.scope == current }
        val indexed = scoped.associateBy { it.id }
        if (command.dependsOn.any { indexed[it]?.status !in resolved }) return CommandBlock.DEPENDENCY
        fun dependsOnCommand(candidate: CommandRecord): Boolean {
            val pending = ArrayDeque(candidate.dependsOn)
            val visited = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val id = pending.removeFirst()
                if (id == command.id) return true
                if (visited.add(id)) indexed[id]?.dependsOn?.let(pending::addAll)
            }
            return false
        }
        // Explicit dependency order wins over wall-clock order, including when the device clock moved backwards.
        if (scoped.any { it.id != command.id && it.status !in resolved && before(it, command) &&
                it.groups.any(command.groups::contains) && !dependsOnCommand(it) }) return CommandBlock.GROUP
        return null
    }
    fun start(command: CommandRecord): CommandRecord {
        check(command.status == CommandStatus.QUEUED)
        return command.copy(status = CommandStatus.IN_FLIGHT, attempts = command.attempts + 1, nextRetryAtMillis = null)
    }
    fun acknowledge(command: CommandRecord, status: CommandStatus): CommandRecord {
        check(command.status == CommandStatus.IN_FLIGHT || command.status == CommandStatus.UNCERTAIN)
        require(status in setOf(CommandStatus.SYNCED, CommandStatus.FAILED, CommandStatus.CONFLICTED))
        return command.copy(status = status)
    }
    // A timeout or process death does not prove that the server rejected a write.
    fun recover(command: CommandRecord): CommandRecord = if (command.status == CommandStatus.IN_FLIGHT) command.copy(status = CommandStatus.UNCERTAIN) else command
    fun cancel(command: CommandRecord): CommandRecord {
        check(command.status in setOf(CommandStatus.QUEUED, CommandStatus.FAILED, CommandStatus.CONFLICTED))
        return command.copy(status = CommandStatus.CANCELLED, nextRetryAtMillis = null)
    }
    /** Call only with evidence that no write was accepted, or an operation-specific dedupe contract. */
    fun retryConfirmed(command: CommandRecord, nowMillis: Long, outcomeConfirmed: Boolean): CommandRecord {
        check(outcomeConfirmed)
        check(command.status in setOf(CommandStatus.UNCERTAIN, CommandStatus.FAILED, CommandStatus.CONFLICTED))
        val delays = longArrayOf(5_000, 15_000, 60_000, 300_000)
        val delay = delays[(command.attempts - 1).coerceIn(0, delays.lastIndex)]
        return command.copy(status = CommandStatus.QUEUED, nextRetryAtMillis = Math.addExact(nowMillis, delay))
    }
}
