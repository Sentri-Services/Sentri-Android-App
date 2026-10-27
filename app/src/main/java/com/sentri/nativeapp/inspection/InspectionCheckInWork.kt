package com.sentri.nativeapp.inspection

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sentri.nativeapp.SentriApplication
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.offline.QueuedCommand
import com.sentri.nativeapp.pigs.DataScope
import com.sentri.nativeapp.pigs.ScopeLease
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Schedules only unit check-ins. Other offline operations retain their own unsent queue records. */
class InspectionCheckInWork(private val context: Context) {
    fun schedule(lease: ScopeLease, command: QueuedCommand) {
        require(command.record.scope == lease.scope && command.input is SubmitUnitInspectionInput)
        enqueue(lease.scope, command.record.id)
    }

    fun ensureScheduled(lease: ScopeLease) {
        val container = (context.applicationContext as SentriApplication).container
        container.commandQueue.list(lease.scope).filter { it.input is SubmitUnitInspectionInput }.forEach { command ->
            if (command.record.status in pendingStatuses) enqueue(lease.scope, command.record.id)
            else WorkManager.getInstance(context).cancelUniqueWork(workName(lease.scope, command.record.id, ""))
        }
    }

    private fun enqueue(scope: DataScope, commandId: String) {
        val request = OneTimeWorkRequestBuilder<InspectionCheckInWorker>()
            .setInputData(workDataOf("environment" to scope.environment, "oauth" to scope.oauthId,
                "user" to scope.userId, "farm" to scope.farmId, "command" to commandId))
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(scope, commandId, "v2-"), ExistingWorkPolicy.KEEP, request)
    }

    private fun networkConstraints(): Constraints = Constraints.Builder().apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setRequiredNetworkRequest(NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), NetworkType.CONNECTED)
        } else {
            setRequiredNetworkType(NetworkType.CONNECTED)
        }
    }.build()

    companion object {
        internal val pendingStatuses = setOf(CommandStatus.QUEUED, CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN)
    }
}

private fun workName(scope: DataScope, commandId: String, version: String): String {
    val value = listOf(scope.environment, scope.oauthId, scope.userId, scope.farmId, commandId)
        .joinToString("") { "${it.length}:$it" }
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return "sentri-inspection-check-in-$version" + digest.joinToString("") { "%02x".format(it) }
}

/** Rechecks the online account before claiming a persisted command after process restart or reboot. */
class InspectionCheckInWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val values = listOf("environment", "oauth", "user", "farm").map { inputData.getString(it) }
        val commandId = inputData.getString("command")
        if (values.any { it.isNullOrBlank() } || commandId.isNullOrBlank()) return Result.failure()
        val scope = DataScope(values[0]!!, values[1]!!, values[2]!!, values[3]!!)
        val container = (applicationContext as SentriApplication).container
        val session = container.auth.session.value
        if (session?.oauthId != scope.oauthId || session?.farmId != scope.farmId) return Result.success()
        return try {
            val account = container.farms.account()
            if (account.cachedAtMillis != null) return Result.retry()
            val lease = container.scopeGate.lease(account)
            if (lease.scope != scope) return Result.success()
            container.inspectionCheckIn.sync(lease)
            val command = container.commandQueue.list(scope).firstOrNull {
                it.record.id == commandId && it.input is SubmitUnitInspectionInput
            }
            if (command?.record?.status in InspectionCheckInWork.pendingStatuses) Result.retry() else {
                // A pre-upgrade CONNECTED job can remain blocked on a partially connected network.
                WorkManager.getInstance(applicationContext).cancelUniqueWork(workName(scope, commandId, ""))
                Result.success()
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { Result.retry() }
    }
}
