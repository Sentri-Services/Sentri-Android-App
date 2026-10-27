package com.sentri.nativeapp

import androidx.test.platform.app.InstrumentationRegistry
import com.sentri.nativeapp.i18n.*
import com.sentri.nativeapp.pigs.*
import com.sentri.nativeapp.auth.*
import com.sentri.nativeapp.network.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class LanguageFoundationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun persistedSelectionResourcesAndLanguageScopedAccountCache() {
        val name = "language-test-${UUID.randomUUID()}"
        try {
            val language = AppLanguage(AndroidLanguageStore(context, name))
            val text = BusinessText(context, language)
            language.select(LanguageChoice.CHINESE)
            assertEquals("生产母猪", text.enum("role", "PRODUCTION_SOW"))
            assertEquals("未提供", text.enum("role", null))
            // Breed acronyms retain their canonical spelling in both languages; do not invent their expansion.
            val invariantCodes = setOf("breed_type:YLL", "breed_type:YLP")
            for ((group, codes) in BusinessEnumKeys.keys) for (code in codes.keys) {
                if ("$group:$code" in invariantCodes) assertEquals(code, text.enum(group, code))
                else assertNotEquals(code, text.enum(group, code))
            }
            val chinese = BusinessEnumKeys.keys.flatMap { (group, codes) -> codes.keys.map { code -> "$group:$code" to text.enum(group, code) } }.toMap()
            language.select(LanguageChoice.ENGLISH)
            assertEquals("Production Sow", text.enum("role", "PRODUCTION_SOW"))
            for ((group, codes) in BusinessEnumKeys.keys) for (code in codes.keys) {
                if ("$group:$code" in invariantCodes) assertEquals(code, text.enum(group, code))
                else assertNotEquals(chinese["$group:$code"], text.enum(group, code))
            }
            assertEquals("1 pig", text.pigCount(1)); assertEquals("2 pigs", text.pigCount(2))
            assertEquals("FUTURE_ROLE", text.enum("role", "FUTURE_ROLE"))
            assertEquals(SupportedLanguage.ENGLISH, AppLanguage(AndroidLanguageStore(context, name)).current())
            SqliteSnapshotCache(context, name).use { cache ->
                val scope = DataScope("dev", "oauth", "user", "farm")
                cache.write(scope, "account", "current:zh-CN", StoredSnapshot("中文", 1))
                cache.write(scope, "account", "current:en", StoredSnapshot("English", 2))
                assertEquals("中文", cache.readAccount("dev", "oauth", "farm", "zh-CN")?.snapshot?.payload)
                assertEquals("English", cache.readAccount("dev", "oauth", "farm", "en")?.snapshot?.payload)
                assertNull(cache.readAccount("dev", "oauth", "farm"))
                cache.write(scope.copy(userId = "new-user"), "account", "current:en", StoredSnapshot("New", 3))
                assertNull(cache.readAccount("dev", "oauth", "farm", "zh-CN"))
                cache.clear(scope.copy(userId = "new-user"))
                assertNull(cache.readAccount("dev", "oauth", "farm", "en"))
            }
        } finally { context.deleteDatabase(name); context.getSharedPreferences(name, 0).edit().clear().commit() }
    }
    @Test fun accountFallbackAndDelayedResponseRespectSelectedLanguage() = runBlocking {
        val name = "account-language-${UUID.randomUUID()}"
        val session = Session("simulated", "simulated", 999999, "oauth", "farm")
        val sessionStore = object : SessionStore {
            var value: Session? = session
            override fun readSession() = value
            override fun saveSession(session: Session?) { value = session }
            override fun readPendingLogin(): LoginRequest? = null
            override fun savePendingLogin(request: LoginRequest?) { }
        }
        val auth = AuthRepository(sessionStore, object : AuthGateway {
            override suspend fun beginLogin(forcePrompt: Boolean): LoginRequest = error("Unused")
            override suspend fun exchange(request: LoginRequest, callbackUrl: String): Session = error("Unused")
            override suspend fun refresh(session: Session): Session = error("Unused")
            override fun logoutUrl(session: Session): String? = null
        }) { 0 }
        val language = AppLanguage(AndroidLanguageStore(context, name))
        var offline = false; var delayed = false
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val remote = object : FarmRepository {
            override suspend fun choices(oauthId: String): List<FarmChoice> = error("Unused")
            override suspend fun choose(choice: FarmChoice) = Unit
            override suspend fun account(): AccountContext {
                if (offline) throw ApiFailure.Network()
                val label = if (language.current() == SupportedLanguage.CHINESE) "中文厂区" else "English farm"
                if (delayed) { entered.complete(Unit); release.await() }
                return AccountContext(Identity("user", "name", null, "user", emptyList()), Farm("farm", label))
            }
        }
        try { SqliteSnapshotCache(context, name).use { cache ->
            val repository = CachedFarmRepository(remote, cache, auth, "dev") { language.current().tag }
            language.select(LanguageChoice.CHINESE); assertEquals("中文厂区", repository.account().farm.name)
            assertEquals("中文厂区", repository.cachedAccountOrNull()?.farm?.name)
            language.select(LanguageChoice.ENGLISH); offline = true
            assertNull(repository.cachedAccountOrNull())
            try { repository.account(); fail("Wrong-language fallback accepted") } catch (_: ApiFailure.Network) { }
            assertNotNull(auth.session.value)
            offline = false; assertEquals("English farm", repository.account().farm.name)
            assertEquals("English farm", repository.cachedAccountOrNull()?.farm?.name)
            offline = true; assertNotNull(repository.account().cachedAtMillis)
            language.select(LanguageChoice.CHINESE); assertEquals("中文厂区", repository.account().farm.name)
            offline = false; delayed = true
            val old = async { try { repository.account(); false } catch (_: RequestContextChanged) { true } }
            entered.await(); language.select(LanguageChoice.ENGLISH); release.complete(Unit)
            assertTrue(old.await())
            offline = true; assertEquals("English farm", repository.account().farm.name)
        } } finally { context.deleteDatabase(name); context.getSharedPreferences(name, 0).edit().clear().commit() }
    }

}
