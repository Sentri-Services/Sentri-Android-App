package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeReportDeviceInspectionMutation
import com.sentri.nativeapp.graphql.NativeResolveDeviceInspectionMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class DeviceInspectionWriteReceipt(val operation: String, val recordId: String) {
    init { require(operation in setOf("ReportDeviceInspection", "ResolveDeviceInspection") && recordId.isNotBlank()) }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("recordId"); value(recordId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): DeviceInspectionWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var recordId: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "recordId" -> { require(reader.peek() == JsonReader.Token.STRING); recordId = reader.nextString() }
                    else -> error("Invalid device inspection receipt")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "recordId") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            DeviceInspectionWriteReceipt(requireNotNull(operation), requireNotNull(recordId))
        }
    }
}

interface DeviceInspectionGateway {
    suspend fun report(input: ReportDeviceInspectionInput): CommandOutcome
    suspend fun resolve(input: ResolveDeviceInspectionInput): CommandOutcome
}

/** Testable wire adapter. Registration as an automatic queue sender is deliberately separate. */
class GraphqlDeviceInspectionGateway(private val executor: GraphqlExecutor) : DeviceInspectionGateway {
    override suspend fun report(input: ReportDeviceInspectionInput): CommandOutcome {
        val response = executor.mutation(NativeReportDeviceInspectionMutation(input.graphql())).biosafety.report_device_inspection
        return outcome(input.operationName, response.status.rawValue, response.recordId)
    }
    override suspend fun resolve(input: ResolveDeviceInspectionInput): CommandOutcome {
        val response = executor.mutation(NativeResolveDeviceInspectionMutation(input.graphql())).biosafety.resolve_device_inspection
        return outcome(input.operationName, response.status.rawValue, response.recordId)
    }
    private fun outcome(operation: String, status: String, recordId: String): CommandOutcome = when (status) {
        "SUCCESS" -> try { CommandOutcome.Success(DeviceInspectionWriteReceipt(operation, recordId).encode()) }
            catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
        "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
        else -> throw ApiFailure.Protocol()
    }
}

class DeviceInspectionTransport(private val gateway: DeviceInspectionGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        if (context() != lease) return CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND")
        return when (val input = command.input) {
            is ReportDeviceInspectionInput -> gateway.report(input)
            is ResolveDeviceInspectionInput -> gateway.resolve(input)
            else -> error("Unsupported device inspection operation")
        }
    }
}
