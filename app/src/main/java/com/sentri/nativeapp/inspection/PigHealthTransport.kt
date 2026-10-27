package com.sentri.nativeapp.inspection

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class PigHealthWriteReceipt(val operation: String, val pigId: String) {
    init { require(operation in setOf("AddPigDisease", "AddPigSymptom", "RemovePigDisease", "RemovePigSymptom",
        "SetPigTriageLevel", "SetPigBodyTemperature") && pigId.isNotBlank()) }
    fun validate(input: PigHealthCommandInput) { require(operation == input.operationName && pigId == input.pigId) }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("pig"); value(pigId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): PigHealthWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var pig: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "pig" -> { require(reader.peek() == JsonReader.Token.STRING); pig = reader.nextString() }
                    else -> error("Invalid health receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "pig") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            PigHealthWriteReceipt(requireNotNull(operation), requireNotNull(pig))
        }
    }
}

interface PigHealthGateway { suspend fun send(input: PigHealthCommandInput): CommandOutcome }

/** Six current health mutations; image upload and record linkage are separate concerns. */
class GraphqlPigHealthGateway(private val executor: GraphqlExecutor) : PigHealthGateway {
    override suspend fun send(input: PigHealthCommandInput): CommandOutcome {
        val response = when (input) {
            is AddPigDiseaseInput -> executor.mutation(NativeAddPigDiseaseMutation(input.graphql())).biosafety.add_pig_disease
                .let { it.status.rawValue to it.recordId }
            is AddPigSymptomInput -> executor.mutation(NativeAddPigSymptomMutation(input.graphql())).biosafety.add_pig_symptom
                .let { it.status.rawValue to it.recordId }
            is RemovePigDiseaseInput -> executor.mutation(NativeRemovePigDiseaseMutation(input.graphql())).biosafety.remove_pig_disease
                .let { it.status.rawValue to it.recordId }
            is RemovePigSymptomInput -> executor.mutation(NativeRemovePigSymptomMutation(input.graphql())).biosafety.remove_pig_symptom
                .let { it.status.rawValue to it.recordId }
            is SetPigTriageLevelInput -> executor.mutation(NativeSetPigTriageLevelMutation(input.graphql())).biosafety.set_pig_triage_level
                .let { it.status.rawValue to it.recordId }
            is SetPigBodyTemperatureInput -> executor.mutation(NativeSetPigBodyTemperatureMutation(input.graphql())).biosafety.set_pig_body_temperature
                .let { it.status.rawValue to it.recordId }
        }
        return when (response.first) {
            "SUCCESS" -> try { CommandOutcome.Success(PigHealthWriteReceipt(input.operationName, response.second)
                .also { it.validate(input) }.encode()) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class PigHealthTransport(private val gateway: PigHealthGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input as? PigHealthCommandInput ?: error("Unsupported health operation")
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.send(input)
    }
}
