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
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * API service for posting notes
 */
class ApiService(
    private val settingsRepository: SettingsRepository,
    private val cryptoService: CryptoService
) {
    companion object {
        private val TS_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
    
    private val client: OkHttpClient = createClient()
    
    private fun createClient(): OkHttpClient {
        return try {
            // Trust all certificates (for development)
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, trustAll, SecureRandom())
            
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }
    
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
            
            if (!cryptoService.isEncryptionEnabled()) {
                return@withContext PostResult(false, "PIN code not set")
            }
            
            // Encrypt content
            val encrypted = cryptoService.encrypt(content)
            val ts = LocalDateTime.now().format(TS_FORMATTER)
            
            // Build JSON body - match JavaFX format exactly
            val json = JSONObject().apply {
                put("channel", "mobile-android")
                put("text", encrypted)
                put("ts", ts)
                put("encrypted", true)
            }
            
            val requestBody = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()
            
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            
            if (response.isSuccessful && responseBody != null) {
                val noteId = parseNoteId(responseBody)
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
    
    private fun parseNoteId(body: String): Long? {
        return try {
            val json = JSONObject(body)
            json.optLong("id", -1).takeIf { it > 0 }
        } catch (e: Exception) {
            null
        }
    }
}
