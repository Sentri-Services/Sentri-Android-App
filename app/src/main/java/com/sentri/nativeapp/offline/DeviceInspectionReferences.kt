package com.sentri.nativeapp.offline

import com.sentri.nativeapp.inspection.*

/** Keep local references durable; resolve them only from a confirmed report receipt at claim time. */
internal object DeviceInspectionReferences {
    fun report(input: ResolveDeviceInspectionInput, commands: List<QueuedCommand>): QueuedCommand? {
        val local = input.reference as? DeviceInspectionReference.LocalReport ?: return null
        return commands.firstOrNull { it.record.id == local.commandId && it.input is ReportDeviceInspectionInput }
    }
    fun resolved(input: ResolveDeviceInspectionInput, commands: List<QueuedCommand>): ResolveDeviceInspectionInput? {
        if (input.reference is DeviceInspectionReference.Server) return input
        val parent = report(input, commands) ?: return null
        if (parent.record.status != CommandStatus.SYNCED) return null
        val receipt = parent.receipt?.let(DeviceInspectionWriteReceipt::decode) ?: return null
        require(receipt.operation == "ReportDeviceInspection")
        return ResolveDeviceInspectionInput(DeviceInspectionReference.Server(receipt.recordId))
    }
}
