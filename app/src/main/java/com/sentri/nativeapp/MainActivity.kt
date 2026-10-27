package com.sentri.nativeapp

import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import com.sentri.nativeapp.pigs.PigViewModel
import com.sentri.nativeapp.pigs.PigScreen
import com.sentri.nativeapp.devices.DeviceVerificationViewModel
import com.sentri.nativeapp.devices.DeviceVerificationScreen
import android.widget.Toast
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.content.Context
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.sentri.nativeapp.ui.ConnectionScreen
import com.sentri.nativeapp.ui.ConnectionViewModel
import com.sentri.nativeapp.update.ApkUpdateViewModel
import com.sentri.nativeapp.update.ApkUpdateScreen
import com.sentri.nativeapp.update.UpdatePhase
import com.sentri.nativeapp.design.SentriAppTheme
import com.sentri.nativeapp.home.HomeScreen
import com.sentri.nativeapp.home.ConnectedHomeScreen
import com.sentri.nativeapp.home.HomeLiveViewModel
import com.sentri.nativeapp.home.HomePregnancyDetailViewModel
import com.sentri.nativeapp.home.HomeEstrusDetailViewModel
import com.sentri.nativeapp.home.HomeTaskCardFactsViewModel
import com.sentri.nativeapp.home.HomeReEstrusDetailViewModel
import com.sentri.nativeapp.home.HomeMatingDetailViewModel
import com.sentri.nativeapp.home.HomeOtherProductionDetailViewModel
import com.sentri.nativeapp.home.HomeTransferDetailViewModel
import com.sentri.nativeapp.home.HomeVaccinationDetailViewModel
import com.sentri.nativeapp.home.HomeUnitOverviewViewModel
import com.sentri.nativeapp.home.HomePickerSummaryViewModel
import com.sentri.nativeapp.inspection.InspectionWalkViewModel
import com.sentri.nativeapp.therapy.MedicineDirectoryViewModel
import com.sentri.nativeapp.production.ProductionTaskKind

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SentriApplication).container
        setContent {
            val model: ConnectionViewModel = viewModel(factory = viewModelFactory {
                initializer { ConnectionViewModel(container.auth, container.farms) }
            })
            val state by model.state.collectAsStateWithLifecycle()
            val pigModel: PigViewModel = viewModel(factory = viewModelFactory {
                initializer { PigViewModel(container.pigs, container.scopeGate, container.auth) }
            })
            val pigState by pigModel.state.collectAsStateWithLifecycle()
            val homeModel: HomeLiveViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeLiveViewModel(container.homePlaces, container.homeProductionCatalog,
                    container.scopeGate, container.commandQueue, container.homeVaccinationCatalog,
                    container.homeTransferCatalog) }
            })
            val homeState by homeModel.state.collectAsStateWithLifecycle()
            val pregnancyDetailModel: HomePregnancyDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomePregnancyDetailViewModel(
                    { lease, id -> container.pregnancy.task(lease, id) }, container.scopeGate) }
            })
            val estrusDetailModel: HomeEstrusDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeEstrusDetailViewModel(
                    { lease, id -> container.estrus.task(lease, id) }, container.scopeGate) }
            })
            val reEstrusDetailModel: HomeReEstrusDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeReEstrusDetailViewModel(
                    { lease, id -> container.reEstrus.task(lease, id) }, container.scopeGate) }
            })
            val matingDetailModel: HomeMatingDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeMatingDetailViewModel(
                    { lease, id -> container.mating.task(lease, id) }, container.scopeGate) }
            })
            val otherProductionDetailModel: HomeOtherProductionDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeOtherProductionDetailViewModel({ lease, kind, id -> when (kind) {
                    ProductionTaskKind.FARROWING -> container.farrowing.task(lease, id)
                    ProductionTaskKind.PIGLET_PROCESSING -> container.piglet.task(lease, id)
                    ProductionTaskKind.POSTPARTUM -> container.postpartum.task(lease, id)
                    ProductionTaskKind.LACTATION -> container.lactation.task(lease, id)
                    ProductionTaskKind.NURSERY -> container.nursery.task(lease, id)
                    ProductionTaskKind.FATTENING -> container.fattening.task(lease, id)
                    else -> error("Not an other-production detail kind")
                } }, container.scopeGate) }
            })
            val transferDetailModel: HomeTransferDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeTransferDetailViewModel(
                    { lease, id -> container.homeTransferDetail.task(lease, id) }, container.scopeGate) }
            })
            val vaccinationDetailModel: HomeVaccinationDetailViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeVaccinationDetailViewModel(
                    { lease, id -> container.vaccination.detail(lease, id) }, container.scopeGate) }
            })
            val unitOverviewModel: HomeUnitOverviewViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeUnitOverviewViewModel(container.homeUnitStock, container.homeUnitPenRoster,
                    container.unitCheckInHistory, container.homeUnitMaintenance, container.scopeGate) }
            })
            val homePickerModel: HomePickerSummaryViewModel = viewModel(factory = viewModelFactory {
                initializer { HomePickerSummaryViewModel(container.homeUnitStock,
                    container.unitCheckInHistory, container.scopeGate::check) }
            })
            val homeCardFactsModel: HomeTaskCardFactsViewModel = viewModel(factory = viewModelFactory {
                initializer { HomeTaskCardFactsViewModel(
                    { lease, id -> container.estrus.task(lease, id) }, container.scopeGate::check) }
            })
            val inspectionModel: InspectionWalkViewModel = viewModel(factory = viewModelFactory {
                initializer { InspectionWalkViewModel(container.pigProfiles, container.scopeGate,
                    container.inspectionCheckIn, homeModel::refreshSavedWork, container.healthReferences,
                    container.unitCheckInHistory, container.deviceInspections, container.recentUnitCheckIns,
                    container.homeUnitPenRoster, container.unitInspections, container.locationInventory,
                    container.faultDrafts, container.penNoteDrafts,
                    readBatch = { lease, id -> container.productionAuxiliary.batch(lease, id) }) }
            })
            val medicineModel: MedicineDirectoryViewModel = viewModel(factory = viewModelFactory {
                initializer { MedicineDirectoryViewModel(container.therapy, container.scopeGate) }
            })
            val uiScope = rememberCoroutineScope()
            DisposableEffect(homeState.lease) {
                val lease = homeState.lease
                if (lease == null) onDispose { }
                else {
                    val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                    val callback = object : ConnectivityManager.NetworkCallback() {
                        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                            // A reachable farm API can work even when Android's public validation probe fails.
                            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
                                uiScope.launch {
                                    try { container.inspectionCheckIn.sync(lease) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { /* Keep the durable queue for the next valid connection. */ }
                                    inspectionModel.refreshCheckIn()
                                    homeModel.refreshSavedWork()
                                }
                        }
                    }
                    connectivity.registerDefaultNetworkCallback(callback)
                    onDispose { connectivity.unregisterNetworkCallback(callback) }
                }
            }
            LaunchedEffect(homeState.lease) {
                val lease = homeState.lease ?: return@LaunchedEffect
                try { container.inspectionCheckInWork.ensureScheduled(lease) }
                catch (_: Exception) { /* Foreground retry remains available. */ }
                while (isActive) {
                    try { container.inspectionCheckIn.sync(lease) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Reconcile by ID on a later connected pass. */ }
                    inspectionModel.refreshCheckIn()
                    homeModel.refreshSavedWork()
                    delay(30_000)
                }
            }
            var businessOpen by rememberSaveable { mutableStateOf(false) }
            var homePreviewOpen by rememberSaveable { mutableStateOf(false) }
            var integrationOpen by rememberSaveable { mutableStateOf(false) }
            val deviceModel: DeviceVerificationViewModel = viewModel(factory = viewModelFactory {
                initializer { DeviceVerificationViewModel(applicationContext) }
            })
            val deviceState by deviceModel.state.collectAsStateWithLifecycle()
            var devicesOpen by rememberSaveable { mutableStateOf(false) }
            LaunchedEffect(state.account) {
                if (state.account == null) {
                    devicesOpen = false
                    integrationOpen = false
                    deviceModel.cancel()
                }
            }
            LaunchedEffect(state.account, businessOpen) {
                state.account?.let { if (businessOpen) pigModel.open(it) } ?: run { businessOpen = false }
            }
            LaunchedEffect(state.account) {
                state.account?.let(homeModel::open) ?: run { homeModel.clear(); inspectionModel.clear() }
            }
            val updateModel: ApkUpdateViewModel = viewModel(factory = viewModelFactory {
                initializer { ApkUpdateViewModel(container.updates) }
            })
            val updateState by updateModel.state.collectAsStateWithLifecycle()
            LaunchedEffect(updateState.phase) {
                // Match the old startup policy: an unavailable check does not block app access.
                if (updateState.phase == UpdatePhase.FAILED) {
                    Toast.makeText(this@MainActivity, R.string.update_failed, Toast.LENGTH_LONG).show()
                    updateModel.proceed()
                }
            }
            fun clearBrowserSession() {
                CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
                val issuer = Uri.parse(container.config.authIssuer)
                // The upgrade shares the old sandbox; preserve legacy business/offline origins.
                WebStorage.getInstance().deleteOrigin("${issuer.scheme}://${issuer.encodedAuthority}")
            }
            MaterialTheme {
                if (updateState.phase != UpdatePhase.IDLE) ApkUpdateScreen(updateState, updateModel)
                else if (shouldShowHome(state, homePreviewOpen, integrationOpen, businessOpen, devicesOpen)) {
                    SentriAppTheme {
                        if (homePreviewOpen) HomeScreen(onExit = {
                            homePreviewOpen = false
                            integrationOpen = openIntegrationAfterHomeExit(state)
                        }) else ConnectedHomeScreen(homeState, inspectionModel, medicineModel,
                            pregnancyDetailModel, unitOverviewModel, vaccinationDetailModel,
                            estrusDetailModel, reEstrusDetailModel, matingDetailModel, transferDetailModel,
                            otherProductionDetailModel,
                            homeModel::refresh,
                            onExit = { integrationOpen = openIntegrationAfterHomeExit(state) },
                            pickerSummaries = homePickerModel, cardFacts = homeCardFactsModel)
                    }
                }
                else if (devicesOpen && state.account != null && state.webUrl == null) DeviceVerificationScreen(deviceState, deviceModel, onBack = { devicesOpen = false })
                else if (businessOpen && state.account != null && state.webUrl == null) PigScreen(pigState, pigModel, state.account!!,
                    onExit = { businessOpen = false }, onSwitch = { businessOpen = false; model.switchFarm() }, onLogout = { businessOpen = false; model.signOut() })
                else ConnectionScreen(state, container.config, model::login, model::refresh, model::selectFarm,
                    model::switchFarm, model::signOut,
                    onCallback = { url ->
                        if (state.loggingOut) clearBrowserSession()
                        model.callback(url)
                    },
                    onCloseWeb = {
                        if (state.loggingOut) clearBrowserSession()
                        model.closeWeb()
                    },
                    onBusiness = { businessOpen = true },
                    onHomePreview = { homePreviewOpen = true; integrationOpen = false },
                    onDevices = { devicesOpen = true; deviceModel.refresh() },
                    onWebFailure = {
                        if (state.loggingOut) clearBrowserSession()
                        model.webFailure()
                    })
            }
        }
    }
}
