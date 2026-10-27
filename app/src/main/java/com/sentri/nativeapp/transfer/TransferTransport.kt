package com.sentri.nativeapp.transfer

import com.apollographql.apollo.api.json.BufferedSinkJsonWriter
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.apollographql.apollo.api.json.JsonReader
import com.sentri.nativeapp.graphql.*
import com.sentri.nativeapp.network.ApiFailure
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.*
import okio.Buffer

data class TransferWriteReceipt(val operation: String, val subjectId: String) {
    init { require(operation in setOf("TransferOut", "TransferIn", "AddTransferTaskItems",
        "RemoveTransferTaskItems", "ResetTransferTaskItems", "AddTransferInUnit",
        "CompleteTransferTask", "TransferPig") && subjectId.isNotBlank()) }
    fun validate(input: CommandInput) {
        require(operation == input.operationName)
        require(subjectId == when (input) {
            is TransferTaskCommandInput -> input.transferId
            is TransferPigInput -> input.pigId
            else -> error("Unsupported transfer receipt")
        })
    }
    fun encode(): String = Buffer().let { buffer ->
        BufferedSinkJsonWriter(buffer).apply {
            beginObject(); name("format"); value(1); name("operation"); value(operation)
            name("subject"); value(subjectId); endObject(); flush()
        }
        buffer.readUtf8()
    }
    companion object {
        fun decode(encoded: String): TransferWriteReceipt = BufferedSourceJsonReader(Buffer().writeUtf8(encoded)).use { reader ->
            val seen = mutableSetOf<String>(); var operation: String? = null; var subject: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                val field = reader.nextName(); require(seen.add(field))
                when (field) {
                    "format" -> { require(reader.peek() in setOf(JsonReader.Token.NUMBER, JsonReader.Token.LONG)); require(reader.nextNumber().value == "1") }
                    "operation" -> { require(reader.peek() == JsonReader.Token.STRING); operation = reader.nextString() }
                    "subject" -> { require(reader.peek() == JsonReader.Token.STRING); subject = reader.nextString() }
                    else -> error("Invalid transfer receipt field")
                }
            }
            reader.endObject()
            require(seen == setOf("format", "operation", "subject") && reader.peek() == JsonReader.Token.END_DOCUMENT)
            TransferWriteReceipt(requireNotNull(operation), requireNotNull(subject))
        }
    }
}

interface TransferGateway { suspend fun send(input: CommandInput): CommandOutcome }

class GraphqlTransferGateway(private val executor: GraphqlExecutor) : TransferGateway {
    override suspend fun send(input: CommandInput): CommandOutcome {
        val response = when (input) {
            is TransferOutInput -> executor.mutation(NativeTransferOutMutation(input.graphql())).production.transfer_out
                .let { it.status.rawValue to it.transfer_id }
            is TransferInInput -> executor.mutation(NativeTransferInMutation(input.graphql())).production.transfer_in
                .let { it.status.rawValue to it.transfer_id }
            is AddTransferTaskItemsInput -> executor.mutation(NativeAddTransferTaskItemsMutation(input.graphql())).production.add_transfer_task_items
                .let { it.status.rawValue to it.transfer_id }
            is RemoveTransferTaskItemsInput -> executor.mutation(NativeRemoveTransferTaskItemsMutation(input.graphql())).production.remove_transfer_task_items
                .let { it.status.rawValue to it.transfer_id }
            is ResetTransferTaskItemsInput -> executor.mutation(NativeResetTransferTaskItemsMutation(input.graphql())).production.reset_transfer_task_items
                .let { it.status.rawValue to it.transfer_id }
            is AddTransferInUnitInput -> executor.mutation(NativeAddTransferInUnitMutation(input.graphql())).production.add_transfer_in_unit
                .let { it.status.rawValue to it.transfer_id }
            is CompleteTransferTaskInput -> executor.mutation(NativeCompleteTransferTaskMutation(input.graphql())).production.complete_transfer_task
                .let { it.status.rawValue to it.transfer_id }
            is TransferPigInput -> executor.mutation(NativeTransferPigMutation(input.graphql())).production.transfer_pig
                .let { it.status.rawValue to it.recordId }
            else -> error("Unsupported transfer operation")
        }
        return when (response.first) {
            "SUCCESS" -> try { CommandOutcome.Success(TransferWriteReceipt(input.operationName, response.second)
                .also { it.validate(input) }.encode()) } catch (_: IllegalArgumentException) { throw ApiFailure.Protocol() }
            "FAILURE" -> CommandOutcome.Rejected("BACKEND_REFUSED")
            else -> throw ApiFailure.Protocol()
        }
    }
}

class TransferTransport(private val gateway: TransferGateway, private val context: () -> QueueContext?) : CommandTransport {
    override suspend fun send(command: QueuedCommand): CommandOutcome {
        val lease = context()
        if (lease == null || lease.scope != command.record.scope || !lease.authenticated || !lease.goodNetwork)
            return CommandOutcome.NotSent("CONTEXT_UNAVAILABLE")
        require(command.record.status == CommandStatus.IN_FLIGHT)
        val input = command.input
        require(input is TransferTaskCommandInput || input is TransferPigInput)
        return if (context() != lease) CommandOutcome.NotSent("CONTEXT_CHANGED_BEFORE_SEND") else gateway.send(input)
    }
}
