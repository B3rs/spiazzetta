package me.bers.spiazzetta.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import me.bers.spiazzetta.domain.DashboardLayout
import me.bers.spiazzetta.domain.DashboardSection
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "spiazzetta")

/** Credentials and preferences that survive restarts. The password never leaves the device. */
class SettingsStore(private val context: Context) {

    data class Credentials(val email: String, val password: String)

    val email: Flow<String> = context.dataStore.data.map { it[KEY_EMAIL].orEmpty() }
    val password: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD].orEmpty() }
    val lastStoveId: Flow<String?> = context.dataStore.data.map { it[KEY_LAST_STOVE] }

    val disclaimerAcceptedVersion: Flow<Int> =
        context.dataStore.data.map { it[KEY_DISCLAIMER_VERSION] ?: 0 }

    val disclaimerAcceptedAt: Flow<Long?> =
        context.dataStore.data.map { it[KEY_DISCLAIMER_ACCEPTED_AT] }

    suspend fun acceptDisclaimer(version: Int, acceptedAtMillis: Long) {
        context.dataStore.edit {
            it[KEY_DISCLAIMER_VERSION] = version
            it[KEY_DISCLAIMER_ACCEPTED_AT] = acceptedAtMillis
        }
    }

    suspend fun savedCredentials(): Credentials? {
        val prefs = context.dataStore.data.first()
        val email = prefs[KEY_EMAIL].orEmpty()
        val password = prefs[KEY_PASSWORD].orEmpty()
        return if (email.isNotBlank() && password.isNotBlank()) Credentials(email, password) else null
    }

    suspend fun saveCredentials(email: String, password: String) {
        context.dataStore.edit {
            it[KEY_EMAIL] = email
            it[KEY_PASSWORD] = password
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit {
            it.remove(KEY_EMAIL)
            it.remove(KEY_PASSWORD)
            it.remove(KEY_LAST_STOVE)
        }
    }

    suspend fun loadDashboardLayout(): DashboardLayout {
        val prefs = context.dataStore.data.first()
        val order = prefs[KEY_SECTION_ORDER]
            ?.split(",")
            ?.mapNotNull { name -> DashboardSection.entries.firstOrNull { it.name == name } }
            ?.takeIf { it.isNotEmpty() }
            ?: DashboardSection.DEFAULT_ORDER
        val hidden = prefs[KEY_SECTION_HIDDEN]
            ?.split(",")
            ?.mapNotNull { name -> DashboardSection.entries.firstOrNull { it.name == name } }
            ?.toSet()
            .orEmpty()
        return DashboardLayout(order = order, hidden = hidden)
    }

    suspend fun saveDashboardLayout(layout: DashboardLayout) {
        context.dataStore.edit {
            it[KEY_SECTION_ORDER] = layout.order.joinToString(",") { section -> section.name }
            it[KEY_SECTION_HIDDEN] = layout.hidden.joinToString(",") { section -> section.name }
        }
    }

    suspend fun saveLastStove(stoveId: String) {
        context.dataStore.edit { it[KEY_LAST_STOVE] = stoveId }
    }

    private companion object {
        val KEY_EMAIL = stringPreferencesKey("email")
        val KEY_PASSWORD = stringPreferencesKey("password")
        val KEY_LAST_STOVE = stringPreferencesKey("last_stove")
        val KEY_SECTION_ORDER = stringPreferencesKey("section_order")
        val KEY_SECTION_HIDDEN = stringPreferencesKey("section_hidden")
        val KEY_DISCLAIMER_VERSION = intPreferencesKey("disclaimer_version")
        val KEY_DISCLAIMER_ACCEPTED_AT = longPreferencesKey("disclaimer_accepted_at")
    }
}
