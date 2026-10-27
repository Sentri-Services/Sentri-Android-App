package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.NativeRecordPigBackfatMutation
import com.sentri.nativeapp.graphql.NativeRecordPigTemperatureMutation
import com.sentri.nativeapp.graphql.NativeRecordPigWeightsMutation
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class PigMeasurementReceipt(val operation: String, val recordIds: List<String>) {
    init {
        require(operation in setOf("RecordPigWeights", "RecordPigBackfat", "RecordPigTemperature"))
        require(recordIds.all(String::isNotBlank))
    }
    fun validate(input: PigMeasurementCommandInput) {
        require(operation == input.operationName)
        val expected = when (input) {
            is RecordPigWeightsInput -> input.records.orEmpty().map(PigWeightItem::pigId)
            is RecordPigBackfatInput -> listOf(input.pigId)
            is RecordPigTemperatureInput -> listOf(input.pigId)
        }
        require(recordIds == expected)
    }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("records"); beginArray(); recordIds.forEach(::value); endArray(); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): PigMeasurementReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var records: List<String>? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "records" -> {
                        val values = mutableListOf<String>(); reader.beginArray()
                        while (reader.hasNext()) { require(reader.peek() == JsonReader.Token.STRING); values += requireNotNull(reader.nextString()) }
                        reader.endArray(); records = values
                    }
                    else -> error("Invalid measurement receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "records") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            PigMeasurementReceipt(requireNotNull(operation), requireNotNull(records))
        }
    }
}

interface PigMeasurementGateway { suspend fun send(input: PigMeasurementCommandInput): CommandOutcome }

class GraphqlPigMeasurementGateway(private val executor: GraphqlExecutor) : PigMeasurementGateway {
    override suspend fun send(input: PigMeasurementCommandInput): CommandOutcome {
        val result = when (input) {
            is RecordPigWeightsInput -> executor.mutation(NativeRecordPigWeightsMutation(input.graphql()))
                .production.record_pig_weights.let { it.status.rawValue to it.recordIds }
            is RecordPigBackfatInput -> executor.mutation(NativeRecordPigBackfatMutation(input.graphql()))
                .production.record_pig_backfat.let { it.status.rawValue to listOf(it.recordId) }
            is RecordPigTemperatureInput -> executor.mutation(NativeRecordPigTemperatureMutation(input.graphql()))
                .production.record_pig_temperature.let { it.status.rawValue to listOf(it.recordId) }
        }
        return when (result.first) {
            "SUCCESS" -> try { CommandOutcome.Success(PigMeasurementReceipt(input.operationName, result.second)
                .also { it.validate(input) }.encode()) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class PigMeasurementTransport(private val gateway: PigMeasurementGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? PigMeasurementCommandInput ?: error("Unsupported measurement operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.send(input)
    }
}
