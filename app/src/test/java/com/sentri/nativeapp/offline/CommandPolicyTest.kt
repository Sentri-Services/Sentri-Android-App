package com.sentri.nativeapp.offline

import com.sentri.nativeapp.pigs.DataScope
import org.junit.Assert.*
import org.junit.Test

class CommandPolicyTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private fun command(id: String = "a") = CommandRecord(id, "operation-$id", scope, "CheckEstrus", "estrus", setOf("pig:p1"), createdAtMillis = 100)
    private fun blocked(command: CommandRecord, all: List<CommandRecord>) = CommandPolicy.blocked(command, all, scope, true, true, 1000)
    @Test fun `explicit dependencies wait for completion cancellation and correct scope`() {
        val a = command(); val b = command("b").copy(dependsOn = setOf(a.id), groupKeys = setOf("other"))
        assertEquals(CommandBlock.DEPENDENCY, blocked(b, listOf(b)))
        for (status in listOf(CommandStatus.QUEUED, CommandStatus.FAILED, CommandStatus.CONFLICTED, CommandStatus.UNCERTAIN)) assertEquals(CommandBlock.DEPENDENCY, blocked(b, listOf(a.copy(status = status), b)))
        for (status in listOf(CommandStatus.SYNCED, CommandStatus.CANCELLED)) assertNull(blocked(b, listOf(a.copy(status = status), b)))
        assertEquals(CommandBlock.DEPENDENCY, blocked(b, listOf(a.copy(scope = scope.copy(farmId = "other"), status = CommandStatus.SYNCED), b)))
    }
    @Test fun `shared entities serialize and failed predecessor blocks without affecting another scope`() {
        val a = command(); val b = command("b").copy(createdAtMillis = 101)
        assertEquals(CommandBlock.GROUP, blocked(b, listOf(a.copy(status = CommandStatus.CONFLICTED), b)))
        assertNull(blocked(b, listOf(a.copy(status = CommandStatus.CANCELLED), b)))
        assertNull(blocked(b, listOf(a.copy(scope = scope.copy(userId = "other")), b)))
        assertNull(blocked(b.copy(groupKeys = setOf("pig:p2")), listOf(a, b)))
    }
    @Test fun `same timestamp preserves check submit and mating order`() {
        val check = command("z"); val submit = command("a").copy(operationName = "SubmitEstrusStatus")
        val mate = command("0").copy(operationName = "MateWithSemen", productionType = "mating")
        assertTrue(CommandPolicy.before(check, submit)); assertFalse(CommandPolicy.before(submit, check))
        assertEquals(CommandBlock.GROUP, blocked(submit, listOf(check, submit)))
        assertEquals(CommandBlock.GROUP, blocked(mate, listOf(submit, mate)))
    }
    @Test fun `auth context network and delay gate scheduling`() {
        val a = command()
        assertEquals(CommandBlock.CONTEXT, CommandPolicy.blocked(a, listOf(a), scope, false, true, 1000))
        assertEquals(CommandBlock.CONTEXT, CommandPolicy.blocked(a, listOf(a), scope.copy(environment = "prod"), true, true, 1000))
        assertEquals(CommandBlock.NETWORK, CommandPolicy.blocked(a, listOf(a), scope, true, false, 1000))
        assertEquals(CommandBlock.DELAY, blocked(a.copy(nextRetryAtMillis = 1001), listOf(a)))
    }
    @Test fun `process death or timeout requires reconciliation instead of replay`() {
        val started = CommandPolicy.start(command()); assertEquals(1, started.attempts)
        val uncertain = CommandPolicy.recover(started)
        assertEquals(CommandStatus.UNCERTAIN, uncertain.status)
        assertEquals(CommandBlock.STATUS, blocked(uncertain, listOf(uncertain)))
        try { CommandPolicy.retryConfirmed(uncertain, 1000, false); fail("Ambiguous write retried") } catch (_: IllegalStateException) { }
        assertEquals(CommandStatus.SYNCED, CommandPolicy.acknowledge(uncertain, CommandStatus.SYNCED).status)
        assertEquals(6000L, CommandPolicy.retryConfirmed(uncertain, 1000, true).nextRetryAtMillis)
        try { CommandPolicy.cancel(uncertain); fail("Unknown server outcome cancelled") } catch (_: IllegalStateException) { }
    }
}
