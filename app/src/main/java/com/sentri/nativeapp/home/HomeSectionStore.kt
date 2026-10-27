package com.sentri.nativeapp.home

import android.content.Context
import com.sentri.nativeapp.pigs.DataScope
import java.security.MessageDigest

/** A UI preference only. No section selection is inferred from task or pig data. */
interface HomeSectionStore {
    fun read(scope: DataScope): String?
    fun write(scope: DataScope, sectionId: String)
}

class AndroidHomeSectionStore(context: Context, name: String = "sentri_home_sections") : HomeSectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    override fun read(scope: DataScope): String? = preferences.getString(key(scope), null)?.takeIf(String::isNotBlank)

    override fun write(scope: DataScope, sectionId: String) {
        require(sectionId.isNotBlank())
        check(preferences.edit().putString(key(scope), sectionId).commit()) {
            "Could not persist the selected section"
        }
    }

    private fun key(scope: DataScope): String {
        val identity = listOf(scope.environment, scope.oauthId, scope.userId, scope.farmId)
            .joinToString("") { "${it.length}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
