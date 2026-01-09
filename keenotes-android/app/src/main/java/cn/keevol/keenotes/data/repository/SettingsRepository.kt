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
    }
    
    val endpointUrl: Flow<String> = context.dataStore.data.map { it[KEY_ENDPOINT] ?: "" }
    val token: Flow<String> = context.dataStore.data.map { it[KEY_TOKEN] ?: "" }
    val encryptionPassword: Flow<String> = context.dataStore.data.map { it[KEY_PASSWORD] ?: "" }
    val copyToClipboardOnPost: Flow<Boolean> = context.dataStore.data.map { it[KEY_COPY_TO_CLIPBOARD]?.toBoolean() ?: false }
    
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
    
    suspend fun clearSettings() {
        context.dataStore.edit { it.clear() }
    }
}
