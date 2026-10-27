package com.sentri.nativeapp.sampling

import com.sentri.nativeapp.offline.*
import com.sentri.nativeapp.pigs.DataScope
import org.junit.Assert.*
import org.junit.Test

class SamplingProjectionTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    private val at = "2026-10-10T08:00:00.123456789+08:00"
    private val task = SamplingTask("task","farm","sampling","test",at,null,false,false,
        false,null,null,null,null,null,null,null,null,null,null,null,null,
        listOf(SamplingTarget(1,"farm","test","surface","ENVIRONMENT",null,null)),null,emptyList())
    private val input = SubmitSampledInput("task", "保留原始备注")
    private fun command(status: CommandStatus = CommandStatus.QUEUED) = QueuedCommand(
        CommandRecord("id", "operation", scope, input.operationName, null, input.groupKeys,
            createdAtMillis = 1, status = status), input)
    @Test fun queuedIntentChangesOnlyPreviewAndLeavesServerTimeUnknown() {
        val result = SamplingProjector.project(scope, task, listOf(command()))
        assertEquals(SamplingPreviewState.PREVIEW, result.effects.single().state)
        assertTrue(result.previewTask.isSampled)
        assertEquals("保留原始备注", result.previewTask.remark)
        assertNull(result.previewTask.sampledAt)
        assertNull(result.previewTask.sampledBy)
        assertEquals(task, result.serverTask)
        assertFalse(task.isSampled)
    }
    @Test fun unknownAndSyncedDoNotChangeConfirmedSnapshotOrPreview() {
        val unknown = SamplingProjector.project(scope, task, listOf(command(CommandStatus.UNCERTAIN)))
        assertEquals(SamplingPreviewState.WAITING_CONFIRMATION, unknown.effects.single().state)
        assertEquals(task, unknown.previewTask)
        val synced = SamplingProjector.project(scope, task, listOf(command(CommandStatus.SYNCED)))
        assertEquals(SamplingPreviewState.WAITING_REFRESH, synced.effects.single().state)
        assertEquals(task, synced.previewTask)
        val reflected = SamplingProjector.project(scope, task, listOf(command(CommandStatus.SYNCED)), setOf("id"))
        assertTrue(reflected.effects.isEmpty())
        assertEquals(task, reflected.previewTask)
        assertEquals(SamplingPreviewState.REJECTED,
            SamplingProjector.project(scope, task, listOf(command(CommandStatus.FAILED))).effects.single().state)
    }
    @Test fun disabledTaskNeverShowsSuccessfulPreviewAndScopeIsolated() {
        val disabled = SamplingProjector.project(scope, task.copy(disabled = true), listOf(command()))
        assertEquals(SamplingPreviewState.INVALID_CONTEXT, disabled.effects.single().state)
        assertFalse(disabled.previewTask.isSampled)
        val other = SamplingProjector.project(scope.copy(farmId = "other"),
            task.copy(farmId = "other", targets = task.targets.map { it.copy(farmId = "other") }), listOf(command()))
        assertTrue(other.effects.isEmpty())
    }
}
