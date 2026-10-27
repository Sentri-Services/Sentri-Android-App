package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.therapy.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class TherapyProcessRestoreTest {
    @Test fun successfulAndUnknownBatchStepsSurviveProcessStopWithoutReplayingEarlierWrites() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("therapyStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "therapy-process-test"; val prefs = context.getSharedPreferences(name, 0); val f = TherapyFixture
        if (stage == "prepare") {
            context.deleteDatabase(name); check(prefs.edit().clear().putInt("pid", Process.myPid()).commit())
            SqliteCommandQueue(context, name).use { queue ->
                queue.enqueueTherapySteps(f.scope, "batch", f.steps, emptySet(), 0)
                val first = queue.claim(f.ctx, 1)!!; queue.finish(first, CommandOutcome.Success(f.receipt(f.complete)), 2)
                assertEquals("SkipTherapyTask", queue.claim(f.ctx, 3)!!.input.operationName)
            }
            println("THERAPY_PREPARED pid=${Process.myPid()} commands=3 successfulSteps=1 inFlightSteps=1")
        } else {
            try {
                val old = prefs.getInt("pid", -1); assertNotEquals(old, Process.myPid())
                SqliteCommandQueue(context, name).use { queue ->
                    assertEquals(1, queue.recoverInterrupted(f.scope, 4))
                    val all = queue.list(f.scope)
                    val saved = f.steps.indices.map { step -> all.single { it.record.operationId == "therapy-step:5:batch:$step" } }
                    assertEquals(listOf(CommandStatus.SYNCED, CommandStatus.UNCERTAIN, CommandStatus.QUEUED), saved.map { it.record.status })
                    assertEquals(f.steps, saved.map { it.input }); assertEquals(f.receipt(f.complete), saved.first().receipt)
                    val calls = mutableListOf<String>()
                    val gateway = object : TherapyGateway {
                        override suspend fun execute(input: TherapyCommandInput): CommandOutcome { calls += input.operationName; return CommandOutcome.Success(f.receipt(input)) }
                    }
                    val dispatcher = CommandDispatcher(queue, TherapyTransport(gateway) { f.ctx }, { f.ctx }) { 5 }
                    assertFalse(dispatcher.drainOne()); assertTrue(calls.isEmpty())
                    queue.finish(saved[1], CommandOutcome.Success(f.receipt(f.skip)), 6)
                    assertTrue(dispatcher.drainOne()); assertEquals(listOf("TerminatePrescription"), calls)
                    assertFalse(dispatcher.drainOne()); assertEquals(listOf(1, 1, 1), queue.list(f.scope).map { it.record.attempts })
                    assertTrue(queue.list(f.scope).all { it.record.status == CommandStatus.SYNCED })
                    println("THERAPY_RESTORED previousPid=$old pid=${Process.myPid()} commands=3 successfulStepsNotReplayed=true unknownResends=0 afterConfirmationCalls=TerminatePrescription receiptsBound=true")
                }
            } finally { context.deleteDatabase(name); prefs.edit().clear().commit() }
        }
    }
}
