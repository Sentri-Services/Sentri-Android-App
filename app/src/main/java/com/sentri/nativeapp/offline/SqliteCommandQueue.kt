package com.sentri.nativeapp.offline

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.production.CompleteTaskInput
import com.sentri.nativeapp.therapy.TherapyCommandInput
import com.sentri.nativeapp.therapy.TherapyWriteReceipt
import com.sentri.nativeapp.vaccination.VaccinationCommandInput
import com.sentri.nativeapp.vaccination.VaccinationWriteReceipt
import com.sentri.nativeapp.sampling.SubmitSampledInput
import com.sentri.nativeapp.sampling.SamplingWriteReceipt
import com.sentri.nativeapp.inspection.DeviceInspectionWriteReceipt
import com.sentri.nativeapp.inspection.ReportDeviceInspectionInput
import com.sentri.nativeapp.inspection.ResolveDeviceInspectionInput
import com.sentri.nativeapp.inspection.SubmitUnitInspectionInput
import com.sentri.nativeapp.inspection.SubmitUnitRemarkInput
import com.sentri.nativeapp.inspection.UnitInspectionWriteReceipt
import com.sentri.nativeapp.inspection.PigHealthCommandInput
import com.sentri.nativeapp.inspection.PigHealthWriteReceipt
import com.sentri.nativeapp.inspection.UploadHealthyImageInput
import com.sentri.nativeapp.inspection.HealthyImageReceipt
import com.sentri.nativeapp.inspection.PigMeasurementCommandInput
import com.sentri.nativeapp.inspection.PigMeasurementReceipt
import com.sentri.nativeapp.inspection.ReconcileLocationInventoryInput
import com.sentri.nativeapp.inspection.LocationInventoryReceipt
import com.sentri.nativeapp.transfer.TransferTaskCommandInput
import com.sentri.nativeapp.transfer.TransferPigInput
import com.sentri.nativeapp.transfer.TransferWriteReceipt
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Separate from read snapshots: commands are never evicted by cache capacity or cache clearing. */
class SqliteCommandQueue(context: Context, name: String = "native-estrus-queue.db") : SQLiteOpenHelper(context.applicationContext, name, null, 2), CommandQueueStore {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE commands (id TEXT PRIMARY KEY, environment TEXT NOT NULL, oauth TEXT NOT NULL, user_id TEXT NOT NULL, farm TEXT NOT NULL, operation_id TEXT NOT NULL, payload TEXT NOT NULL, digest TEXT NOT NULL, format INTEGER NOT NULL, dependencies TEXT NOT NULL, created_at INTEGER NOT NULL, status TEXT NOT NULL, attempts INTEGER NOT NULL, retry_at INTEGER, receipt TEXT, UNIQUE(environment,oauth,user_id,farm,operation_id))")
        db.execSQL("ALTER TABLE commands ADD COLUMN requested_dependencies TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("CREATE TABLE attempts (command_id TEXT NOT NULL REFERENCES commands(id), number INTEGER NOT NULL, started_at INTEGER NOT NULL, finished_at INTEGER, outcome TEXT, error_code TEXT, PRIMARY KEY(command_id,number))")
        db.execSQL("CREATE TABLE events (seq INTEGER PRIMARY KEY AUTOINCREMENT, command_id TEXT NOT NULL REFERENCES commands(id), at INTEGER NOT NULL, kind TEXT NOT NULL)")
    }
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion == 1 && newVersion == 2) { "Unsupported command database migration" }
        db.execSQL("ALTER TABLE commands ADD COLUMN requested_dependencies TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("UPDATE commands SET requested_dependencies=dependencies")
    }
    private val where = "environment=? AND oauth=? AND user_id=? AND farm=?"
    private fun args(scope: DataScope) = arrayOf(scope.environment, scope.oauthId, scope.userId, scope.farmId)
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun strings(text: String): Set<String> = JSONArray(text).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    private fun read(db: SQLiteDatabase, c: Cursor): QueuedCommand {
        fun text(key: String) = c.getString(c.getColumnIndexOrThrow(key))
        fun long(key: String) = c.getLong(c.getColumnIndexOrThrow(key))
        // Large data-URL payloads must not occupy one CursorWindow row. Payloads are immutable.
        val encoded = if (!c.isNull(c.getColumnIndexOrThrow("payload"))) text("payload") else buildString {
            val length = long("payload_length")
            var offset = 1L
            while (offset <= length) {
                db.rawQuery("SELECT substr(payload,?,65536) FROM commands WHERE id=?", arrayOf(offset.toString(), text("id"))).use { chunk ->
                    check(chunk.moveToFirst()); append(chunk.getString(0))
                }
                offset += 65536
            }
        }
        check(hash(encoded) == text("digest")) { "Corrupt command payload" }
        val input = CommandPayloadCodec.decode(long("format").toInt(), encoded)
        val scope = DataScope(text("environment"), text("oauth"), text("user_id"), text("farm"))
        val retry = c.getColumnIndexOrThrow("retry_at")
        return QueuedCommand(CommandRecord(text("id"), text("operation_id"), scope, input.operationName, input.productionType,
            input.groupKeys, strings(text("dependencies")), long("created_at"),
            CommandStatus.valueOf(text("status")), long("attempts").toInt(), if (c.isNull(retry)) null else c.getLong(retry)), input, text("receipt"), strings(text("requested_dependencies")))
    }
    private fun list(db: SQLiteDatabase, scope: DataScope): List<QueuedCommand> {
        val columns = arrayOf("id", "environment", "oauth", "user_id", "farm", "operation_id", "digest", "format", "dependencies", "created_at",
            "status", "attempts", "retry_at", "receipt", "requested_dependencies", "length(payload) AS payload_length",
            "CASE WHEN length(payload)<=65536 THEN payload ELSE NULL END AS payload")
        return db.query("commands", columns, where, args(scope), null, null, "created_at ASC,id ASC").use { c ->
            buildList { while (c.moveToNext()) add(read(db, c)) }
        }
    }
    override fun list(scope: DataScope) = list(readableDatabase, scope)
    private inline fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }
    private fun event(db: SQLiteDatabase, id: String, kind: String, at: Long) {
        db.insertOrThrow("events", null, ContentValues().apply { put("command_id", id); put("kind", kind); put("at", at) })
    }
    private fun update(db: SQLiteDatabase, command: QueuedCommand, record: CommandRecord, receipt: String? = command.receipt) {
        check(db.update("commands", ContentValues().apply {
            put("status", record.status.name); put("attempts", record.attempts)
            put("retry_at", record.nextRetryAtMillis); put("receipt", receipt)
        }, "$where AND id=? AND status=? AND attempts=?", args(record.scope) + arrayOf(record.id, command.record.status.name, command.record.attempts.toString())) == 1) { "Stale command attempt" }
    }
    override fun enqueue(scope: DataScope, operationId: String, input: CommandInput, dependsOn: Set<String>, createdAt: Long): QueuedCommand = transaction { db ->
        require(operationId.isNotBlank() && dependsOn.all(String::isNotBlank))
        val encoded = CommandPayloadCodec.encode(input)
        val all = list(db, scope)
        all.firstOrNull { it.record.operationId == operationId }?.let {
            require(it.input == input && it.requestedDependencies == dependsOn) { "Operation ID reused with different payload" }; return@transaction it
        }
        CommandDependencies.validateNew(input, dependsOn, all)
        val attachments = if (input is ReportPigDeathInput) DeathCommandReferences.uploads(operationId, input, all).map { it.record.id }.toSet() else emptySet()
        val dependencies = dependsOn + CommandDependencies.required(input, all) + attachments
        val id = UUID.randomUUID().toString(); require(id !in dependencies)
        db.insertOrThrow("commands", null, ContentValues().apply {
            put("id", id); put("environment", scope.environment); put("oauth", scope.oauthId); put("user_id", scope.userId); put("farm", scope.farmId)
            put("operation_id", operationId); put("payload", encoded); put("digest", hash(encoded)); put("format", CommandPayloadCodec.VERSION)
            put("dependencies", JSONArray(dependencies.sorted()).toString()); put("created_at", createdAt); put("status", CommandStatus.QUEUED.name); put("attempts", 0)
            put("requested_dependencies", JSONArray(dependsOn.sorted()).toString())
        })
        event(db, id, "ENQUEUED", createdAt)
        list(db, scope).single { it.record.id == id }
    }
    /** One SQLite transaction publishes the whole draft, including original image bytes and ordered references. */
    fun enqueueDeath(scope: DataScope, operationId: String, draft: PigDeathDraft, dependsOn: Set<String>, createdAt: Long): QueuedCommand = transaction {
        require(operationId.isNotBlank())
        val pigs = draft.pigs.mapIndexed { p, row ->
            var previousUploadId: String? = null
            row.pig.copy(images = row.imageSources.mapIndexed { i, source ->
                if (!source.startsWith("data:")) DeathImage.Remote(source) else {
                    val imageId = "death-image:${operationId.length}:$operationId:$p:$i"
                    val upload = enqueue(scope, imageId, UploadPigDeathImageInput(operationId, p, i, source, row.pig.targetId, draft.taskId), dependsOn + setOfNotNull(previousUploadId), createdAt)
                    previousUploadId = upload.record.id
                    DeathImage.Uploaded(imageId)
                }
            })
        }
        enqueue(scope, operationId, ReportPigDeathInput(pigs, draft.taskId), dependsOn, createdAt)
    }
    /** Each remote operation has its own durable attempt/receipt; retry never replays earlier successful steps. */
    fun enqueueTherapySteps(scope: DataScope, operationId: String, inputs: List<TherapyCommandInput>, dependsOn: Set<String>, createdAt: Long): List<QueuedCommand> = transaction { db ->
        require(operationId.isNotBlank() && inputs.isNotEmpty())
        val prefix = "therapy-step:${operationId.length}:$operationId:"
        val existing = list(db, scope).filter { it.record.operationId.startsWith(prefix) }
        val expected = inputs.indices.map { "$prefix$it" }.toSet()
        require(existing.isEmpty() || existing.map { it.record.operationId }.toSet() == expected) { "Therapy batch operation ID reused with different steps" }
        var previous: String? = null
        inputs.mapIndexed { index, input ->
            enqueue(scope, "$prefix$index", input, dependsOn + setOfNotNull(previous), createdAt).also { previous = it.record.id }
        }
    }
    override fun claim(context: QueueContext, now: Long): QueuedCommand? = claimMatching(context, now) { true }

    /** A feature transport may only claim commands it actually implements. */
    fun claimOperations(context: QueueContext, now: Long, allowed: Set<String>): QueuedCommand? {
        require(allowed.isNotEmpty())
        return claimMatching(context, now) { it.input.operationName in allowed }
    }

    private fun claimMatching(context: QueueContext, now: Long, eligible: (QueuedCommand) -> Boolean): QueuedCommand? = transaction { db ->
        val saved = list(db, context.scope)
        val all = saved.map { command ->
            if (command.input !is CompleteTaskInput || command.record.status != CommandStatus.QUEUED) return@map command
            // Only task completion barriers grow: capture late task writes before any completion attempt starts.
            val dependencies = command.record.dependsOn + CommandDependencies.required(command.input, saved.filter { it.record.id != command.record.id })
            if (dependencies == command.record.dependsOn) return@map command
            check(db.update("commands", ContentValues().apply { put("dependencies", JSONArray(dependencies.sorted()).toString()) },
                "$where AND id=? AND status='QUEUED' AND attempts=?", args(context.scope) + arrayOf(command.record.id, command.record.attempts.toString())) == 1)
            event(db, command.record.id, "TASK_DEPENDENCIES_EXTENDED", now)
            command.copy(record = command.record.copy(dependsOn = dependencies))
        }
        val command = all.firstOrNull { eligible(it) && CommandPolicy.blocked(it.record, all.map { row -> row.record }, context.scope, context.authenticated, context.goodNetwork, now) == null && DeathCommandReferences.ready(it, all) &&
            (it.input !is ResolveDeviceInspectionInput || DeviceInspectionReferences.resolved(it.input as ResolveDeviceInspectionInput, all) != null) } ?: return@transaction null
        val record = CommandPolicy.start(command.record)
        update(db, command, record)
        db.insertOrThrow("attempts", null, ContentValues().apply { put("command_id", record.id); put("number", record.attempts); put("started_at", now) })
        event(db, record.id, "STARTED", now)
        command.copy(record = record, input = (command.input as? ResolveDeviceInspectionInput)?.let { DeviceInspectionReferences.resolved(it, all) } ?: command.input)
    }
    override fun finish(attempt: QueuedCommand, outcome: CommandOutcome, now: Long) = transaction { db ->
        val all = list(db, attempt.record.scope)
        val command = all.single { it.record.id == attempt.record.id }
        check(command.record.attempts == attempt.record.attempts && command.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN)) { "Stale command attempt" }
        val record = when (outcome) {
            is CommandOutcome.Success -> {
                require(outcome.receipt.isNotBlank())
                if (command.input is UploadPigDeathImageInput) DeathImageReceipt.decode(outcome.receipt)
                if (command.input is TherapyCommandInput) TherapyWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is VaccinationCommandInput) VaccinationWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is SubmitSampledInput) SamplingWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is SubmitUnitInspectionInput || command.input is SubmitUnitRemarkInput)
                    UnitInspectionWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is PigHealthCommandInput) PigHealthWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is UploadHealthyImageInput) HealthyImageReceipt.decode(outcome.receipt)
                if (command.input is PigMeasurementCommandInput)
                    PigMeasurementReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is ReconcileLocationInventoryInput)
                    LocationInventoryReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is TransferTaskCommandInput || command.input is TransferPigInput)
                    TransferWriteReceipt.decode(outcome.receipt).validate(command.input)
                if (command.input is ReportDeviceInspectionInput || command.input is ResolveDeviceInspectionInput) {
                    val receipt = DeviceInspectionWriteReceipt.decode(outcome.receipt)
                    require(receipt.operation == command.input.operationName)
                    if (command.input is ResolveDeviceInspectionInput) {
                        val resolved = DeviceInspectionReferences.resolved(command.input, all)
                        require(resolved?.reference == com.sentri.nativeapp.inspection.DeviceInspectionReference.Server(receipt.recordId))
                    }
                }
                require(DeathCommandReferences.ready(command, all))
                CommandPolicy.acknowledge(command.record, CommandStatus.SYNCED)
            }
            is CommandOutcome.Rejected -> CommandPolicy.acknowledge(command.record, if (outcome.conflict) CommandStatus.CONFLICTED else CommandStatus.FAILED)
            is CommandOutcome.NotSent -> CommandPolicy.retryConfirmed(CommandPolicy.recover(command.record), now, true)
            CommandOutcome.Uncertain -> CommandPolicy.recover(command.record)
        }
        update(db, command, record, (outcome as? CommandOutcome.Success)?.receipt)
        val kind = when (outcome) { is CommandOutcome.Success -> "CONFIRMED_SUCCESS"; is CommandOutcome.NotSent -> "NOT_SENT"; is CommandOutcome.Rejected -> if (outcome.conflict) "CONFLICT" else "REJECTED"; CommandOutcome.Uncertain -> "UNCERTAIN" }
        check(db.update("attempts", ContentValues().apply { put("finished_at", now); put("outcome", kind); put("error_code", when (outcome) { is CommandOutcome.Rejected -> outcome.code; is CommandOutcome.NotSent -> outcome.code; else -> null }) }, "command_id=? AND number=?", arrayOf(record.id, record.attempts.toString())) == 1)
        event(db, record.id, kind, now)
        Unit
    }
    override fun recoverInterrupted(scope: DataScope, now: Long): Int = recoverInterruptedMatching(scope, now) { true }

    /** A feature recovery pass must not change another feature's in-flight operation. */
    fun recoverInterruptedOperations(scope: DataScope, now: Long, allowed: Set<String>): Int {
        require(allowed.isNotEmpty())
        return recoverInterruptedMatching(scope, now) { it.input.operationName in allowed }
    }

    private fun recoverInterruptedMatching(scope: DataScope, now: Long, eligible: (QueuedCommand) -> Boolean): Int = transaction { db ->
        val interrupted = list(db, scope).filter { it.record.status == CommandStatus.IN_FLIGHT && eligible(it) }
        interrupted.forEach { finish(it, CommandOutcome.Uncertain, now) }
        interrupted.size
    }
    override fun cancel(scope: DataScope, id: String, now: Long) = transaction { db ->
        val all = list(db, scope)
        val command = all.single { it.record.id == id }
        if (command.input is ReportDeviceInspectionInput) {
            require(all.none { child -> child.record.status != CommandStatus.CANCELLED &&
                (child.input as? ResolveDeviceInspectionInput)?.let { DeviceInspectionReferences.report(it, all)?.record?.id == id } == true
            }) { "Cancel dependent device resolution first" }
        }
        val children = if (command.input is ReportPigDeathInput) DeathCommandReferences.uploads(command.record.operationId, command.input, all) else emptyList()
        require(children.none { it.record.status in setOf(CommandStatus.IN_FLIGHT, CommandStatus.UNCERTAIN) }) { "Death upload outcome requires confirmation" }
        val record = CommandPolicy.cancel(command.record)
        children.filter { it.record.status in setOf(CommandStatus.QUEUED, CommandStatus.FAILED, CommandStatus.CONFLICTED) }.forEach {
            update(db, it, CommandPolicy.cancel(it.record)); event(db, it.record.id, "CANCELLED", now)
        }
        update(db, command, record); event(db, id, "CANCELLED", now); Unit
    }
    override fun retryAfterConfirmation(scope: DataScope, id: String, now: Long, outcomeConfirmed: Boolean) = transaction { db ->
        val command = list(db, scope).single { it.record.id == id }; update(db, command, CommandPolicy.retryConfirmed(command.record, now, outcomeConfirmed)); event(db, id, "RETRY_CONFIRMED", now); Unit
    }
}
