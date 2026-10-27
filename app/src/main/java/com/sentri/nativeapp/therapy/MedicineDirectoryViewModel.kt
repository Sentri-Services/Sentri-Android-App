package com.sentri.nativeapp.therapy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sentri.nativeapp.network.RequestContextChanged
import com.sentri.nativeapp.pigs.CachedResult
import com.sentri.nativeapp.pigs.ScopeGate
import com.sentri.nativeapp.pigs.ScopeLease
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MedicineDirectoryState(
    val lease: ScopeLease? = null,
    val medications: CachedResult<List<TherapyMedication>>? = null,
    val catalogs: CachedResult<List<TherapyMedicationCatalog>>? = null,
    val loading: Boolean = false,
    val medicationsFailed: Boolean = false,
    val catalogsFailed: Boolean = false,
    val treatments: CachedResult<List<TherapyTreatment>>? = null,
    val treatmentsFailed: Boolean = false,
)

/** The old flat medicine/formulation registry is useful for lookup, but it is not the new editable catalogue. */
class MedicineDirectoryViewModel(private val reads: TherapyReads, private val gate: ScopeGate) : ViewModel() {
    private val mutable = MutableStateFlow(MedicineDirectoryState())
    val state = mutable.asStateFlow()
    private var job: Job? = null

    fun open(lease: ScopeLease) {
        try { gate.check(lease) } catch (_: RequestContextChanged) { clear(); return }
        if (state.value.lease != lease) mutable.value = MedicineDirectoryState(lease = lease)
        refresh()
    }

    fun refresh() {
        val lease = state.value.lease ?: return
        job?.cancel()
        mutable.value = state.value.copy(loading = true, medicationsFailed = false, catalogsFailed = false,
            treatmentsFailed = false)
        job = viewModelScope.launch {
            var medicines: CachedResult<List<TherapyMedication>>? = null
            var formulations: CachedResult<List<TherapyMedicationCatalog>>? = null
            var medicineFailed = false
            var formulationFailed = false
            var methods: CachedResult<List<TherapyTreatment>>? = null
            var methodsFailed = false
            try { medicines = reads.medications(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { medicineFailed = true }
            try { formulations = reads.catalogs(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { formulationFailed = true }
            try { methods = reads.treatments(lease) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { methodsFailed = true }
            try { gate.check(lease) } catch (_: RequestContextChanged) { return@launch }
            if (state.value.lease == lease) mutable.value = state.value.copy(
                medications = medicines ?: state.value.medications,
                catalogs = formulations ?: state.value.catalogs,
                treatments = methods ?: state.value.treatments,
                loading = false, medicationsFailed = medicineFailed, catalogsFailed = formulationFailed,
                treatmentsFailed = methodsFailed)
        }
    }

    fun clear() {
        job?.cancel()
        mutable.value = MedicineDirectoryState()
    }
}
