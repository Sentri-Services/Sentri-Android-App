package com.sentri.nativeapp.therapy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sentri.nativeapp.R
import com.sentri.nativeapp.design.SentriButton
import com.sentri.nativeapp.design.SentriField
import com.sentri.nativeapp.design.SentriIconButton
import com.sentri.nativeapp.design.SentriPanel
import com.sentri.nativeapp.design.SentriRow
import com.sentri.nativeapp.design.SentriSheet
import com.sentri.nativeapp.design.SentriTheme
import com.sentri.nativeapp.design.SentriType
import com.sentri.nativeapp.pigs.DataSource
import com.sentri.nativeapp.pigs.PigProfile

/** Reviewable local form. The existing prescription API cannot safely save this new treatment. */
@Composable
fun TreatmentDraftScreen(pigs: List<PigProfile>, directory: MedicineDirectoryState?,
    onRefreshMedicines: () -> Unit, onBack: () -> Unit, conditionName: String? = null,
    fixedCondition: TreatmentConditionKey? = null) {
    val eligible = remember(pigs) { pigs.filter { it.lifecycle == "ACTIVE" }.distinctBy(PigProfile::id) }
    if (eligible.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(18.dp)) {
            SentriIconButton("back", stringResource(R.string.home_back), onBack)
            Text(stringResource(R.string.treatment_draft_no_active_pigs), Modifier.padding(top = 20.dp))
        }
        return
    }
    var draft by remember(eligible.map(PigProfile::id), fixedCondition, conditionName) {
        mutableStateOf(TreatmentDraft(eligible.map(PigProfile::id), condition = fixedCondition))
    }
    var picker by remember { mutableStateOf<String?>(null) }
    var dosePage by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val colors = SentriTheme.colors
    val selectedPigs = eligible.filter { it.id in draft.pigIds }
    val conditions = remember(selectedPigs) { TreatmentConditions.existing(selectedPigs) }
    val conditionLocked = conditionName != null || fixedCondition != null
    val chosenCondition = conditions.options.firstOrNull { it.key == draft.condition }
    LaunchedEffect(conditions, draft.condition, conditionLocked) {
        if (!conditionLocked && draft.condition != null && chosenCondition == null)
            draft = draft.copy(condition = null)
    }
    val medicines = directory?.medications?.value.orEmpty()
    val medicine = medicines.firstOrNull { it.id == draft.medicineId }
    val methods = directory?.treatments?.value.orEmpty()
    val method = methods.firstOrNull { it.id == draft.method }
    BackHandler(enabled = picker != null || dosePage) {
        if (picker != null) picker = null else if (removing) removing = false else dosePage = false
    }
    if (dosePage) {
        Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
            DraftHeader(stringResource(R.string.treatment_draft_per_pig),
                onBack = { if (removing) removing = false else dosePage = false })
            LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text(stringResource(R.string.treatment_draft_per_pig_help), color = colors.muted,
                        fontFamily = SentriType.sans, fontSize = 12.sp)
                }
                item {
                    SentriButton(stringResource(if (removing) R.string.treatment_draft_done_removing
                        else R.string.treatment_draft_remove_pigs), { removing = !removing },
                        Modifier.fillMaxWidth(), primary = false)
                }
                item { SentriField(query, { query = it }, stringResource(R.string.treatment_draft_search_pigs), Modifier.fillMaxWidth()) }
                val visible = selectedPigs.filter { pig -> query.isBlank() ||
                    listOfNotNull(pig.earTag, pig.earNotch).any { it.contains(query.trim(), ignoreCase = true) } }
                if (visible.isEmpty()) item { Text(stringResource(R.string.treatment_draft_no_matching_pigs),
                    color = colors.muted, fontFamily = SentriType.sans, fontSize = 13.sp) }
                items(visible, key = PigProfile::id) { pig ->
                    SentriPanel(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(treatmentPigLabel(pig), Modifier.weight(1f), color = colors.ink,
                                fontFamily = SentriType.sans, fontSize = 14.sp)
                            if (removing) SentriButton(stringResource(R.string.treatment_draft_remove),
                                { draft = draft.removePig(pig.id) }, enabled = draft.pigIds.size > 1,
                                primary = false)
                            else PigDoseField(pig.id, draft.doseFor(pig.id), pig.id in draft.ownDoses,
                                draft.unit, { draft = draft.setOwnDose(pig.id, it) })
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().background(colors.background).imePadding()) {
        DraftHeader(stringResource(R.string.treatment_draft_title), onBack)
        LazyColumn(Modifier.weight(1f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.treatment_draft_preview_warning),
                color = colors.muted, fontFamily = SentriType.sans, fontSize = 12.sp) }
            item { Text(stringResource(R.string.treatment_draft_targets, draft.pigIds.size),
                color = colors.ink, fontFamily = SentriType.sans, fontWeight = FontWeight.SemiBold) }
            item { SentriPanel(Modifier.fillMaxWidth()) { SentriRow(
                stringResource(R.string.treatment_draft_medicine),
                medicine?.let { if (chinese) it.chinese.ifBlank { it.english } else it.english.ifBlank { it.chinese } }
                    ?: stringResource(R.string.treatment_draft_select), "health", { picker = "medicine" }) } }
            if (directory?.loading == true && directory.medications == null)
                item { Text(stringResource(R.string.treatment_draft_loading), color = colors.muted) }
            if (directory?.medicationsFailed == true)
                item { SentriButton(stringResource(R.string.treatment_draft_retry), onRefreshMedicines,
                    Modifier.fillMaxWidth(), primary = false) }
            if (directory?.medications?.source == DataSource.CACHE)
                item { Text(stringResource(R.string.treatment_draft_old_catalog), color = colors.muted) }
            item { SentriPanel(Modifier.fillMaxWidth()) { SentriRow(
                stringResource(R.string.treatment_draft_method), method?.label(chinese)
                    ?: stringResource(R.string.treatment_draft_select),
                "health", { picker = "method" }) } }
            if (directory?.treatments?.source == DataSource.CACHE)
                item { Text(stringResource(R.string.treatment_draft_old_methods), color = colors.muted) }
            item { SentriPanel(Modifier.fillMaxWidth()) { SentriRow(
                stringResource(R.string.treatment_draft_brand),
                stringResource(R.string.treatment_draft_brand_pending), null, null) } }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SentriField(draft.sharedDose, { draft = draft.copy(sharedDose = it) },
                        stringResource(R.string.treatment_draft_dose), Modifier.weight(1f).testTag("treatment-shared-dose"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    SentriPanel(Modifier.weight(1f)) { SentriRow(
                        stringResource(R.string.treatment_draft_unit),
                        draft.unit ?: stringResource(R.string.treatment_draft_select),
                        null, { picker = "unit" }) }
                }
            }
            if (draft.pigIds.size > 1 && (draft.sharedDose.isNotBlank() || draft.ownDoses.isNotEmpty()))
                item { SentriPanel(Modifier.fillMaxWidth()) { SentriRow(
                    stringResource(R.string.treatment_draft_adjust, draft.pigIds.size),
                    if (draft.adjustedCount > 0) stringResource(R.string.treatment_draft_adjusted, draft.adjustedCount)
                        else stringResource(R.string.treatment_draft_each, draft.sharedDose,
                            draft.unit ?: stringResource(R.string.treatment_draft_unit_unset)),
                    "health", { dosePage = true }) } }
            item { SentriPanel(Modifier.fillMaxWidth()) { SentriRow(
                stringResource(R.string.treatment_draft_condition),
                conditionName ?: chosenCondition?.label(chinese)
                    ?: stringResource(R.string.treatment_draft_condition_none), null,
                if (conditionLocked) null else ({ picker = "condition" })) } }
            if (chosenCondition != null) item { Text(
                stringResource(R.string.treatment_draft_condition_scope, chosenCondition.pigIds.size, draft.pigIds.size),
                color = colors.muted, fontFamily = SentriType.sans, fontSize = 12.sp) }
            if (conditionLocked) item { Text(stringResource(R.string.treatment_draft_condition_fixed),
                color = colors.muted, fontFamily = SentriType.sans, fontSize = 12.sp) }
            if (chosenCondition != null || conditionLocked) item { Text(
                stringResource(R.string.treatment_draft_condition_unlinked), color = colors.muted,
                fontFamily = SentriType.sans, fontSize = 12.sp) }
            item { SentriField(draft.note, { if (it.length <= 200) draft = draft.copy(note = it) },
                stringResource(R.string.treatment_draft_note), Modifier.fillMaxWidth(),
                singleLine = false, minLines = 3, maxLines = 5, optional = true,
                hint = if (draft.note.length >= 160) stringResource(R.string.treatment_draft_note_count, draft.note.length) else null) }
            item { Spacer(Modifier.height(12.dp)) }
        }
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text(stringResource(R.string.treatment_draft_save_pending), color = colors.muted,
                fontFamily = SentriType.sans, fontSize = 12.sp)
            SentriButton(stringResource(R.string.treatment_draft_save), {},
                Modifier.fillMaxWidth().padding(top = 8.dp), enabled = false)
        }
    }
    if (picker != null) SentriSheet(stringResource(when (picker) {
        "medicine" -> R.string.treatment_draft_medicine
        "method" -> R.string.treatment_draft_method
        "condition" -> R.string.treatment_draft_condition
        else -> R.string.treatment_draft_unit
    }), stringResource(R.string.home_back), stringResource(R.string.home_close), { picker = null }) {
        when (picker) {
            "medicine" -> {
                var search by remember { mutableStateOf("") }
                var shown by remember(search) { mutableIntStateOf(60) }
                SentriField(search, { search = it }, stringResource(R.string.treatment_draft_search_medicine), Modifier.fillMaxWidth())
                Text(stringResource(R.string.treatment_draft_flat_catalog),
                    Modifier.padding(vertical = 10.dp), color = colors.muted, fontSize = 12.sp)
                if (directory?.loading == true)
                    Text(stringResource(R.string.treatment_draft_loading), color = colors.muted)
                if (directory?.medications?.source == DataSource.CACHE)
                    Text(stringResource(R.string.treatment_draft_old_catalog), color = colors.muted)
                if (directory?.medicationsFailed == true ||
                    directory?.loading != true && directory?.medications == null)
                    SentriButton(stringResource(R.string.treatment_draft_retry), onRefreshMedicines,
                        Modifier.fillMaxWidth(), primary = false, enabled = directory?.loading != true)
                val match = medicines.filter { item -> search.isBlank() ||
                    item.chinese.contains(search.trim(), ignoreCase = true) ||
                    item.english.contains(search.trim(), ignoreCase = true) }
                match.take(shown).forEach { item -> SentriRow(
                    if (chinese) item.chinese.ifBlank { item.english } else item.english.ifBlank { item.chinese },
                    null, null, { draft = draft.chooseMedicine(item.id); picker = null },
                    selected = draft.medicineId == item.id) }
                if (match.size > shown) SentriButton(
                    stringResource(R.string.treatment_draft_more_medicines, match.size - shown),
                    { shown += 60 }, Modifier.fillMaxWidth(), primary = false)
                if (match.isEmpty() && directory?.medications != null && !directory.loading && !directory.medicationsFailed)
                    Text(stringResource(R.string.treatment_draft_no_medicine), color = colors.muted)
            }
            "condition" -> ExistingTreatmentConditionPicker(conditions, selectedPigs.size, draft.condition) { key ->
                draft = draft.copy(condition = key)
                picker = null
            }
            "method" -> {
                if (directory?.treatments?.source == DataSource.CACHE)
                    Text(stringResource(R.string.treatment_draft_old_methods), color = colors.muted)
                if (directory?.loading == true)
                    Text(stringResource(R.string.treatment_draft_loading_methods), color = colors.muted)
                if (directory?.treatmentsFailed == true || directory == null)
                    SentriButton(stringResource(R.string.treatment_draft_retry_methods), onRefreshMedicines,
                        Modifier.fillMaxWidth(), primary = false)
                if (methods.isEmpty() && directory != null && !directory.loading && !directory.treatmentsFailed)
                    Text(stringResource(R.string.treatment_draft_no_methods), color = colors.muted)
                methods.forEach { item ->
                    SentriRow(item.label(chinese), null, null,
                        { draft = draft.copy(method = item.id); picker = null }, selected = draft.method == item.id)
                }
            }
            else -> {
                Text(stringResource(R.string.treatment_draft_sample_values),
                    Modifier.padding(bottom = 10.dp), color = colors.muted, fontSize = 12.sp)
                listOf("mL", "mg", "g", "dose").forEach { value ->
                    SentriRow(value, null, null, { draft = draft.copy(unit = value); picker = null },
                        selected = draft.unit == value)
                }
            }
        }
    }
}

@Composable
private fun ExistingTreatmentConditionPicker(conditions: TreatmentConditionOptions, targetCount: Int,
    selected: TreatmentConditionKey?, onPick: (TreatmentConditionKey?) -> Unit) {
    var query by remember { mutableStateOf("") }
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    val colors = SentriTheme.colors
    Text(stringResource(R.string.treatment_draft_condition_existing_only), color = colors.muted,
        fontFamily = SentriType.sans, fontSize = 12.sp)
    SentriField(query, { query = it }, stringResource(R.string.treatment_draft_condition_search),
        Modifier.fillMaxWidth().padding(vertical = 10.dp))
    SentriRow(stringResource(R.string.treatment_draft_condition_none), null, null,
        { onPick(null) }, selected = selected == null)
    if (conditions.unavailablePigs > 0 || conditions.unavailableRecords > 0)
        Text(stringResource(R.string.treatment_draft_condition_incomplete), color = colors.muted,
            fontFamily = SentriType.sans, fontSize = 12.sp)
    val visible = conditions.options.filter { option -> query.isBlank() || option.searchTerms.any {
        it.contains(query.trim(), ignoreCase = true) } }
    if (visible.isEmpty()) Text(stringResource(when {
        query.isNotBlank() -> R.string.treatment_draft_condition_no_match
        conditions.unavailablePigs > 0 || conditions.unavailableRecords > 0 -> R.string.treatment_draft_condition_unconfirmed
        else -> R.string.treatment_draft_condition_empty
    }), Modifier.padding(vertical = 12.dp), color = colors.muted, fontFamily = SentriType.sans, fontSize = 13.sp)
    visible.groupBy { it.key.kind }.forEach { (kind, entries) ->
        Text(stringResource(if (kind == TreatmentConditionKind.SYMPTOM) R.string.inspection_catalog_symptoms
            else R.string.inspection_catalog_diseases), Modifier.padding(top = 12.dp, bottom = 6.dp),
            color = colors.muted, fontFamily = SentriType.sans, fontSize = 12.sp)
        SentriPanel(Modifier.fillMaxWidth()) { Column {
            entries.sortedWith(compareBy({ it.label(chinese) }, { it.key.catalogId })).forEach { condition ->
                SentriRow(condition.label(chinese),
                    stringResource(R.string.treatment_draft_condition_carriers, condition.pigIds.size, targetCount),
                    null, { onPick(condition.key) }, selected = selected == condition.key)
            }
        } }
    }
}

@Composable
private fun PigDoseField(pigId: String, dose: String, adjusted: Boolean, unit: String?,
    onDoseChanged: (String) -> Unit) {
    val colors = SentriTheme.colors
    val focusManager = LocalFocusManager.current
    var focused by remember(pigId) { mutableStateOf(false) }
    var editing by remember(pigId) { mutableStateOf("") }
    SentriField(if (focused) editing else dose, { value ->
        editing = value
        onDoseChanged(value)
    }, stringResource(if (adjusted) R.string.treatment_draft_own_dose else R.string.treatment_draft_follows_shared),
        Modifier.width(144.dp).testTag("treatment-dose:$pigId").onFocusChanged { state ->
        if (state.isFocused && !focused) editing = dose
        focused = state.isFocused
    }, unit = unit,
        textStyle = TextStyle(fontFamily = SentriType.mono, fontSize = 16.sp,
            fontWeight = if (adjusted) FontWeight.SemiBold else FontWeight.Normal,
            color = if (adjusted) colors.ink else colors.muted),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }))
}

@Composable private fun DraftHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        SentriIconButton("back", stringResource(R.string.home_back), onBack)
        Spacer(Modifier.width(12.dp))
        Text(title, fontFamily = SentriType.sans, fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold, color = SentriTheme.colors.ink)
    }
}

@Composable private fun treatmentPigLabel(pig: PigProfile): String =
    pig.earTag?.takeIf(String::isNotBlank) ?: pig.earNotch?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.inspection_no_tag)

private fun TherapyTreatment.label(chinese: Boolean): String {
    val name = if (chinese) this.chinese.ifBlank { english } else english.ifBlank { this.chinese }
    val detail = if (chinese) detailChinese.ifBlank { detailEnglish } else detailEnglish.ifBlank { detailChinese }
    return listOf(name, detail).filter(String::isNotBlank).distinct().joinToString(" · ")
}
