package cn.keevol.keenotes.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    
    companion object {
        private val KEY_ENDPOINT = stringPreferencesKey("endpoint_url")
        private val KEY_TOKEN = stringPreferencesKey("token")
        private val KEY_PASSWORD = stringPreferencesKey("encryption_password")
        private val KEY_COPY_TO_CLIPBOARD = stringPreferencesKey("copy_to_clipboard_on_post")
        private val KEY_SHOW_OVERVIEW_CARD = stringPreferencesKey("show_overview_card")
        private val KEY_FIRST_NOTE_DATE = stringPreferencesKey("first_note_date")
        private val KEY_AUTO_FOCUS_INPUT = stringPreferencesKey("auto_focus_input_on_launch")
    }
    
    val endpointUrl: Flow<String> = context.dataStore.data.map { it[KEY_ENDPOINT] ?: "https://kns.afoo.me" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val encryptionPassword: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val copyToClipboardOnPost: Flow<Boolean> = context.dataStore.data.map { it[KEY_COPY_TO_CLIPBOARD]?.toBoolean() ?: false }
    val showOverviewCard: Flow<Boolean> = context.dataStore.data.map { it[KEY_SHOW_OVERVIEW_CARD]?.toBoolean() ?: true }
    val firstNoteDate: Flow<String?> = context.dataStore.data.map { it[KEY_FIRST_NOTE_DATE] }
    val autoFocusInputOnLaunch: Flow<Boolean> = context.dataStore.data.map { it[KEY_AUTO_FOCUS_INPUT]?.toBoolean() ?: true }
    
    val isConfigured: Flow<Boolean> = context.dataStore.data.map { prefs ->
        !prefs[KEY_ENDPOINT].isNullOrBlank() && !prefs[KEY_TOKEN].isNullOrBlank()
    }
    
    suspend fun getEndpointUrl(): String = endpointUrl.first()
    suspend fun getToken(): String = token.first()
    suspend fun getEncryptionPassword(): String = encryptionPassword.first()
    suspend fun isConfigured(): Boolean = isConfigured.first()
    suspend fun getCopyToClipboardOnPost(): Boolean = copyToClipboardOnPost.first()
    
    suspend fun saveSettings(endpoint: String, token: String, password: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ENDPOINT] = endpoint
            prefs[KEY_TOKEN] = token
            prefs[KEY_PASSWORD] = password
        }
    }
    
    suspend fun setCopyToClipboardOnPost(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_COPY_TO_CLIPBOARD] = enabled.toString()
        }
    }
    
    suspend fun setShowOverviewCard(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SHOW_OVERVIEW_CARD] = enabled.toString()
        }
    }
    
    suspend fun setFirstNoteDate(date: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_FIRST_NOTE_DATE] = date
        }
    }
    
    suspend fun setAutoFocusInputOnLaunch(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_FOCUS_INPUT] = enabled.toString()
        }
    }
    
    suspend fun getFirstNoteDate(): String? = firstNoteDate.first()
    
    suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}
