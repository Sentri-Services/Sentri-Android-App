package com.sentri.nativeapp.pigs

import com.sentri.nativeapp.graphql.NativeReportPigDeathMutation
import com.sentri.nativeapp.graphql.NativeUploadPigDeathImageMutation
import com.sentri.nativeapp.graphql.type.ProductionReportPigDeathCommandStrawberryInput
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*

interface PigDeathGateway {
    suspend fun upload(input: UploadPigDeathImageInput): CommandOutcome
    suspend fun report(input: ProductionReportPigDeathCommandStrawberryInput): CommandOutcome
}
/** Explicit adapters only. Neither this gateway nor its transport is registered for real writes in AppContainer. */
class GraphqlPigDeathGateway(private val executor: GraphqlExecutor) : PigDeathGateway {
    override suspend fun upload(input: UploadPigDeathImageInput): CommandOutcome {
        val response = executor.mutation(NativeUploadPigDeathImageMutation(input.graphql())).production.upload_pig_death_image
        return when (response.status.rawValue) {
            "SUCCESS" -> CommandOutcome.Success(DeathImageReceipt(response.file_key, response.file_url).encode())
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
    override suspend fun report(input: ProductionReportPigDeathCommandStrawberryInput): CommandOutcome {
        val response = executor.mutation(NativeReportPigDeathMutation(input)).production.report_pig_death
        return when (response.status.rawValue) {
            "SUCCESS" -> CommandOutcome.Success("report_pig_death:SUCCESS")
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}
/** Exactly one remote operation per claimed queue attempt; image receipts are reused on report retries. */
class PigDeathTransport(private val store: CommandQueueStore, private val gateway: PigDeathGateway,
    private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        return when (val input = command.input) {
            is UploadPigDeathImageInput -> {
                if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.upload(input)
            }
            is ReportPigDeathInput -> {
                val scoped = store.list(command.record.scope)
                if (!DeathCommandReferences.ready(command, scoped)) return CommandOutcome.NotSent("DEATH_IMAGES_UNCONFIRMED")
                val urls = DeathCommandReferences.urls(command, scoped)
                val resolved = input.graphql { requireNotNull(urls[it]) }
                if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.report(resolved)
            }
            else -> error("Unsupported death command")
        }
    }
}
