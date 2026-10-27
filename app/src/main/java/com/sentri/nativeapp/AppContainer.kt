package com.sentri.nativeapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.apollographql.apollo.ApolloClient
import com.sentri.nativeapp.auth.AuthRepository
import com.sentri.nativeapp.auth.EncryptedSessionStore
import com.sentri.nativeapp.auth.LogtoAuthGateway
import com.sentri.nativeapp.config.AppConfig
import com.sentri.nativeapp.network.GraphqlFarmRepository
import com.sentri.nativeapp.update.ApkUpdateRepository
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.home.HomePlacesReader
import com.sentri.nativeapp.home.HomeProductionCatalogReader
import com.sentri.nativeapp.home.GraphqlHomeTransferRemote
import com.sentri.nativeapp.home.HomeTransferCatalogReader
import com.sentri.nativeapp.home.GraphqlHomeTransferDetailRemote
import com.sentri.nativeapp.home.HomeTransferDetailReader
import com.sentri.nativeapp.home.GraphqlHomeUnitMaintenanceRemote
import com.sentri.nativeapp.home.HomeUnitMaintenanceReads
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.production.*
import com.sentri.nativeapp.therapy.GraphqlTherapyReads
import com.sentri.nativeapp.therapy.TherapyReads
import com.sentri.nativeapp.therapy.TherapyWorkspaceReads
import com.sentri.nativeapp.vaccination.GraphqlVaccinationReads
import com.sentri.nativeapp.vaccination.VaccinationReads
import com.sentri.nativeapp.vaccination.VaccinationWorkspaceReads
import com.sentri.nativeapp.sampling.GraphqlSamplingReads
import com.sentri.nativeapp.sampling.SamplingReads
import com.sentri.nativeapp.sampling.SamplingWorkspaceReads
import com.sentri.nativeapp.inspection.DeviceInspectionReads
import com.sentri.nativeapp.inspection.GraphqlDeviceInspectionReads
import com.sentri.nativeapp.inspection.UnitInspectionReads
import com.sentri.nativeapp.inspection.GraphqlUnitInspectionReads
import com.sentri.nativeapp.inspection.UnitCheckInHistoryReads
import com.sentri.nativeapp.inspection.GraphqlUnitCheckInHistoryRemote
import com.sentri.nativeapp.inspection.RecentUnitCheckInsReads
import com.sentri.nativeapp.inspection.GraphqlRecentUnitCheckInsRemote
import com.sentri.nativeapp.inspection.GraphqlUnitInspectionGateway
import com.sentri.nativeapp.inspection.InspectionCheckInService
import com.sentri.nativeapp.inspection.InspectionCheckInWork
import com.sentri.nativeapp.inspection.HealthReferenceReads
import com.sentri.nativeapp.inspection.GraphqlHealthReferenceReads
import com.sentri.nativeapp.inspection.PigMeasurementReads
import com.sentri.nativeapp.inspection.GraphqlPigMeasurementReads
import com.sentri.nativeapp.inspection.LocationInventoryReads
import com.sentri.nativeapp.inspection.GraphqlLocationInventoryReads

/** Explicit constructor injection; UI and tests depend on interfaces rather than a global locator. */
class AppContainer(context: Context) {
    val config = AppConfig.current()
    val language = com.sentri.nativeapp.i18n.AppLanguage(com.sentri.nativeapp.i18n.AndroidLanguageStore(context))
    val businessText = com.sentri.nativeapp.i18n.BusinessText(context, language)
    val auth = AuthRepository(EncryptedSessionStore(context), LogtoAuthGateway(context, config))
    val graphql = ApolloClient.Builder().serverUrl(config.graphqlUrl).build()
    val businessCache = SqliteSnapshotCache(context)
    val scopeGate = ScopeGate(config.environment, auth)
    val faultDrafts = com.sentri.nativeapp.inspection.FileDeviceFaultDraftStore(context)
    val penNoteDrafts = com.sentri.nativeapp.inspection.SnapshotPenNoteDraftStore(businessCache, scopeGate::check)
    val farms = CachedFarmRepository(GraphqlFarmRepository(graphql, auth) { language.current().tag }, businessCache, auth, config.environment) { language.current().tag }
    val pigs = PigRepository(GraphqlPigRemote(GraphqlExecutor(graphql, auth) { language.current().tag }), businessCache, scopeGate::check)
    val homePlaces = HomePlacesReader(pigs)
    private val taskExecutor = GraphqlExecutor(graphql, auth) { language.current().tag }
    val pigProfiles = PigProfileReads(GraphqlPigProfiles(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val taskCatalog = TaskCatalogReads(GraphqlTaskCatalog(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val homeProductionCatalog = HomeProductionCatalogReader(taskCatalog, businessCache, scopeGate::check)
    val productionAuxiliary = ProductionAuxiliaryReads(GraphqlProductionAuxiliary(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val therapy = TherapyReads(GraphqlTherapyReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val vaccination = VaccinationReads(GraphqlVaccinationReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val homeVaccinationCatalog = com.sentri.nativeapp.home.HomeVaccinationCatalogReader(
        vaccination, businessCache, scopeGate::check)
    val homeTransferCatalog = HomeTransferCatalogReader(
        GraphqlHomeTransferRemote(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val homeTransferDetail = HomeTransferDetailReader(
        GraphqlHomeTransferDetailRemote(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val sampling = SamplingReads(GraphqlSamplingReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val deviceInspections = DeviceInspectionReads(GraphqlDeviceInspectionReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val unitInspections = UnitInspectionReads(GraphqlUnitInspectionReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val unitCheckInHistory = UnitCheckInHistoryReads(GraphqlUnitCheckInHistoryRemote(taskExecutor, scopeGate::check),
        businessCache, scopeGate::check)
    val recentUnitCheckIns = RecentUnitCheckInsReads(GraphqlRecentUnitCheckInsRemote(taskExecutor, scopeGate::check),
        businessCache, scopeGate::check)
    val healthReferences = HealthReferenceReads(GraphqlHealthReferenceReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val pigMeasurements = PigMeasurementReads(GraphqlPigMeasurementReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val locationInventory = LocationInventoryReads(GraphqlLocationInventoryReads(taskExecutor, scopeGate::check), businessCache, scopeGate::check)
    val homeUnitStock = com.sentri.nativeapp.home.HomeUnitStockReads(
        com.sentri.nativeapp.home.GraphqlHomeUnitStockRemote(taskExecutor, scopeGate::check),
        businessCache, scopeGate::check)
    val homeUnitPenRoster = com.sentri.nativeapp.home.HomeUnitPenRosterReads(
        com.sentri.nativeapp.home.GraphqlHomeUnitPenRosterRemote(taskExecutor, scopeGate::check),
        businessCache, scopeGate::check)
    val homeUnitMaintenance = HomeUnitMaintenanceReads(GraphqlHomeUnitMaintenanceRemote(taskExecutor,
        GraphqlDeviceInspectionReads(taskExecutor, scopeGate::check), scopeGate::check),
        businessCache, scopeGate::check)
    val estrus = ProductionTaskReads(GraphqlEstrusReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.estrus, scopeGate::check)
    val mating = ProductionTaskReads(GraphqlMatingReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.mating, scopeGate::check)
    val reEstrus = ProductionTaskReads(GraphqlReEstrusReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.reEstrus, scopeGate::check)
    val pregnancy = ProductionTaskReads(GraphqlPregnancyReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.pregnancy, scopeGate::check)
    val farrowing = ProductionTaskReads(GraphqlFarrowingReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.farrowing, scopeGate::check)
    val piglet = ProductionTaskReads(GraphqlPigletReads(taskExecutor, scopeGate::check), businessCache, ProductionTaskCodecs.piglet, scopeGate::check)
    val postpartum = ProductionTaskReads(GraphqlPostpartumReads(taskExecutor, scopeGate::check), businessCache, StageTaskCodecs.postpartum, scopeGate::check)
    val lactation = ProductionTaskReads(GraphqlLactationReads(taskExecutor, scopeGate::check), businessCache, StageTaskCodecs.lactation, scopeGate::check)
    val nursery = ProductionTaskReads(GraphqlNurseryReads(taskExecutor, scopeGate::check), businessCache, StageTaskCodecs.nursery, scopeGate::check)
    val fattening = ProductionTaskReads(GraphqlFatteningReads(taskExecutor, scopeGate::check), businessCache, StageTaskCodecs.fattening, scopeGate::check)
    val commandQueue = com.sentri.nativeapp.offline.SqliteCommandQueue(context)
    val inspectionCheckInWork = InspectionCheckInWork(context)
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val inspectionCheckIn = InspectionCheckInService(commandQueue, GraphqlUnitInspectionGateway(taskExecutor),
        unitInspections, scopeGate, auth, online = {
            connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        }, onSaved = inspectionCheckInWork::schedule)
    val therapyWorkspace = TherapyWorkspaceReads(therapy, commandQueue, scopeGate::check)
    val vaccinationWorkspace = VaccinationWorkspaceReads(vaccination, commandQueue, scopeGate::check)
    val samplingWorkspace = SamplingWorkspaceReads(sampling, commandQueue, scopeGate::check)
    val productionWorkspace = ProductionWorkspaceReads(taskCatalog, mapOf<ProductionTaskKind, ProductionDetailRead>(
        ProductionTaskKind.ESTRUS to { lease, id, offline -> estrus.task(lease, id, offline) },
        ProductionTaskKind.MATING to { lease, id, offline -> mating.task(lease, id, offline) },
        ProductionTaskKind.RE_ESTRUS to { lease, id, offline -> reEstrus.task(lease, id, offline) },
        ProductionTaskKind.PREGNANCY to { lease, id, offline -> pregnancy.task(lease, id, offline) },
        ProductionTaskKind.FARROWING to { lease, id, offline -> farrowing.task(lease, id, offline) },
        ProductionTaskKind.PIGLET_PROCESSING to { lease, id, offline -> piglet.task(lease, id, offline) },
        ProductionTaskKind.POSTPARTUM to { lease, id, offline -> postpartum.task(lease, id, offline) },
        ProductionTaskKind.LACTATION to { lease, id, offline -> lactation.task(lease, id, offline) },
        ProductionTaskKind.NURSERY to { lease, id, offline -> nursery.task(lease, id, offline) },
        ProductionTaskKind.FATTENING to { lease, id, offline -> fattening.task(lease, id, offline) },
    ), commandQueue, scopeGate::check, pigProfiles)
    val updates = ApkUpdateRepository(context)
}
