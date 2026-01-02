package cn.keevol.keenotes.network

import cn.keevol.keenotes.crypto.CryptoService
import cn.keevol.keenotes.data.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * API service for posting notes
 */
class ApiService(
    private val settingsRepository: SettingsRepository,
    private val cryptoService: CryptoService
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    data class PostResult(
        val success: Boolean,
        val message: String,
        val noteId: Long? = null,
        val echoContent: String? = null
    )
    
    suspend fun postNote(content: String): PostResult = withContext(Dispatchers.IO) {
        try {
            val endpoint = settingsRepository.getEndpointUrl()
            val token = settingsRepository.getToken()
            
            if (endpoint.isBlank() || token.isBlank()) {
                return@withContext PostResult(false, "Please configure server settings first")
            }
            
            // Encrypt if enabled
            val (finalContent, isEncrypted) = if (cryptoService.isEncryptionEnabled()) {
                cryptoService.encrypt(content) to true
            } else {
                content to false
            }
            
            // Build JSON body
            val json = JSONObject().apply {
                put("content", finalContent)
                put("encrypted", isEncrypted)
            }
            
            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val responseJson = JSONObject(responseBody)
                val noteId = responseJson.optLong("id", -1).takeIf { it > 0 }
                PostResult(
                    success = true,
                    message = "Note saved successfully",
                    noteId = noteId,
                    echoContent = content
                )
            } else {
                PostResult(
                    success = false,
                    message = "Server error: ${response.code} ${response.message}"
                )
            }
        } catch (e: Exception) {
            PostResult(
                success = false,
                message = "Network error: ${e.message}"
            )
        }
    }
}
