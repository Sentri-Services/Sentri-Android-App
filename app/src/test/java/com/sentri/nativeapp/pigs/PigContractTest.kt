package com.sentri.nativeapp.pigs

import com.apollographql.apollo.api.CustomScalarAdapters
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.api.json.BufferedSourceJsonReader
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.test.runTest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class PigContractTest {
    private val scope = DataScope("dev", "oauth", "user", "farm")
    @Test fun `case sensitive literal search and unit regex preserve web semantics`() {
        val where = GraphqlPigRemote.where(scope, PigFilter("Z-A-U", search = "Ab%_\\"))
        assertEquals("farm", where.farm_id.getOrNull()?._eq?.getOrNull())
        assertEquals("Z-A-U", where.location_code.getOrNull()?._iregex?.getOrNull())
        assertEquals("%Ab\\%\\_\\\\%", where._or.getOrNull()?.first()?.ear_tag?.getOrNull()?._like?.getOrNull())
        assertTrue(where.ear_tag is Optional.Absent)
    }
    @Test fun `cache keys distinguish case null delimiters filters offset and page size`() {
        val filters = listOf(PigFilter(search = "A"), PigFilter(search = "a"), PigFilter(search = "a:"), PigFilter(penLocation = ""), PigFilter(), PigFilter(pageSize = 30))
        assertEquals(filters.size, filters.map { it.cacheKey(0) }.distinct().size)
        assertNotEquals(PigFilter().cacheKey(0), PigFilter().cacheKey(20))
    }
    @Test fun `decimal and nested JSON preserve precision and null values`() {
        val decimal = DecimalAdapter.fromJson(BufferedSourceJsonReader(Buffer().writeUtf8("1234567890.123456789")), CustomScalarAdapters.Empty)
        assertEquals(BigDecimal("1234567890.123456789"), decimal)
        val json = """{"value":1234567890.123456789,"items":[null,true,"tag"]}"""
        assertEquals(json, JsonValueAdapter.fromJson(BufferedSourceJsonReader(Buffer().writeUtf8(json)), CustomScalarAdapters.Empty).encoded)
    }
    @Test fun `timestamps retain timezone microseconds and reject invalid calendar dates`() {
        val value = "2026-10-09T12:30:45.123456+08:00"
        assertEquals(value, TimestampAdapter.validate(value))
        listOf("2026-02-30T00:00:00Z", "2026-10-09", "2026-10-09T00:00:00+99:00").forEach {
            try { TimestampAdapter.validate(it); fail("Accepted invalid timestamp") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun `farm changes invalidate leases and token refresh does not`() = runTest {
        val auth = AuthRepository(MemorySessionStore(validSession().copy(farmId = "farm")), FakeAuthGateway()) { 0 }
        val gate = ScopeGate("dev", auth)
        val account = AccountContext(Identity("user", "test", null, "role", emptyList()), Farm("farm", "farm"))
        val lease = gate.lease(account)
        auth.refreshRejectedToken("test-token"); gate.check(lease)
        auth.clearFarm()
        try { gate.check(lease); fail("Old farm lease survived") } catch (_: RequestContextChanged) { }
    }
    @Test fun `logout and login to same farm cannot reuse an old lease`() = runTest {
        val auth = AuthRepository(MemorySessionStore(validSession().copy(farmId = "farm")), FakeAuthGateway()) { 0 }
        val gate = ScopeGate("dev", auth)
        val lease = gate.lease(AccountContext(Identity("user", "test", null, "role", emptyList()), Farm("farm", "farm")))
        auth.signOut()
        try { gate.check(lease); fail("Logged out lease survived") } catch (_: RequestContextChanged) { }
    }
}
