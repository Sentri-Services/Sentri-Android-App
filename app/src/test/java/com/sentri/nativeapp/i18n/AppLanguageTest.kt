package com.sentri.nativeapp.i18n

import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import java.util.TimeZone

class AppLanguageTest {
    private class Store : LanguageStore {
        var choice = LanguageChoice.SYSTEM
        var fail = false
        override fun read() = choice
        override fun write(choice: LanguageChoice) { check(!fail); this.choice = choice }
    }
    @Test fun `selection persists resolves system changes and unsupported languages`() {
        val store = Store(); var system = Locale.CHINA
        val language = AppLanguage(store) { system }
        assertEquals(SupportedLanguage.CHINESE, language.current())
        language.select(LanguageChoice.ENGLISH); system = Locale.TAIWAN
        assertEquals(SupportedLanguage.ENGLISH, AppLanguage(store) { system }.current())
        language.select(LanguageChoice.SYSTEM)
        assertEquals(SupportedLanguage.CHINESE, language.current())
        system = Locale.FRANCE
        assertEquals(SupportedLanguage.ENGLISH, language.current())
        assertEquals(SupportedLanguage.ENGLISH, language.state.value.language)
        store.fail = true
        try { language.select(LanguageChoice.CHINESE); fail() } catch (_: IllegalStateException) { }
        assertEquals(LanguageChoice.SYSTEM, language.state.value.choice)
    }
    @Test fun `formatting keeps precision uses explicit timezone and bilingual fallback`() {
        val en = BusinessFormatter(SupportedLanguage.ENGLISH)
        assertEquals("1,234.123456789", en.number(BigDecimal("1234.123456789")))
        assertEquals("1.24", en.number(BigDecimal("1.235"), 2))
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals(en.timestamp("2026-01-01T00:00:00.999999+08:00", utc), en.timestamp("2025-12-31T16:00:00Z", utc))
        assertNotEquals(en.timestamp(0, utc), BusinessFormatter(SupportedLanguage.CHINESE).timestamp(0, utc))
        assertEquals("中文", en.name("中文", " ", "id"))
        assertEquals("id", en.name(null, null, "id"))
        try { en.timestamp("2026-02-30T00:00:00Z", utc); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun `enum keys preserve canonical case unknown values and domain distinctions`() {
        assertEquals("biz_role_production_sow", BusinessEnumKeys.key("role", "PRODUCTION_SOW"))
        assertNull(BusinessEnumKeys.key("role", "production_sow"))
        assertNull(BusinessEnumKeys.key("role", "NEW_ROLE"))
        assertNotEquals(BusinessEnumKeys.key("production", "IN_ESTRUS"), BusinessEnumKeys.key("estrus", "IN_ESTRUS"))
    }
}
