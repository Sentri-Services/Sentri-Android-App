package com.sentri.nativeapp.i18n

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.sentri.nativeapp.R

/** Provides localized text without changing activity configuration or building a language picker. */
class BusinessText(context: Context, private val language: AppLanguage) {
    private val context = context.applicationContext
    private fun localized(): Context = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        setLocales(LocaleList(language.current().locale))
    })
    fun enum(group: String, code: String?): String {
        val resources = localized().resources
        if (code == null) return resources.getString(R.string.biz_missing)
        val key = BusinessEnumKeys.key(group, code) ?: return code
        val id = resources.getIdentifier(key, "string", context.packageName)
        check(id != 0) { "Missing business enum resource" }
        return resources.getString(id)
    }
    fun pigCount(count: Int): String {
        require(count >= 0)
        return localized().resources.getQuantityString(R.plurals.biz_pig_count, count, count)
    }
}
