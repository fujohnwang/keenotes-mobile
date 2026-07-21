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
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PreparedNote(
    val content: String,
    val encryptedContent: String,
    val channel: String,
    val createdAt: String,
    val requestId: String
)

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
        val echoContent: String? = null,
        val networkError: Boolean = false
    )

    suspend fun prepareNote(content: String, channel: String = "mobile-android"): PreparedNote = withContext(Dispatchers.IO) {
        val endpoint = settingsRepository.getEndpointUrl()
        val token = settingsRepository.getToken()

        if (endpoint.isBlank() || token.isBlank()) {
            throw IllegalStateException("Please configure server settings first")
        }

        if (!cryptoService.isEncryptionEnabled()) {
            throw IllegalStateException("PIN code not set")
        }

        val encrypted = cryptoService.encrypt(content)
        val ts = LocalDateTime.now()
            .atZone(ZoneId.systemDefault())
            .toOffsetDateTime()
            .withOffsetSameInstant(ZoneOffset.UTC)
            .format(TS_FORMATTER)

        PreparedNote(
            content = content,
            encryptedContent = encrypted,
            channel = channel,
            createdAt = ts,
            requestId = UUID.randomUUID().toString()
        )
    }

    suspend fun postNote(content: String): PostResult {
        return try {
            postPreparedNote(prepareNote(content))
        } catch (e: Exception) {
            PostResult(
                success = false,
                message = e.message ?: "Failed to prepare note"
            )
        }
    }

    suspend fun postPreparedNote(note: PreparedNote): PostResult = withContext(Dispatchers.IO) {
        try {
            val endpoint = settingsRepository.getEndpointUrl()
            val token = settingsRepository.getToken()

            if (endpoint.isBlank() || token.isBlank()) {
                return@withContext PostResult(false, "Please configure server settings first")
            }

            // Build JSON body - match JavaFX format exactly
            val json = JSONObject().apply {
                put("channel", note.channel)
                put("text", note.encryptedContent)
                put("ts", note.createdAt)
                put("encrypted", true)
                put("request_id", note.requestId)
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
                    echoContent = note.content
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
                message = "Network error: ${e.message}",
                networkError = true
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
