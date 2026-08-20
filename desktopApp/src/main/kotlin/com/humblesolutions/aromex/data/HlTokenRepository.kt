package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
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
 * JVM port of Android's HlTokenRepository. Brokers a short-lived HL access
 * token via the gateway's /hl-token endpoint; caches in memory; refreshes
 * within a 30 s margin or on explicit [invalidate] (called after HL returns
 * 401 to force a re-broker on the next call).
 *
 * Gateway contract: POST /hl-token with just `Authorization: Bearer
 * <firebase-id-token>` — no body, no Content-Type header. Fastify returns
 * 400 FST_ERR_CTP_EMPTY_JSON_BODY otherwise.
 */
class HlTokenRepository(
    private val authRepo: AuthRepository,
    private val activeConfig: FirebaseClientConfig,
    baseUrl: String = GATEWAY_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : HlTokenProvider {

    private val tokenUrl = "$baseUrl/hl-token"
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    override suspend fun currentToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < expiresAtMs - REFRESH_MARGIN_MS) {
                return token
            }
        }
        return brokerLocked()
    }

    override suspend fun invalidate() {
        mutex.withLock {
            cachedToken = null
            expiresAtMs = 0L
            NetLog.d(TAG, "invalidate() cleared cached hlToken")
        }
    }

    private suspend fun brokerLocked(): String = mutex.withLock {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < expiresAtMs - REFRESH_MARGIN_MS) {
                return@withLock token
            }
        }
        NetLog.d(TAG, "cache MISS → brokering fresh /hl-token")
        val idToken = authRepo.idToken(activeConfig, forceRefresh = false)
        val request = Request.Builder()
            .url(tokenUrl)
            .post(EMPTY_BODY)
            .header("Authorization", "Bearer $idToken")
            .build()
        NetLog.d(TAG, "POST $tokenUrl Authorization=Bearer ${NetLog.redact(idToken)}")
        val response = withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute()
            } catch (io: IOException) {
                NetLog.w(TAG, "POST $tokenUrl network error: ${io.message}")
                throw HlException(HlError.NetworkUnavailable)
            }
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 || resp.code == 403 -> {
                    NetLog.w(TAG, "POST $tokenUrl → HTTP ${resp.code} — token rejected")
                    throw HlException(HlError.TokenRejected)
                }
                !resp.isSuccessful -> {
                    NetLog.w(TAG, "POST $tokenUrl → HTTP ${resp.code} (not 2xx)")
                    throw HlException(HlError.GatewayUnreachable)
                }
            }
            val parsed = try {
                json.decodeFromString(BrokerResponse.serializer(), text)
            } catch (t: Throwable) {
                NetLog.w(TAG, "POST $tokenUrl malformed body", t)
                throw HlException(HlError.Unexpected("Malformed /hl-token response"))
            }
            cachedToken = parsed.hlToken
            expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000L
            NetLog.d(
                TAG,
                "POST $tokenUrl → HTTP ${resp.code} hlToken=${NetLog.redact(parsed.hlToken)} " +
                    "expiresIn=${parsed.expiresIn}s",
            )
            parsed.hlToken
        }
    }

    @Serializable
    private data class BrokerResponse(val hlToken: String, val expiresIn: Long)

    private companion object {
        const val TAG = "Aromex/HlToken"
        const val REFRESH_MARGIN_MS = 30_000L
        val EMPTY_BODY = "".toRequestBody(null)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
