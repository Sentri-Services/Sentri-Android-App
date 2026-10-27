package com.sentri.nativeapp.sampling

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeSubmitSampledMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

/** A successful response must identify the submitted task; record is optional in this resolver. */
data class SamplingWriteReceipt(val operation: String, val taskId: String) {
    init { require(operation.isNotBlank() && taskId.isNotBlank()) }
    fun validate(input: SubmitSampledInput) {
        require(operation == input.operationName && taskId == input.samplingTaskId)
    }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("task"); value(taskId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): SamplingWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var task: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "task" -> { require(reader.peek() == JsonReader.Token.STRING); task = reader.nextString() }
                    else -> error("Invalid sampling receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "task") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            SamplingWriteReceipt(requireNotNull(operation), requireNotNull(task))
        }
    }
}

interface SamplingGateway { suspend fun submit(input: SubmitSampledInput): CommandOutcome }

/** Testable adapter only: no mutation sender is registered in the app. */
class GraphqlSamplingGateway(private val executor: GraphqlExecutor) : SamplingGateway {
    override suspend fun submit(input: SubmitSampledInput): CommandOutcome {
        val response = executor.mutation(NativeSubmitSampledMutation(input.graphql())).biosafety.submit_sampled
        return when (response.status.rawValue) {
            "SUCCESS" -> {
                val receipt = try {
                    require(response.record == null || response.record.sampling_task_id == response.recordId)
                    SamplingWriteReceipt(input.operationName, response.recordId).also { it.validate(input) }
                } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
                CommandOutcome.Success(receipt.encode())
            }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class SamplingTransport(private val gateway: SamplingGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? SubmitSampledInput ?: error("Unsupported sampling operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.submit(input)
    }
}
