package com.sentri.nativeapp.i18n

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class LanguageChoice { SYSTEM, CHINESE, ENGLISH }
enum class SupportedLanguage(val tag: String) {
    CHINESE("zh-CN"), ENGLISH("en");
    val locale: Locale get() = Locale.forLanguageTag(tag)
    companion object {
        fun resolve(choice: LanguageChoice, system: Locale): SupportedLanguage = when (choice) {
            LanguageChoice.CHINESE -> CHINESE
            LanguageChoice.ENGLISH -> ENGLISH
            LanguageChoice.SYSTEM -> if (system.language == "zh") CHINESE else ENGLISH
        }
    }
}
interface LanguageStore {
    fun read(): LanguageChoice
    fun write(choice: LanguageChoice)
}
data class LanguageState(val choice: LanguageChoice, val language: SupportedLanguage)
/** Settings API only. UI wiring waits for the product component specification. */
class AppLanguage(private val store: LanguageStore, private val systemLocale: () -> Locale = Locale::getDefault) {
    private val mutable = MutableStateFlow(resolve(store.read()))
    val state = mutable.asStateFlow()
    private fun resolve(choice: LanguageChoice) = LanguageState(choice, SupportedLanguage.resolve(choice, systemLocale()))
    @Synchronized fun current(): SupportedLanguage {
        mutable.value = resolve(mutable.value.choice)
        return mutable.value.language
    }
    @Synchronized fun select(choice: LanguageChoice) {
        store.write(choice) // Publish only after durable success.
        mutable.value = resolve(choice)
    }
}
