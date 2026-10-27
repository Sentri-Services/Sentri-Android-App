package com.sentri.nativeapp.inspection

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import com.sentri.nativeapp.pigs.DataScope
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class StoredDeviceFaultDrafts(val drafts: Map<String, DeviceFaultDraft>,
    val unavailablePhotoPens: Set<String> = emptySet())

interface DeviceFaultDraftStore {
    suspend fun load(scope: DataScope, unitId: String): StoredDeviceFaultDrafts
    suspend fun save(scope: DataScope, unitId: String, penCode: String, draft: DeviceFaultDraft): DeviceFaultDraft
}

/** Private local drafts only. No server status or upload queue is created here. */
class FileDeviceFaultDraftStore(context: Context,
    private val root: File = File(context.filesDir, "inspection-fault-drafts")) : DeviceFaultDraftStore {
    private val resolver = context.applicationContext.contentResolver
    private val mutex = Mutex()

    override suspend fun load(scope: DataScope, unitId: String): StoredDeviceFaultDrafts =
        withContext(Dispatchers.IO) { mutex.withLock {
            val unitDir = unitDirectory(scope, unitId)
            val directories = if (unitDir.exists()) unitDir.listFiles()
                ?: throw IOException("Draft directory unavailable") else emptyArray()
            val unavailable = mutableSetOf<String>()
            val drafts = directories.filter(File::isDirectory).mapNotNull { dir ->
                val manifest = AtomicFile(File(dir, "draft.json"))
                if (!manifest.baseFile.exists() && !File(dir, "draft.json.bak").exists()) {
                    // A process may have stopped after copying the first photos but before publishing a draft.
                    manifest.delete()
                    cleanup(dir, emptySet())
                    dir.delete()
                    return@mapNotNull null
                }
                val json = manifest.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) }
                require(json.getInt("format") == 1 && json.getString("unit") == unitId)
                val pen = json.getString("pen")
                require(dir.name == hash(JSONArray().put(pen).toString()))
                val photos = json.getJSONArray("photos")
                val draft = DeviceFaultDraft(json.optString("type").takeIf(String::isNotEmpty),
                    json.getString("otherName"), json.getString("description"),
                    (0 until photos.length()).map { index ->
                        val name = photos.getString(index)
                        require(name.matches(PHOTO_NAME))
                        Uri.fromFile(File(dir, name))
                    })
                validate(draft)
                // Missing photos stay visible as unavailable; never silently discard an attachment.
                if (draft.photoUris.any { uri ->
                    try { validatePhoto(File(requireNotNull(uri.path))); false } catch (_: IOException) { true }
                }) unavailable += pen
                cleanup(dir, draft.photoUris.mapNotNull(Uri::getLastPathSegment).toSet())
                if (draft.empty) null else pen to draft
            }.toMap()
            StoredDeviceFaultDrafts(drafts, unavailable)
        } }

    override suspend fun save(scope: DataScope, unitId: String, penCode: String,
        draft: DeviceFaultDraft): DeviceFaultDraft = withContext(Dispatchers.IO) { mutex.withLock {
        require(unitId.isNotBlank() && penCode.isNotBlank())
        validate(draft)
        val dir = File(unitDirectory(scope, unitId), hash(JSONArray().put(penCode).toString()))
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Draft directory unavailable")
        val added = mutableListOf<File>()
        var committed = false
        var publicationAttempted = false
        try {
            val ownedPhotos = draft.photoUris.map { uri ->
                val local = uri.path?.let(::File)
                if (uri.scheme == "file" && local != null && local.canonicalFile.parentFile == dir.canonicalFile &&
                    local.name.matches(PHOTO_NAME)) {
                    validatePhoto(local)
                    uri
                } else {
                    require(uri.scheme in setOf("content", "file"))
                    val target = File(dir, "${UUID.randomUUID()}.image")
                    added += target
                    val input = resolver.openInputStream(uri) ?: throw IOException("Photo unavailable")
                    input.use { source -> target.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var size = 0L
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            size += count
                            if (size > MAX_PHOTO_BYTES) throw IOException("Local photo limit exceeded")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    } }
                    validatePhoto(target)
                    Uri.fromFile(target)
                }
            }
            val persisted = draft.copy(photoUris = ownedPhotos)
            val json = JSONObject().put("format", 1).put("unit", unitId).put("pen", penCode)
                .put("type", persisted.type.orEmpty()).put("otherName", persisted.otherName)
                .put("description", persisted.description)
                .put("photos", JSONArray(ownedPhotos.map { it.lastPathSegment }))
            val manifest = AtomicFile(File(dir, "draft.json"))
            val payload = json.toString().toByteArray(Charsets.UTF_8)
            val stream = manifest.startWrite()
            try {
                stream.write(payload)
                stream.fd.sync()
                publicationAttempted = true
                manifest.finishWrite(stream)
            } catch (failure: Exception) {
                manifest.failWrite(stream)
                throw failure
            }
            // AtomicFile can log a failed rename instead of throwing. Confirm what was published
            // before deleting any photos from the previous version or returning a saved receipt.
            if (!manifest.openRead().use { it.readBytes() }.contentEquals(payload))
                throw IOException("Draft commit not confirmed")
            committed = true
            cleanup(dir, ownedPhotos.mapNotNull(Uri::getLastPathSegment).toSet())
            persisted
        } finally {
            if (!committed && !publicationAttempted) added.forEach(File::delete)
        }
    } }

    private fun unitDirectory(scope: DataScope, unitId: String): File = File(File(root,
        hash(JSONArray().put(scope.environment).put(scope.oauthId).put(scope.userId).put(scope.farmId).toString())),
        hash(JSONArray().put(unitId).toString()))

    private fun validate(draft: DeviceFaultDraft) {
        require(draft.type == null || draft.type in setOf("Feed", "Water", "Other"))
        require(draft.photoUris.size <= 12 && draft.photoUris.distinct().size == draft.photoUris.size)
    }

    private fun validatePhoto(file: File) {
        if (!file.isFile || file.length() !in 1..MAX_PHOTO_BYTES) throw IOException("Photo unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Invalid photo")
    }

    private fun cleanup(dir: File, keep: Set<String>) {
        dir.listFiles().orEmpty().filter { it.name.matches(PHOTO_NAME) && it.name !in keep }.forEach(File::delete)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val PHOTO_NAME = Regex("[0-9a-f-]{36}\\.image")
        // Client storage guard, not a claim about the future server attachment limits.
        const val MAX_PHOTO_BYTES = 20L * 1024 * 1024
    }
}
