package com.sentri.nativeapp

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.i18n.*
import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.production.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in two-stage test, isolated data and simulated transport only. Host stops the process between stages. */
class FoundationProcessRestoreTest {
    @Test fun languageAndUnacknowledgedCommandSurviveProcessStop() = runBlocking {
        val stage = InstrumentationRegistry.getArguments().getString("foundationStage")
        assumeTrue(stage == "prepare" || stage == "restore")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "foundation-process-test"
        val preferences = context.getSharedPreferences(name, 0)
        val scope = DataScope("simulated-dev", "simulated-oauth", "simulated-user", "simulated-farm")
        if (stage == "prepare") {
            context.deleteDatabase(name)
            preferences.edit().clear().commit()
            val language = AppLanguage(AndroidLanguageStore(context, name))
            language.select(LanguageChoice.ENGLISH)
            check(preferences.edit().putInt("previous_pid", Process.myPid()).commit())
            SqliteCommandQueue(context, name).use { store ->
                store.enqueue(scope, "simulated-operation", CheckEstrusInput("simulated-task", "simulated-pig", EstrusStatus.IN_ESTRUS, null), emptySet(), 1)
                store.enqueue(scope, "simulated-submit", SubmitEstrusInput("simulated-task", listOf("simulated-pig"), null), emptySet(), 2)
                assertNotNull(store.claim(QueueContext(scope, 1, true, true), 2))
            }
            println("FOUNDATION_PROCESS_PREPARED language=en command=IN_FLIGHT")
        } else {
            try {
                assertNotEquals(preferences.getInt("previous_pid", -1), Process.myPid())
                assertEquals(SupportedLanguage.ENGLISH, AppLanguage(AndroidLanguageStore(context, name)).current())
                SqliteCommandQueue(context, name).use { store ->
                    val old = store.list(scope).single { it.record.operationId == "simulated-operation" }
                    val batch = store.list(scope).single { it.record.operationId == "simulated-submit" }
                    assertTrue(batch.input is SubmitEstrusInput)
                    assertEquals(setOf(old.record.id), batch.record.dependsOn)
                    assertEquals(CommandStatus.IN_FLIGHT, old.record.status)
                    assertEquals(1, store.recoverInterrupted(scope, 3))
                    assertEquals(CommandStatus.UNCERTAIN, store.list(scope).single { it.record.id == old.record.id }.record.status)
                    var sends = 0
                    val dispatcher = CommandDispatcher(store, object : CommandTransport {
                        override suspend fun send(command: QueuedCommand): CommandOutcome { sends++; return CommandOutcome.Success("simulated-receipt") }
                    }, { QueueContext(scope, 1, true, true) }) { 100 }
                    assertFalse(dispatcher.drainOne()); assertEquals(0, sends)
                    store.finish(old, CommandOutcome.Success("simulated-confirmed-result"), 101)
                    assertEquals(CommandStatus.SYNCED, store.list(scope).single { it.record.id == old.record.id }.record.status)
                    assertEquals(batch.record.id, requireNotNull(store.claim(QueueContext(scope, 1, true, true), 102)).record.id)
                }
                println("FOUNDATION_PROCESS_RESTORED newProcess=true language=en uncertain=true automaticResends=0 confirmed=true typedSubmitRestored=true")
            } finally { context.deleteDatabase(name); preferences.edit().clear().commit() }
        }
    }
}
