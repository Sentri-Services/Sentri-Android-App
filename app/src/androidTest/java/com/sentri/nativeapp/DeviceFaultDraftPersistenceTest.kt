package com.sentri.nativeapp

import android.graphics.Bitmap
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.inspection.DeviceFaultDraft
import com.sentri.nativeapp.inspection.FileDeviceFaultDraftStore
import com.sentri.nativeapp.inspection.InspectionWalkViewModel
import com.sentri.nativeapp.inspection.SnapshotPenNoteDraftStore
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.pigs.*
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class DeviceFaultDraftPersistenceTest {
    @Test fun savedDraftRestoresInANewWalkAndUncommittedEditsDoNotReplaceIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "fault-walk-test-${UUID.randomUUID()}")
        val databaseName = "fault-walk-test-${UUID.randomUUID()}"
        val scope = PigProfileFixture.scope
        val auth = AuthRepository(object : SessionStore {
            private var session: Session? = Session("{}", "token", Long.MAX_VALUE, scope.oauthId, scope.farmId)
            override fun readSession() = session
            override fun saveSession(session: Session?) { this.session = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) = Unit
        }, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("unused")
            override suspend fun refresh(session: Session) = session
            override fun logoutUrl(session: Session): String? = null
        })
        val gate = ScopeGate(scope.environment, auth)
        val lease = ScopeLease(scope, auth.contextRevision)
        val unit = FarmUnit("unit", scope.farmId, "单元", "Unit", "Z-S-U", "AnimalHousing")
        val pen = requireNotNull(PigProfileFixture.pig.location?.penLocation)
        val cache = SqliteSnapshotCache(context, databaseName)
        val reads = PigProfileReads(PigProfileFixture.remote(), cache, gate::check)
        val models = mutableListOf<InspectionWalkViewModel>()
        suspend fun walk(): InspectionWalkViewModel {
            val vm = withContext(Dispatchers.Main) {
                InspectionWalkViewModel(reads, gate, faultDraftStore = FileDeviceFaultDraftStore(context, root),
                    penNoteDraftStore = SnapshotPenNoteDraftStore(cache, gate::check))
                    .also { models += it; it.open(lease, unit) }
            }
            withTimeout(5_000) { vm.state.first { !it.faultDraftsLoading && it.page != null } }
            withContext(Dispatchers.Main) { vm.openPen(pen) }
            withTimeout(5_000) { vm.state.first { it.penCode == pen && !it.penLoading } }
            return vm
        }
        try {
            val first = walk()
            val saved = DeviceFaultDraft("Other", "Fan", "Noise")
            withContext(Dispatchers.Main) {
                first.updateFaultDraft(lease, pen, saved)
                first.saveFaultDraft(lease.copy(scope = scope.copy(farmId = "another")), pen)
                assertTrue(first.state.value.faultDraftSavingPens.isEmpty())
                first.saveFaultDraft(lease, pen)
            }
            withTimeout(5_000) { first.state.first { pen in it.faultDraftSavedPens } }
            withContext(Dispatchers.Main) {
                first.openPenNoteDraft(lease.copy(scope = scope.copy(farmId = "another")), pen)
                assertTrue(first.state.value.penNoteDrafts.isEmpty())
                first.openPenNoteDraft(lease, pen)
            }
            withTimeout(5_000) { first.state.first { it.penNoteDrafts[pen]?.loaded == true } }
            withContext(Dispatchers.Main) {
                first.updatePenNoteDraft(lease, pen, "Saved pen note draft")
                first.savePenNoteDraft(lease, pen)
            }
            withTimeout(5_000) { first.state.first { it.penNoteDrafts[pen]?.unchanged == true } }
            withContext(Dispatchers.Main) {
                first.updatePenNoteDraft(lease, pen, "Uncommitted note edit")
                first.updateFaultDraft(lease, pen, saved.copy(description = "Not saved"))
                assertFalse(pen in first.state.value.faultDraftSavedPens)
                first.clear()
            }
            val restored = walk()
            assertEquals(saved, restored.state.value.faultDrafts[pen])
            assertTrue(pen in restored.state.value.faultDraftSavedPens)
            withContext(Dispatchers.Main) { restored.openPenNoteDraft(lease, pen) }
            withTimeout(5_000) { restored.state.first { it.penNoteDrafts[pen]?.loaded == true } }
            assertEquals("Saved pen note draft", restored.state.value.penNoteDrafts[pen]?.text)
            withContext(Dispatchers.Main) { restored.clearPenNoteDraft(lease, pen) }
            withTimeout(5_000) { restored.state.first { it.penNoteDrafts[pen]?.let { note ->
                note.loaded && !note.saving && note.saved == null } == true } }
            assertNull(SnapshotPenNoteDraftStore(cache, gate::check).read(lease, unit.id, pen))
            withContext(Dispatchers.Main) { restored.updateFaultDraft(lease, pen, DeviceFaultDraft()) }
            withTimeout(5_000) { restored.state.first { it.faultDraftSavingPens.isEmpty() } }
            assertTrue(FileDeviceFaultDraftStore(context, root).load(scope, unit.id).drafts.isEmpty())
        } finally {
            withContext(Dispatchers.Main) { models.forEach(InspectionWalkViewModel::clear) }
            cache.close()
            context.deleteDatabase(databaseName)
            root.deleteRecursively()
        }
    }

    @Test fun copiedPhotosSurviveSourceRemovalAndFailedReplacementKeepsTheSavedDraft() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "fault-draft-test-${UUID.randomUUID()}")
        val originals = File(context.cacheDir, "fault-photo-test-${UUID.randomUUID()}").apply { mkdirs() }
        val scope = PigProfileFixture.scope
        fun photo(name: String): Uri {
            val file = File(originals, name)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            return Uri.fromFile(file)
        }
        try {
            val store = FileDeviceFaultDraftStore(context, directory)
            val source = photo("first.png")
            val saved = store.save(scope, "unit", "pen-1",
                DeviceFaultDraft("Other", "Fan", "Noise", listOf(source)))
            assertNotEquals(source, saved.photoUris.single())
            assertTrue(File(requireNotNull(source.path)).delete())
            val reopened = FileDeviceFaultDraftStore(context, directory)
            assertEquals(saved, reopened.load(scope, "unit").drafts["pen-1"])
            context.contentResolver.openInputStream(saved.photoUris.single())!!.use { assertTrue(it.readBytes().isNotEmpty()) }
            val another = reopened.save(scope, "unit", "pen-2", saved)
            assertNotEquals(saved.photoUris, another.photoUris)
            assertTrue(reopened.load(scope, "other-unit").drafts.isEmpty())
            listOf(scope.copy(environment = "another"), scope.copy(oauthId = "another"),
                scope.copy(userId = "another"), scope.copy(farmId = "another")).forEach {
                assertTrue(reopened.load(it, "unit").drafts.isEmpty())
            }
            val bad = File(originals, "bad.png").apply { writeText("not an image") }
            try {
                reopened.save(scope, "unit", "pen-1", saved.copy(description = "Changed",
                    photoUris = saved.photoUris + photo("second.png") + Uri.fromFile(bad)))
                fail("Invalid photo must reject the entire draft replacement")
            } catch (_: IOException) { }
            assertEquals(saved, reopened.load(scope, "unit").drafts["pen-1"])
            val savedDirectory = File(requireNotNull(saved.photoUris.single().path)).parentFile!!
            assertEquals(1, savedDirectory.listFiles()!!.count { it.extension == "image" })
            val interruptedPhoto = File(savedDirectory, "${UUID.randomUUID()}.image").apply { writeText("incomplete") }
            File(savedDirectory, "draft.json.new").writeText("{incomplete")
            val firstWrite = File(savedDirectory.parentFile, "interrupted-first-write").apply { mkdirs() }
            val firstPhoto = File(firstWrite, "${UUID.randomUUID()}.image").apply { writeText("uncommitted") }
            File(firstWrite, "draft.json.new").writeText("{incomplete")
            assertEquals(saved, reopened.load(scope, "unit").drafts["pen-1"])
            assertFalse(interruptedPhoto.exists())
            assertFalse(firstPhoto.exists())
            reopened.save(scope, "unit", "pen-1", DeviceFaultDraft())
            assertFalse(reopened.load(scope, "unit").drafts.containsKey("pen-1"))
            assertFalse(File(requireNotNull(saved.photoUris.single().path)).exists())
            assertEquals(another, reopened.load(scope, "unit").drafts["pen-2"])
            assertTrue(File(requireNotNull(another.photoUris.single().path)).delete())
            val missingPhoto = reopened.load(scope, "unit")
            assertEquals(setOf("pen-2"), missingPhoto.unavailablePhotoPens)
            assertEquals(another, missingPhoto.drafts["pen-2"])
        } finally {
            directory.deleteRecursively()
            originals.deleteRecursively()
        }
    }
}
