package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeSubmitUnitInspectionMutation
import com.sentri.nativeapp.graphql.NativeSubmitUnitRemarkMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class UnitInspectionWriteReceipt(val operation: String, val recordId: String) {
    init { require(operation in setOf("SubmitUnitInspection", "SubmitUnitRemark") && recordId.isNotBlank()) }
    fun validate(input: CommandInput) {
        require(operation == input.operationName)
        val expected = when (input) {
            is SubmitUnitInspectionInput -> input.unitInspectionId
            is SubmitUnitRemarkInput -> input.unitInspectionId
            else -> error("Unsupported unit inspection input")
        }
        require(expected == null || expected == recordId)
    }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("recordId"); value(recordId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): UnitInspectionWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var id: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "recordId" -> { require(reader.peek() == JsonReader.Token.STRING); id = reader.nextString() }
                    else -> error("Invalid unit inspection receipt")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "recordId") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            UnitInspectionWriteReceipt(requireNotNull(operation), requireNotNull(id))
        }
    }
}

interface UnitInspectionGateway {
    suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome
    suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome
}

class GraphqlUnitInspectionGateway(private val executor: GraphqlExecutor) : UnitInspectionGateway {
    override suspend fun submit(input: SubmitUnitInspectionInput): CommandOutcome {
        val response = executor.mutation(NativeSubmitUnitInspectionMutation(input.graphql())).biosafety.submit_unit_inspection
        return outcome(input, response.status.rawValue, response.recordId)
    }
    override suspend fun remark(input: SubmitUnitRemarkInput): CommandOutcome {
        val response = executor.mutation(NativeSubmitUnitRemarkMutation(input.graphql())).biosafety.submit_unit_remark
        return outcome(input, response.status.rawValue, response.recordId)
    }
    private fun outcome(input: CommandInput, status: String, recordId: String): CommandOutcome = when (status) {
        "SUCCESS" -> try { CommandOutcome.Success(UnitInspectionWriteReceipt(input.operationName, recordId)
            .also { it.validate(input) }.encode()) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
        else -> throw ApiFailure.Protocol()
    }
}

/** Explicit caller-driven transport; not registered for background replay. */
class UnitInspectionTransport(private val gateway: UnitInspectionGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        if (context() != lease) return CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND")
        return when (val input = command.input) {
            is SubmitUnitInspectionInput -> gateway.submit(input)
            is SubmitUnitRemarkInput -> gateway.remark(input)
            else -> error("Unsupported unit inspection operation")
        }
    }
}
