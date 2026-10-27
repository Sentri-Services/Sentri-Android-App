package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class PigManagementProcessRestoreTest {
    @Test fun propertyAndMultiPigRoleSurviveProcessStopWithoutUnknownResend() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("pigManagementStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "pig-management-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = PigManagementFixture
        val inputs = listOf(f.property, f.role, f.other); val queueContext = QueueContext(f.scope, 1, true, true)
        if (stage == "prepare") {
            context.deleteDatabase(name); check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteCommandQueue(context, name).use { queue ->
                inputs.forEachIndexed { i, input -> queue.enqueue(f.scope, "op" + i, input, emptySet(), i.toLong()) }
                assertEquals("op0", queue.claim(queueContext, 3)?.record?.operationId)
            }
            println("PIG_MANAGEMENT_PREPARED commands=3 property=IN_FLIGHT")
        } else {
            try {
                assertNotEquals(prefs.getInt("pid", -1), Process.myPid())
                SqliteCommandQueue(context, name).use { queue ->
                    val original = queue.list(f.scope); assertEquals(inputs, original.map { it.input })
                    assertEquals(1, queue.recoverInterrupted(f.scope, 4))
                    val sent = mutableListOf<String>()
                    val dispatcher = CommandDispatcher(queue, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sent += command.record.operationId; return CommandOutcome.Success("simulated") }
                    }, { queueContext }) { 5 }
                    assertTrue(dispatcher.drainOne()); assertFalse(dispatcher.drainOne()); assertEquals(listOf("op2"), sent)
                    queue.finish(original.first(), CommandOutcome.Success("simulated-confirmed"), 6)
                    assertEquals("op1", queue.claim(queueContext, 7)?.record?.operationId)
                }
                println("PIG_MANAGEMENT_RESTORED newProcess=true commands=3 decimalExact=true orderedParents=true unknownResends=0 multiPigRoleBlocked=true independentPig=1 confirmedThenRoleClaimed=true")
            } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
        }
    }
}
