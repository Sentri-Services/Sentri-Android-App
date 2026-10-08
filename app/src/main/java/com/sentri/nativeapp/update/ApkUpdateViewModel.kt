package com.sentri.nativeapp.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.BuildConfig
import com.sentri.nativeapp.DiagnosticLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class UpdatePhase { IDLE, CHECKING, DOWNLOADING, READY, FAILED }
data class UpdateState(val phase: UpdatePhase = UpdatePhase.IDLE, val progress: Int = 0, val ready: ReadyUpdate? = null)

class ApkUpdateViewModel(private val repository: ApkUpdates, enabled: Boolean = BuildConfig.ENABLE_APK_UPDATE) : ViewModel() {
    private val mutableState = MutableStateFlow(UpdateState())
    val state = mutableState.asStateFlow()
    init { if (enabled) check() }

    fun check() {
        if (state.value.phase in setOf(UpdatePhase.CHECKING, UpdatePhase.DOWNLOADING)) return
        mutableState.value = UpdateState(UpdatePhase.CHECKING)
        viewModelScope.launch {
            try {
                val ready = repository.check { percent -> mutableState.update { it.copy(phase = UpdatePhase.DOWNLOADING, progress = percent) } }
                mutableState.value = if (ready == null) UpdateState() else UpdateState(UpdatePhase.READY, ready = ready)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                DiagnosticLog.event("apk_update_unavailable")
                mutableState.value = UpdateState(UpdatePhase.FAILED)
            }
        }
    }

    fun proceed() {
        if (state.value.phase == UpdatePhase.FAILED ||
            (state.value.phase == UpdatePhase.READY && state.value.ready?.manifest?.forceUpdate == false)) {
            mutableState.value = UpdateState()
        }
    }

    suspend fun validatedInstallFile(): ReadyUpdate? {
        val ready = state.value.ready ?: return null
        return try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { repository.validate(ready.file, ready.manifest) }
            ready
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.value = UpdateState(UpdatePhase.FAILED); null }
    }
}
