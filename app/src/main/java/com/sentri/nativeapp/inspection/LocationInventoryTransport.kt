package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeReconcileLocationInventoryMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class LocationInventoryReceipt(val locationCode: String) {
    init { require(locationCode.isNotBlank()) }
    fun validate(input: ReconcileLocationInventoryInput) { require(locationCode == input.locationCode) }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("location"); value(locationCode); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): LocationInventoryReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var location: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "location" -> { require(reader.peek() == JsonReader.Token.STRING); location = reader.nextString() }
                    else -> error("Invalid location inventory receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "location") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            LocationInventoryReceipt(requireNotNull(location))
        }
    }
}

interface LocationInventoryGateway { suspend fun reconcile(input: ReconcileLocationInventoryInput): CommandOutcome }

class GraphqlLocationInventoryGateway(private val executor: GraphqlExecutor) : LocationInventoryGateway {
    override suspend fun reconcile(input: ReconcileLocationInventoryInput): CommandOutcome {
        val response = executor.mutation(NativeReconcileLocationInventoryMutation(input.graphql()))
            .production.reconcile_location_inventory
        return when (response.status.rawValue) {
            "SUCCESS" -> try { CommandOutcome.Success(LocationInventoryReceipt(response.location_code)
                .also { it.validate(input) }.encode()) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class LocationInventoryTransport(private val gateway: LocationInventoryGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? ReconcileLocationInventoryInput ?: error("Unsupported inventory operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.reconcile(input)
    }
}
