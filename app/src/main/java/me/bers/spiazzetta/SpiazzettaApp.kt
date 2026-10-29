package me.bers.spiazzetta

import android.app.Application
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.bers.spiazzetta.data.AguaRepository
import me.bers.spiazzetta.data.SettingsStore
import me.bers.spiazzetta.data.agua.HttpAguaRepository
import java.util.Locale

class SpiazzettaApp : Application() {

    private val preferences by lazy { getSharedPreferences("spiazzetta", MODE_PRIVATE) }

    val settings: SettingsStore by lazy { SettingsStore(this) }

    /**
     * Language must be readable synchronously in [android.app.Activity.attachBaseContext], which runs
     * before any coroutine can finish, so the choice lives in plain SharedPreferences.
     */
    private val _language = MutableStateFlow(LANGUAGE_ITALIAN)
    val language: StateFlow<String> = _language.asStateFlow()

    val repository: AguaRepository by lazy {
        HttpAguaRepository(
            baseUrl = BuildConfig.AGUA_BASE_URL,
            languageTag = { _language.value },
            readOnly = BuildConfig.READ_ONLY,
        )
    }

    override fun onCreate() {
        super.onCreate()
        _language.value = preferences.getString(KEY_LANGUAGE, null) ?: defaultLanguage()
    }

    /** Switches the whole app language; the choice survives restarts. */
    fun setLanguage(tag: String) {
        preferences.edit().putString(KEY_LANGUAGE, tag).apply()
        _language.value = tag
    }

    private fun defaultLanguage(): String =
        if (Locale.getDefault().language == LANGUAGE_ENGLISH) LANGUAGE_ENGLISH else LANGUAGE_ITALIAN

    companion object {
        const val LANGUAGE_ITALIAN = "it"
        const val LANGUAGE_ENGLISH = "en"
        private const val KEY_LANGUAGE = "language"
    }
}

/** Returns a context whose resources resolve in [tag], so strings and formats follow the choice. */
fun Context.withLanguage(tag: String): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(tag))
    return createConfigurationContext(configuration)
}
