package com.sentri.nativeapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.net.ConnectivityManager
import com.apollographql.apollo.api.Optional
import com.sentri.nativeapp.graphql.GetFarmTimezoneQuery
import com.sentri.nativeapp.graphql.NativePigProfilesQuery
import com.sentri.nativeapp.graphql.type.Farm_view_pig_order_by
import com.sentri.nativeapp.graphql.type.Order_by
import com.sentri.nativeapp.inspection.hasRecordedCondition
import com.sentri.nativeapp.network.FarmTimezone
import com.sentri.nativeapp.network.GraphqlExecutor
import com.sentri.nativeapp.offline.CommandStatus
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.vaccination.VaccinationPageRequest
import com.sentri.nativeapp.vaccination.VaccinationReadMode
import com.sentri.nativeapp.therapy.TherapyPageRequest
import com.sentri.nativeapp.therapy.TherapyReadOrder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit opt-in real dev checks. The background check-in case enqueues one real write. */
@RunWith(AndroidJUnit4::class)
class LivePigReadTest {
    @Test fun developmentInspectionCompleteRosterRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveInspectionRoster") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in and select a dev farm first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val units = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, units.source)
        var total = 0
        var health = 0
        var batchIdentity = 0
        var verifiedBatch = 0
        val batchPigIds = mutableSetOf<String>()
        for (unit in units.value.units) {
            val result = container.pigProfiles.completeUnitRoster(lease, unit.id)
            assertEquals(DataSource.NETWORK, result.source)
            assertEquals(result.value.total, result.value.pigs.size)
            assertTrue(result.value.pigs.all { pig ->
                pig.farmId == lease.scope.farmId && pig.location?.unitId == unit.id &&
                    pig.lifecycle == "ACTIVE" && (pig.batch == null ||
                    (pig.batch.id == pig.currentBatchId && pig.batch.farmId == lease.scope.farmId))
            })
            assertEquals(result.value, container.pigProfiles.completeUnitRoster(lease, unit.id, true).value)
            total += result.value.total
            health += result.value.pigs.count(::hasRecordedCondition)
            batchIdentity += result.value.pigs.count { it.currentBatchId != null }
            verifiedBatch += result.value.pigs.count { it.batch != null }
            batchPigIds += result.value.pigs.filter { it.currentBatchId != null }.map { it.id }
        }
        var missingRelation = 0
        var missingFarm = 0
        var foreignFarm = 0
        var mismatchedId = 0
        val executor = GraphqlExecutor(container.graphql, container.auth) { container.language.current().tag }
        batchPigIds.toList().chunked(100).forEach { ids ->
            val request = PigProfileRequest(PigFilter(pageSize = 100), ids = ids.toSet(), includeCount = false)
            val raw = executor.query(NativePigProfilesQuery(GraphqlPigProfiles.where(lease.scope, request),
                0, 100, listOf(Farm_view_pig_order_by(id = Optional.Present(Order_by.asc))), false)).pigs
            assertEquals(ids.toSet(), raw.map { it.id }.toSet())
            missingRelation += raw.count { it.current_batch == null }
            missingFarm += raw.count { it.current_batch != null && it.current_batch.farm_id == null }
            foreignFarm += raw.count { it.current_batch?.farm_id?.let { farm -> farm != lease.scope.farmId } == true }
            mismatchedId += raw.count { it.current_batch != null && it.current_batch.id != it.current_batch_id }
        }
        println("LIVE_INSPECTION_ROSTER_OK units=${units.value.units.size} pigs=$total " +
            "health=$health currentBatch=$batchIdentity verifiedBatch=$verifiedBatch " +
            "missingBatchRelation=$missingRelation missingBatchFarm=$missingFarm " +
            "foreignBatchFarm=$foreignFarm mismatchedBatchId=$mismatchedId cache=true")
    }

    @Test fun developmentTherapyHomeCatalogRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTherapyHome") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in and select a dev farm first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val rows = mutableListOf<com.sentri.nativeapp.therapy.TherapyRow>()
        var offset = 0
        var more: Boolean
        do {
            val request = TherapyPageRequest(statuses = setOf("InProgress", "NotStarted"),
                order = TherapyReadOrder.ID_ASC, offset = offset, limit = 200)
            val page = container.therapy.page(lease, request)
            assertEquals(DataSource.NETWORK, page.source)
            rows += page.value.rows
            offset += page.value.rows.size
            more = page.value.hasMore
            assertTrue("Therapy catalogue pagination must make progress", !page.value.hasMore || page.value.rows.isNotEmpty())
            assertTrue("Bound explicit dev catalogue inspection", offset <= 4_000)
        } while (more)
        assertEquals(rows.size, rows.map { it.id }.distinct().size)
        val targets = rows.flatMap { it.tasks }
        android.util.Log.i("SentriLiveTherapy", "rows=${rows.size} tasks=${targets.size} " +
            "therapyStatuses=${rows.groupingBy { it.status }.eachCount().toSortedMap()} " +
            "taskStatuses=${targets.groupingBy { it.status }.eachCount().toSortedMap()} " +
            "missingSchedule=${targets.count { it.scheduledAt == null }} " +
            "distinctPrescriptions=${rows.map { it.prescriptionId }.distinct().size} " +
            "missingPig=${targets.count { it.pig == null }} " +
            "missingUnit=${targets.count { it.pig?.location?.unitLocation.isNullOrBlank() }} " +
            "prescriptionStatusMismatch=${rows.count { it.prescription?.status != it.status }} " +
            "distinctUnits=${targets.mapNotNull { it.pig?.location?.unitLocation }.distinct().size}")
        Unit
    }

    @Test fun developmentFarmTimezoneContract() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveFarmTimezone") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in and select a dev farm first", container.auth.session.value)
        val account = container.farms.account()
        assertNull("This contract check needs a live account response", account.cachedAtMillis)
        val response = GraphqlExecutor(container.graphql, container.auth) { container.language.current().tag }
            .query(GetFarmTimezoneQuery()).biosafety.farm_info
        assertEquals(account.farm.id, response.id)
        assertEquals("The farm timezone must be a recognized IANA ID", response.timezone,
            FarmTimezone.validated(response.timezone))
        assertEquals(response.timezone, account.farm.timezone)
        println("LIVE_FARM_TIMEZONE_OK validIana=true")
    }

    @Test fun developmentInspectionFarmSearchRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveInspectionFarmSearch") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val units = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, units.source)
        var sample: PigProfile? = null
        for (unit in units.value.units) {
            val result = container.pigProfiles.page(lease, PigProfileRequest(
                PigFilter(pageSize = 30), lifecycles = setOf(PigLifecycle.ACTIVE), unitId = unit.id))
            sample = result.value.pigs.firstOrNull { pig ->
                listOf(pig.earTag, pig.earNotch, pig.extendedEarTag).any { !it.isNullOrBlank() }
            }
            if (sample != null) break
        }
        val searchable = sample ?: error("Selected dev farm has no searchable active pig")
        val code = listOf(searchable.earTag, searchable.earNotch, searchable.extendedEarTag)
            .first { !it.isNullOrBlank() }!!
        val request = PigProfileRequest(PigFilter(search = code, pageSize = 30),
            lifecycles = setOf(PigLifecycle.ACTIVE), order = PigProfileOrder.LOCATION_IDENTITY)
        val result = container.pigProfiles.page(lease, request)
        assertEquals(DataSource.NETWORK, result.source)
        assertTrue(result.value.pigs.all { it.farmId == lease.scope.farmId && it.lifecycle == "ACTIVE" })
        assertTrue(result.value.pigs.any { it.id == searchable.id })
        assertEquals(result.value, container.pigProfiles.page(lease, request, true).value)
        println("LIVE_INSPECTION_FARM_SEARCH_OK resultCount=${result.value.total} cache=true")
    }

    @Test fun developmentInspectionExactScanRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveInspectionScan") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val locations = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, locations.source)
        var uniqueFound = false
        for (unit in locations.value.units.take(3)) {
            val roster = container.pigProfiles.page(lease, PigProfileRequest(
                PigFilter(pageSize = 100), lifecycles = setOf(PigLifecycle.ACTIVE), unitId = unit.id))
            assertEquals(DataSource.NETWORK, roster.source)
            for (pig in roster.value.pigs.take(10)) {
                val codes = listOf(pig.earTag, pig.earNotch, pig.extendedEarTag)
                    .filterNotNull().filter(String::isNotBlank).distinct()
                for (code in codes) {
                    val result = container.pigProfiles.exactEarCode(lease, code)
                    assertEquals(DataSource.NETWORK, result.source)
                    assertTrue(result.value.all { it.farmId == lease.scope.farmId &&
                        code in listOf(it.earTag, it.earNotch, it.extendedEarTag) })
                    if (result.value.size == 1 && result.value.single().id == pig.id) {
                        assertEquals(result.value, container.pigProfiles.exactEarCode(lease, code, true).value)
                        uniqueFound = true
                        break
                    }
                }
                if (uniqueFound) break
            }
            if (uniqueFound) break
        }
        assertTrue("Expected an active pig with a unique ear code in the selected dev farm", uniqueFound)
        println("LIVE_INSPECTION_SCAN_OK exact=true cache=true")
    }

    @Test fun developmentCheckInHistoryDetailRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveCheckInDetail") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val locations = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, locations.source)
        var checked = 0
        for (unit in locations.value.units) {
            val page = container.recentUnitCheckIns.page(lease, unit.locationCode)
            assertEquals(DataSource.NETWORK, page.source)
            val entry = page.value.entries.firstOrNull() ?: continue
            val detail = container.unitInspections.byId(lease, unit.locationCode, entry.id)
            assertEquals(DataSource.NETWORK, detail.source)
            assertEquals(entry.id, detail.value?.id)
            assertEquals(unit.locationCode, detail.value?.unitCode)
            assertEquals(detail.value, container.unitInspections.byId(lease, unit.locationCode,
                entry.id, cacheOnly = true).value)
            checked++
        }
        assertTrue("Expected at least one server check-in in the selected dev farm", checked > 0)
        println("LIVE_CHECK_IN_DETAIL_OK unitsWithRecords=$checked cache=true")
    }

    @Test fun developmentUnitMaintenanceRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveMaintenanceRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val locations = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, locations.source)
        var openTotal = 0
        locations.value.units.forEach { unit ->
            val result = container.homeUnitMaintenance.current(lease, unit.id)
            assertEquals(DataSource.NETWORK, result.source)
            assertEquals(result.value, container.homeUnitMaintenance.current(lease, unit.id, true).value)
            assertTrue(result.value.records.all { it.penCode in result.value.penCodes })
            openTotal += result.value.open.size
        }
        println("LIVE_UNIT_MAINTENANCE_OK units=${locations.value.units.size} open=$openTotal cache=true")
    }

    @Test fun developmentUnitRegisteredPenRosterRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveUnitPens") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val locations = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, locations.source)
        assertTrue("Development fixture needs a unit", locations.value.units.isNotEmpty())
        var registeredTotal = 0
        var occupiedTotal = 0
        var emptyTotal = 0
        var unknownCodeTotal = 0
        locations.value.units.forEach { unit ->
            val roster = container.homeUnitPenRoster.current(lease, unit.id)
            assertEquals(DataSource.NETWORK, roster.source)
            val registered = requireNotNull(roster.value.registeredPens)
            assertEquals(roster.value, container.homeUnitPenRoster.current(lease, unit.id, true).value)
            val occupied = roster.value.pens.map { it.code }.toSet()
            registeredTotal += registered.size
            occupiedTotal += occupied.size
            emptyTotal += registered.count { it.code != null && it.code !in occupied }
            unknownCodeTotal += registered.count { it.code == null }
        }
        println("LIVE_UNIT_PENS_OK units=${locations.value.units.size} registered=$registeredTotal occupied=$occupiedTotal empty=$emptyTotal unknownCode=$unknownCodeTotal cache=true")
    }

    @Test fun developmentPenPopulationRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePenPopulation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Use the existing development session", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val places = container.pigs.locations(lease)
        val unit = places.value.units.firstOrNull { it.nameEn.replace(" ", "") == "Unit2" }
            ?: error("Expected test unit is unavailable")
        val roster = container.homeUnitPenRoster.current(lease, unit.id)
        val pen = roster.value.registeredPens.orEmpty().firstOrNull { it.number == 2 && it.code != null }
            ?: error("Expected registered test pen is unavailable")
        val penCode = requireNotNull(pen.code)
        val inventory = container.locationInventory.current(lease, unit.id, penCode)
        assertEquals(DataSource.NETWORK, inventory.source)
        val value = inventory.value
        val unassigned = value.nonIdentityByBatch[null] ?: 0
        assertEquals(value, container.locationInventory.current(lease, unit.id, penCode, true).value)
        var namedBatches = 0
        var unresolvedBatches = 0
        for (id in value.nonIdentityByBatch.keys.filterNotNull()) {
            val result = container.productionAuxiliary.batch(lease, id)
            assertEquals(DataSource.NETWORK, result.source)
            val batch = result.value
            assertEquals(batch, container.productionAuxiliary.batch(lease, id, true).value)
            if (batch == null) {
                unresolvedBatches++
                continue
            }
            assertEquals(id, batch.id)
            assertEquals(lease.scope.farmId, batch.farmId)
            assertTrue(batch.serial.isNotBlank())
            namedBatches++
        }
        println("LIVE_PEN_BATCH_LABELS_READ verified=$namedBatches unresolved=$unresolvedBatches cache=true")
        println("LIVE_PEN_POPULATION_OK total=${value.totalCount} identified=${value.identityCount} " +
            "unidentified=${value.totalCount - value.identityCount} unassigned=$unassigned " +
            "batchGroups=${value.nonIdentityByBatch.keys.count { it != null }} cache=true")
    }

    @Test fun developmentTreatmentDirectoryRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveTreatmentDirectory") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val medications = container.therapy.medications(lease)
        val catalogs = container.therapy.catalogs(lease)
        val methods = container.therapy.treatments(lease)
        assertEquals(DataSource.NETWORK, medications.source)
        assertEquals(DataSource.NETWORK, catalogs.source)
        assertEquals(DataSource.NETWORK, methods.source)
        assertEquals(medications.value, container.therapy.medications(lease, true).value)
        assertEquals(catalogs.value, container.therapy.catalogs(lease, true).value)
        assertEquals(methods.value, container.therapy.treatments(lease, true).value)
        println("LIVE_TREATMENT_DIRECTORY_OK medications=${medications.value.size} catalogs=${catalogs.value.size} methods=${methods.value.size} cache=true")
    }

    @Test fun developmentOfflineCheckInBackgroundPrepare() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("liveBackgroundCheckIn")
        assumeTrue(phase in setOf("prepare", "reschedule"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.ACCESS_NETWORK_STATE)
        try { assertNull("Disable device connectivity before preparing check-in", context.getSystemService(ConnectivityManager::class.java).activeNetwork) }
        finally { automation.dropShellPermissionIdentity() }
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        val account = container.farms.account()
        val lease = container.scopeGate.lease(account)
        if (phase == "prepare") {
            val unit = container.pigs.locations(lease, true).value.units.firstOrNull()
                ?: error("Read and cache unit locations while online first")
            val command = container.inspectionCheckIn.save(lease, unit)
            assertEquals(CommandStatus.QUEUED, command.record.status)
            println("LIVE_BACKGROUND_CHECKIN_PREPARE_OK queued=true")
        } else {
            container.inspectionCheckInWork.ensureScheduled(lease)
            println("LIVE_BACKGROUND_CHECKIN_RESCHEDULE_OK")
        }
    }

    @Test fun developmentInspectionReferenceReads() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveInspectionReads") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val catalog = container.healthReferences.catalog(lease)
        assertEquals(DataSource.NETWORK, catalog.source)
        val faults = container.deviceInspections.list(lease)
        assertEquals(DataSource.NETWORK, faults.source)
        assertTrue(faults.value.all { it.farmId == lease.scope.farmId })
        assertEquals(catalog.value, container.healthReferences.catalog(lease, true).value)
        assertEquals(faults.value, container.deviceInspections.list(lease, true).value)
        faults.value.firstOrNull()?.penCode?.let { penCode ->
            val penFaults = container.deviceInspections.forPen(lease, penCode)
            assertEquals(DataSource.NETWORK, penFaults.source)
            assertEquals(faults.value.filter { it.penCode == penCode }.toSet(), penFaults.value.toSet())
        }
        println("LIVE_INSPECTION_READ_OK diseases=${catalog.value.diseases.size} symptoms=${catalog.value.symptoms.size} faults=${faults.value.size} cache=true")
    }

    @Test fun developmentVaccinationHomeCatalogRead() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveVaccinationRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val lease = container.scopeGate.lease(container.farms.account())
        val page = try {
            container.vaccination.page(lease, VaccinationPageRequest(mode = VaccinationReadMode.ACTIVE,
                limit = 1))
        } catch (error: Exception) {
            println("LIVE_VACCINATION_READ_FAIL stage=firstPage type=${error::class.java.simpleName}")
            throw error
        }
        assertEquals(DataSource.NETWORK, page.source)
        val catalog = try { container.homeVaccinationCatalog.load(lease) }
        catch (error: Exception) {
            println("LIVE_VACCINATION_READ_FAIL stage=homeCatalog type=${error::class.java.simpleName}")
            throw error
        }
        assertEquals(DataSource.NETWORK, catalog.source)
        println("LIVE_VACCINATION_READ_OK firstPageRows=${page.value.rows.size} catalogRows=${catalog.value.size}")
    }
    @Test fun disconnectedProcessRestoresAccountBrowseAndData() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("offlineRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.ACCESS_NETWORK_STATE)
        try { assertNull("Disable device connectivity first", context.getSystemService(ConnectivityManager::class.java).activeNetwork) }
        finally { automation.dropShellPermissionIdentity() }
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        val account = container.farms.account()
        assertNotNull("Account must be restored from cache", account.cachedAtMillis)
        val lease = container.scopeGate.lease(account)
        val position = requireNotNull(container.pigs.restore(lease))
        assertFalse("This proves network failure fallback rather than forcing cache mode", position.cacheOnly)
        val locations = container.pigs.locations(lease)
        val page = container.pigs.page(lease, position.filter, position.offset)
        val detail = container.pigs.detail(lease, requireNotNull(position.detailId))
        assertTrue(listOf(locations.source, page.source, detail.source).all { it == DataSource.CACHE })
        assertTrue(listOf(locations.refreshFailure, page.refreshFailure, detail.refreshFailure).all { it != null })
        assertEquals(position.detailId, detail.value?.pig?.id)
        println("OFFLINE_PROCESS_OK activeNetwork=false accountCache=true browse=true locations=true page=true detail=true")
    }
    @Test fun developmentReadSearchPagingAndPersistentSnapshots() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveRead") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = (context.applicationContext as SentriApplication).container
        assertEquals("dev", container.config.environment)
        assertNotNull("Log in through the development WebView first", container.auth.session.value)
        val account = container.farms.account()
        val lease = container.scopeGate.lease(account)
        val locations = container.pigs.locations(lease)
        assertEquals(DataSource.NETWORK, locations.source)
        assertTrue("Development fixture needs a unit", locations.value.units.isNotEmpty())
        assertTrue(locations.value.units.all { it.farmId == account.farm.id })
        assertTrue(locations.value.pens.all { it.farmId == account.farm.id })
        var selected: Pair<FarmUnit, PigPage>? = null
        for (unit in locations.value.units) {
            val page = container.pigs.page(lease, PigFilter(unit.locationCode, pageSize = 1), 0)
            assertEquals(DataSource.NETWORK, page.source)
            if (page.value.total > 1) { selected = unit to page.value; break }
        }
        val (unit, first) = requireNotNull(selected) { "Development fixture needs a unit with two pigs" }
        val pig = first.pigs.single()
        val filter = PigFilter(unit.locationCode, pageSize = 1)
        val second = container.pigs.page(lease, filter, 1)
        assertEquals(first.total, second.value.total)
        assertNotEquals(pig.id, second.value.pigs.single().id)
        assertTrue(pig.id < second.value.pigs.single().id)
        val term = pig.earTag?.takeIf { it.isNotEmpty() } ?: pig.earNotch?.takeIf { it.isNotEmpty() }
        assertNotNull("Development fixture needs an ear tag or notch", term)
        val search = container.pigs.page(lease, filter.copy(search = term!!, pageSize = 100), 0)
        assertTrue(search.value.pigs.any { it.id == pig.id })
        assertTrue(search.value.pigs.all { it.earTag?.contains(term) == true || it.earNotch?.contains(term) == true })
        pig.penLocation?.let { pen ->
            val scoped = container.pigs.page(lease, filter.copy(penLocation = pen), 0)
            assertTrue(scoped.value.pigs.all { it.penLocation == pen })
        }
        val detail = container.pigs.detail(lease, pig.id)
        assertEquals(DataSource.NETWORK, detail.source)
        assertEquals(pig.id, detail.value?.pig?.id)
        val position = BrowsePosition(filter, 0, pig.id, false)
        container.pigs.remember(lease, position)
        // Reopen the real database through a new helper and make any HTTP call a test failure.
        SqliteSnapshotCache(context).use { reopened ->
            val offline = PigRepository(object : PigRemote {
                override suspend fun locations(scope: DataScope): Locations = error("Unexpected HTTP")
                override suspend fun page(scope: DataScope, filter: PigFilter, offset: Int): PigPage = error("Unexpected HTTP")
                override suspend fun detail(scope: DataScope, id: String): PigDetail? = error("Unexpected HTTP")
            }, reopened, container.scopeGate::check)
            assertEquals(locations.value, offline.locations(lease, true).value)
            assertEquals(first, offline.page(lease, filter, 0, true).value)
            assertEquals(detail.value, offline.detail(lease, pig.id, true).value)
            assertEquals(position, offline.restore(lease))
        }
        // Counts only: do not include identifiers, ear tags, or response bodies in logs.
        println("LIVE_READ_OK units=${locations.value.units.size} pens=${locations.value.pens.size} unitTotal=${first.total} paging=true search=true detail=true dbReopen=true")
    }
}
