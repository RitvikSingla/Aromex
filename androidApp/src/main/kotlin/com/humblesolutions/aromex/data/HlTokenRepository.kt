package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.repository.AuthRepository
import com.humblesolutions.aromex.repository.HlTokenProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Brokers a short-lived Humble Ledger access token via the gateway's /hl-token
 * endpoint. Caches the token in memory for its full ~15-min life and re-brokers
 * lazily — either when the cached token is within 30s of expiry, or when the
 * caller (HlLedgerRepository) explicitly [invalidate]s it after a 401 from HL.
 *
 * HL credentials never live on the device — only the brokered token does, and
 * only in memory (cleared on process death).
 */
class HlTokenRepository(
    private val authRepo: AuthRepository,
    private val activeConfig: FirebaseClientConfig,
    baseUrl: String = GATEWAY_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
    private val now: () -> Long = { System.currentTimeMillis() },
) : HlTokenProvider {

    private val tokenUrl = "$baseUrl/hl-token"
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    override suspend fun currentToken(): String = mutex.withLock {
        val token = cachedToken
        if (token != null && now() < expiresAtMs - SAFETY_MARGIN_MS) {
            val remainingMs = expiresAtMs - now()
            NetLog.d(TAG, "cache HIT  hlToken=${NetLog.redact(token)}  expires in ${remainingMs / 1000}s")
            return@withLock token
        }
        NetLog.d(TAG, "cache MISS  → brokering fresh /hl-token")
        brokerLocked(forceRefresh = false)
    }

    override suspend fun invalidate() {
        mutex.withLock {
            NetLog.d(TAG, "invalidate()  → cleared cached hlToken")
            cachedToken = null
            expiresAtMs = 0L
        }
    }

    private suspend fun brokerLocked(forceRefresh: Boolean): String {
        val idToken = try {
            authRepo.idToken(activeConfig, forceRefresh = forceRefresh)
        } catch (le: LoginException) {
            NetLog.w(TAG, "Firebase idToken() failed: ${le.error}")
            throw HlException(HlError.Unexpected("id token: ${le.error}"))
        }
        NetLog.d(TAG, "POST $tokenUrl  Authorization=Bearer ${NetLog.redact(idToken)}")
        val response = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(tokenUrl)
                .post(EMPTY_BODY)
                .header("Authorization", "Bearer $idToken")
                .build()
            try {
                client.newCall(request).execute()
            } catch (io: IOException) {
                NetLog.w(TAG, "POST $tokenUrl  → network error: ${io.message}")
                throw HlException(HlError.NetworkUnavailable)
            }
        }
        response.use { resp ->
            if (resp.code == 401 || resp.code == 403) {
                NetLog.w(TAG, "POST $tokenUrl  → HTTP ${resp.code} (Firebase ID token rejected)")
                throw HlException(HlError.TokenRejected)
            }
            if (!resp.isSuccessful) {
                NetLog.w(TAG, "POST $tokenUrl  → HTTP ${resp.code} (not 2xx)")
                throw HlException(HlError.GatewayUnreachable)
            }
            val text = resp.body?.string().orEmpty()
            val parsed = try {
                json.decodeFromString(HlTokenResponse.serializer(), text)
            } catch (t: Throwable) {
                NetLog.w(TAG, "POST $tokenUrl  → HTTP ${resp.code} but malformed body: $text", t)
                throw HlException(HlError.Unexpected("gateway returned malformed /hl-token body"))
            }
            cachedToken = parsed.hlToken
            expiresAtMs = now() + parsed.expiresIn * 1000L
            val redactedBody = text.replace(parsed.hlToken, NetLog.redact(parsed.hlToken))
            NetLog.d(
                TAG,
                "POST $tokenUrl  → HTTP ${resp.code}, expiresIn=${parsed.expiresIn}s",
            )
            NetLog.d(TAG, "  raw body (token redacted): $redactedBody")
            return parsed.hlToken
        }
    }

    @Serializable
    private data class HlTokenResponse(
        val hlToken: String,
        val expiresIn: Long,
    )

    private companion object {
        const val TAG = "Aromex/HlToken"
        const val SAFETY_MARGIN_MS = 30_000L

        // No Content-Type — sending application/json with an empty body makes
        // Fastify reply 400 (FST_ERR_CTP_EMPTY_JSON_BODY). The /hl-token route
        // only reads the Authorization header; body is unused. Matches the
        // contract verified by aromex-gateway/scripts/live-e2e.ts.
        val EMPTY_BODY = "".toRequestBody(null)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
