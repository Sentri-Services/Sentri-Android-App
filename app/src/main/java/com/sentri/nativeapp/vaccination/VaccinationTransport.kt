package com.sentri.nativeapp.vaccination

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeExecuteVaccinationTaskMutation
import com.sentri.nativeapp.graphql.NativeFinishVaccinationTaskMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

/** A SUCCESS receipt must identify the exact task and operation before a durable attempt is acknowledged. */
data class VaccinationWriteReceipt(val operation: String, val recordId: String) {
    init { require(operation.isNotBlank() && recordId.isNotBlank()) }
    fun validate(input: VaccinationCommandInput) {
        require(operation == input.operationName && recordId == input.vaccinationTaskId)
    }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("record"); value(recordId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): VaccinationWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var record: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "record" -> { require(reader.peek() == JsonReader.Token.STRING); record = reader.nextString() }
                    else -> error("Invalid vaccination receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "record") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            VaccinationWriteReceipt(requireNotNull(operation), requireNotNull(record))
        }
    }
}

interface VaccinationGateway { suspend fun execute(input: VaccinationCommandInput): CommandOutcome }

/** Testable mutation adapter; no sender or background replay is registered in AppContainer. */
class GraphqlVaccinationGateway(private val executor: GraphqlExecutor) : VaccinationGateway {
    override suspend fun execute(input: VaccinationCommandInput): CommandOutcome {
        val status: String; val recordId: String
        when (input) {
            is ExecuteVaccinationTaskInput -> executor.mutation(NativeExecuteVaccinationTaskMutation(input.graphql()))
                .biosafety.execute_vaccination_task.let { status = it.status.rawValue; recordId = it.recordId }
            is FinishVaccinationTaskInput -> executor.mutation(NativeFinishVaccinationTaskMutation(input.graphql()))
                .biosafety.finish_vaccination_task.let { status = it.status.rawValue; recordId = it.recordId }
        }
        return when (status) {
            "SUCCESS" -> {
                val receipt = try { VaccinationWriteReceipt(input.operationName, recordId).also { it.validate(input) } }
                catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
                CommandOutcome.Success(receipt.encode())
            }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class VaccinationTransport(private val gateway: VaccinationGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? VaccinationCommandInput ?: error("Unsupported vaccination operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.execute(input)
    }
}
