package com.sentri.nativeapp.i18n

import android.content.Context

class AndroidLanguageStore(context: Context, name: String = "sentri_language") : LanguageStore {
    private val preferences = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override fun read(): LanguageChoice = preferences.getString("choice", null)?.let {
        runCatching { LanguageChoice.valueOf(it) }.getOrNull()
    } ?: LanguageChoice.SYSTEM
    override fun write(choice: LanguageChoice) {
        check(preferences.edit().putString("choice", choice.name).commit()) { "Could not persist language choice" }
    }
}
