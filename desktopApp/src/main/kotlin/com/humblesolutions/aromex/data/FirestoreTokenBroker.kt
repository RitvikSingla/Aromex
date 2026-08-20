package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.repository.AuthRepository
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
 * Desktop-internal broker: Firebase ID token → gateway POST /firestore-token
 * → short-lived, datastore-scoped Google OAuth access token.
 *
 * Not part of the shared repository set — it's an implementation detail of
 * how [FirestoreUserRepository] talks to Firestore on Desktop. Mirrors the
 * shape of Android's HlTokenRepository (cache + Mutex + 30 s refresh margin
 * + no-body / no-Content-Type POST).
 *
 * The gateway contract (verified by aromex-gateway ticket #15):
 *   POST /firestore-token
 *   Authorization: Bearer <firebase-id-token>
 *   (no body, no Content-Type — Fastify rejects application/json with an
 *    empty body via FST_ERR_CTP_EMPTY_JSON_BODY, same gotcha as /hl-token)
 *   → 200 { firestoreToken, projectId, expiresIn }
 */
class FirestoreTokenBroker(
    private val authRepo: AuthRepository,
    private val activeConfig: FirebaseClientConfig,
    baseUrl: String = GATEWAY_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) {
    private val tokenUrl = "$baseUrl/firestore-token"
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile private var cachedToken: String? = null
    @Volatile private var expiresAtMs: Long = 0L

    /** Returns a non-expired Firestore token, brokering a fresh one if needed. */
    suspend fun currentToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < expiresAtMs - REFRESH_MARGIN_MS) {
                return token
            }
        }
        return brokerLocked()
    }

    /**
     * The current token **with the expiry the gateway actually gave it**.
     *
     * Callers building an Admin-SDK credential must use this rather than inventing a lifetime. The
     * gateway's TTL varies with the underlying Google token's remaining life — observed anywhere
     * from ~24 to ~60 minutes — so a hardcoded guess is wrong roughly whenever it matters: stamp a
     * credential as good for 50 minutes when it dies after 24 and the SDK spends the difference
     * sending a token the server already rejects, never once calling the refresh handler.
     *
     * The stamped expiry is pulled [SDK_REFRESH_MARGIN_MS] early so the SDK asks for a new token
     * slightly before the old one dies, rather than racing it.
     */
    suspend fun currentTokenWithExpiry(): BrokeredToken {
        val token = currentToken()
        return BrokeredToken(token, expiresAtMs - SDK_REFRESH_MARGIN_MS)
    }

    /** Drop the cached token so the next [currentToken] call brokers a fresh one. */
    suspend fun invalidate() {
        mutex.withLock {
            cachedToken = null
            expiresAtMs = 0L
            NetLog.d(TAG, "invalidate() cleared cached firestore token")
        }
    }

    private suspend fun brokerLocked(): String = mutex.withLock {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < expiresAtMs - REFRESH_MARGIN_MS) {
                NetLog.d(TAG, "cache HIT after lock  firestoreToken=${NetLog.redact(token)}")
                return@withLock token
            }
        }
        NetLog.d(TAG, "cache MISS → brokering fresh /firestore-token")
        val idToken = authRepo.idToken(activeConfig, forceRefresh = false)
        val request = Request.Builder()
            .url(tokenUrl)
            .post(EMPTY_BODY)
            .header("Authorization", "Bearer $idToken")
            .build()
        NetLog.d(
            TAG,
            "POST $tokenUrl  Authorization=Bearer ${NetLog.redact(idToken)}",
        )
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
                throw HlException(HlError.Unexpected("Malformed /firestore-token response"))
            }
            cachedToken = parsed.firestoreToken
            expiresAtMs = System.currentTimeMillis() + parsed.expiresIn * 1000L
            NetLog.d(
                TAG,
                "POST $tokenUrl → HTTP ${resp.code} firestoreToken=" +
                    "${NetLog.redact(parsed.firestoreToken)} expiresIn=${parsed.expiresIn}s",
            )
            parsed.firestoreToken
        }
    }

    @Serializable
    private data class BrokerResponse(
        val firestoreToken: String,
        val projectId: String,
        val expiresIn: Long,
    )

    private companion object {
        const val TAG = "Aromex/FsToken"
        const val REFRESH_MARGIN_MS = 30_000L

        /** How early the Admin SDK is told a token expires, so it renews before the real deadline. */
        const val SDK_REFRESH_MARGIN_MS = 60_000L
        val EMPTY_BODY = "".toRequestBody(null)

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

/** A brokered Firestore token and when it genuinely stops working. */
data class BrokeredToken(val value: String, val expiresAtMs: Long)
