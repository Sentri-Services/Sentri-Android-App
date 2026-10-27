package com.sentri.nativeapp.therapy

import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import com.apollographql.apollo.api.json.*
import okio.Buffer

/** recordId is the task ID for execution/skip, the prescription ID for add/remove/termination. */
data class TherapyWriteReceipt(val operation: String, val recordId: String) {
    init { require(operation.isNotBlank() && recordId.isNotBlank()) }
    fun validate(input: TherapyCommandInput) {
        require(operation == input.operationName)
        val expected = when (input) {
            is CompleteTherapyTargetInput -> input.therapyTaskId
            is SkipTherapyTaskInput -> input.therapyTaskId
            else -> input.prescriptionId
        }
        require(recordId == expected)
    }
    fun encode(): String {
        val buffer = Buffer()
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation); name("record"); value(recordId); endObject(); flush()
        }
        return buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): TherapyWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var record: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "record" -> { require(reader.peek() == JsonReader.Token.STRING); record = reader.nextString() }
                    else -> error("Invalid therapy receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "record") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            TherapyWriteReceipt(requireNotNull(operation), requireNotNull(record))
        }
    }
}
interface TherapyGateway { suspend fun execute(input: TherapyCommandInput): CommandOutcome }
/** Explicit testable adapter; AppContainer does not register this gateway or any mutation worker. */
class GraphqlTherapyGateway(private val executor: GraphqlExecutor) : TherapyGateway {
    override suspend fun execute(input: TherapyCommandInput): CommandOutcome {
        val status: String; val record: String
        when (input) {
            is CompleteTherapyTargetInput -> executor.mutation(NativeCompleteTherapyTargetMutation(input.graphql())).biosafety.complete_therapy_target.let { status = it.status.rawValue; record = it.recordId }
            is SkipTherapyTaskInput -> executor.mutation(NativeSkipTherapyTaskMutation(input.graphql())).biosafety.skip_therapy_task.let { status = it.status.rawValue; record = it.recordId }
            is AddPrescriptionTargetInput -> executor.mutation(NativeAddPrescriptionTargetMutation(input.graphql())).biosafety.add_prescription_target.let { status = it.status.rawValue; record = it.recordId }
            is RemovePrescriptionTargetInput -> executor.mutation(NativeRemovePrescriptionTargetMutation(input.graphql())).biosafety.remove_prescription_target.let { status = it.status.rawValue; record = it.recordId }
            is TerminateTherapyInput -> executor.mutation(NativeTerminateTherapyMutation(input.graphql())).biosafety.terminate_therapy.let { status = it.status.rawValue; record = it.recordId }
            is TerminatePrescriptionInput -> executor.mutation(NativeTerminatePrescriptionMutation(input.graphql())).biosafety.terminate_prescription.let { status = it.status.rawValue; record = it.recordId }
        }
        return when (status) {
            "SUCCESS" -> {
                val receipt = try { TherapyWriteReceipt(input.operationName, record).also { it.validate(input) } }
                catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
                CommandOutcome.Success(receipt.encode())
            }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}
class TherapyTransport(private val gateway: TherapyGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? TherapyCommandInput ?: error("Unsupported therapy operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.execute(input)
    }
}
