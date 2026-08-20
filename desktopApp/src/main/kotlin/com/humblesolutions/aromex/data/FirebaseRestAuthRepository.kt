package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Firebase Auth implemented over the Firebase Auth REST APIs, because the JVM
 * has no Firebase client SDK. Backs the shared [AuthRepository] interface.
 *
 * Two Google endpoints do the work:
 *   - identitytoolkit.googleapis.com/v1/accounts:signInWithPassword — sign-in
 *   - securetoken.googleapis.com/v1/token — refresh an ID token from the
 *     refresh token (Firebase ID tokens expire ~1h)
 *
 * SESSION MANAGEMENT ON DESKTOP
 * On Android/iOS the Firebase SDK holds the session invisibly. On Desktop
 * WE hold it. After sign-in we get back { idToken, refreshToken, localId,
 * expiresIn }. We cache all of that in memory (per Firebase project) inside
 * [SessionStore], and we persist the refresh token to disk via
 * [DesktopPreferencesRepository]. On next launch, [currentUid] reads the
 * persisted refresh token, calls securetoken to mint a fresh ID token, and
 * returns the uid — that's how RestoreSessionUseCase works on Desktop.
 *
 * TOKENS ARE NEVER LOGGED IN FULL.
 */
class FirebaseRestAuthRepository(
    private val prefs: DesktopPreferencesRepository,
    private val client: OkHttpClient = defaultClient(),
    private val identityToolkitBase: String = IDENTITY_TOOLKIT_BASE,
    private val secureTokenBase: String = SECURETOKEN_BASE,
) : AuthRepository {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = SessionStore()

    override suspend fun signIn(
        config: FirebaseClientConfig,
        email: String,
        password: String,
    ): String = withContext(Dispatchers.IO) {
        val url = "$identityToolkitBase/accounts:signInWithPassword?key=${config.apiKey}"
        val body = json.encodeToString(
            SignInRequest.serializer(),
            SignInRequest(email = email, password = password, returnSecureToken = true),
        ).toRequestBody(JSON_MEDIA)

        NetLog.d(TAG, "POST identitytoolkit:signInWithPassword project=${config.projectId} email=$email")
        val response = try {
            client.newCall(Request.Builder().url(url).post(body).build()).execute()
        } catch (io: IOException) {
            NetLog.w(TAG, "signIn network error: ${io.message}")
            throw LoginException(LoginError.NetworkUnavailable)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapAuthError(resp.code, text)
            val parsed = try {
                json.decodeFromString(SignInResponse.serializer(), text)
            } catch (t: Throwable) {
                NetLog.w(TAG, "signIn: malformed body", t)
                throw LoginException(LoginError.FirebaseFailure("malformed sign-in response"))
            }
            val expiresAtMs = System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
            store.set(
                projectId = config.projectId,
                session = Session(
                    idToken = parsed.idToken,
                    refreshToken = parsed.refreshToken,
                    expiresAtMs = expiresAtMs,
                    uid = parsed.localId,
                ),
            )
            prefs.setFirebaseRefreshToken(config.projectId, parsed.refreshToken)
            NetLog.d(
                TAG,
                "signIn OK uid=${parsed.localId} idToken=${NetLog.redact(parsed.idToken)} " +
                    "refresh=${NetLog.redact(parsed.refreshToken)} expiresIn=${parsed.expiresIn}s",
            )
            parsed.localId
        }
    }

    override suspend fun signOut(config: FirebaseClientConfig) {
        // Firebase Auth REST has no per-refresh-token revocation. Clearing local
        // state is enough: without the refresh token we can't mint new ID tokens.
        store.clear(config.projectId)
        prefs.setFirebaseRefreshToken(config.projectId, null)
        NetLog.d(TAG, "signOut cleared local session for project=${config.projectId}")
    }

    override suspend fun currentUid(config: FirebaseClientConfig): String? {
        store.get(config.projectId)?.let { return it.uid }
        // No in-memory session — try to restore from the persisted refresh token.
        val persisted = prefs.getFirebaseRefreshToken(config.projectId) ?: return null
        return try {
            val session = refreshWith(config, persisted)
            session.uid
        } catch (t: Throwable) {
            // Refresh token is bad / revoked → clear it so subsequent launches
            // don't loop.
            NetLog.w(TAG, "currentUid: refresh failed → clearing stored refresh token", t)
            prefs.setFirebaseRefreshToken(config.projectId, null)
            null
        }
    }

    override suspend fun idToken(config: FirebaseClientConfig, forceRefresh: Boolean): String {
        val cached = store.get(config.projectId)
        if (cached != null && !forceRefresh &&
            System.currentTimeMillis() < cached.expiresAtMs - REFRESH_MARGIN_MS
        ) {
            return cached.idToken
        }
        val refreshToken = cached?.refreshToken
            ?: prefs.getFirebaseRefreshToken(config.projectId)
            ?: throw LoginException(LoginError.Unexpected("no signed-in user"))
        return refreshWith(config, refreshToken).idToken
    }

    /**
     * Exchange a refresh token for a fresh ID token via securetoken. Updates
     * the in-memory store and (if the refresh token rotated) the persisted
     * copy on disk. Returns the fresh [Session].
     */
    private suspend fun refreshWith(config: FirebaseClientConfig, refreshToken: String): Session =
        withContext(Dispatchers.IO) {
            store.refreshMutex(config.projectId).withLock {
                // Double-check inside the lock in case another caller already refreshed.
                store.get(config.projectId)?.let { existing ->
                    if (System.currentTimeMillis() < existing.expiresAtMs - REFRESH_MARGIN_MS) {
                        return@withLock existing
                    }
                }

                val url = "$secureTokenBase/token?key=${config.apiKey}"
                val body = FormBody.Builder()
                    .add("grant_type", "refresh_token")
                    .add("refresh_token", refreshToken)
                    .build()
                NetLog.d(
                    TAG,
                    "POST securetoken:token project=${config.projectId} refresh=${NetLog.redact(refreshToken)}",
                )
                val response = try {
                    client.newCall(Request.Builder().url(url).post(body).build()).execute()
                } catch (io: IOException) {
                    NetLog.w(TAG, "refresh network error: ${io.message}")
                    throw LoginException(LoginError.NetworkUnavailable)
                }
                response.use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw mapRefreshError(resp.code, text)
                    val parsed = try {
                        json.decodeFromString(RefreshResponse.serializer(), text)
                    } catch (t: Throwable) {
                        NetLog.w(TAG, "refresh: malformed body", t)
                        throw LoginException(LoginError.FirebaseFailure("malformed refresh response"))
                    }
                    val expiresAtMs =
                        System.currentTimeMillis() + (parsed.expiresIn.toLongOrNull() ?: 3600L) * 1000
                    val session = Session(
                        idToken = parsed.idToken,
                        refreshToken = parsed.refreshToken,
                        expiresAtMs = expiresAtMs,
                        uid = parsed.userId,
                    )
                    store.set(config.projectId, session)
                    if (parsed.refreshToken != refreshToken) {
                        prefs.setFirebaseRefreshToken(config.projectId, parsed.refreshToken)
                    }
                    NetLog.d(
                        TAG,
                        "refresh OK idToken=${NetLog.redact(parsed.idToken)} expiresIn=${parsed.expiresIn}s",
                    )
                    session
                }
            }
        }

    private fun mapAuthError(status: Int, body: String): LoginException {
        val message = errorMessageFromBody(body)
        val err = when {
            message == "EMAIL_NOT_FOUND" -> LoginError.WrongPassword
            message == "INVALID_PASSWORD" -> LoginError.WrongPassword
            message == "INVALID_LOGIN_CREDENTIALS" -> LoginError.WrongPassword
            message == "USER_DISABLED" -> LoginError.AccountDisabled
            message.startsWith("MISSING_") -> LoginError.WrongPassword
            status in 500..599 -> LoginError.FirebaseFailure("Firebase Auth returned $status")
            else -> LoginError.FirebaseFailure(message.ifEmpty { "Firebase Auth returned $status" })
        }
        return LoginException(err)
    }

    private fun mapRefreshError(status: Int, body: String): LoginException {
        val message = errorMessageFromBody(body)
        val err = when {
            message == "TOKEN_EXPIRED" ||
                message == "USER_DISABLED" ||
                message == "USER_NOT_FOUND" ||
                message == "INVALID_REFRESH_TOKEN" -> LoginError.AccountDisabled
            status in 500..599 -> LoginError.FirebaseFailure("securetoken returned $status")
            else -> LoginError.FirebaseFailure(message.ifEmpty { "securetoken returned $status" })
        }
        return LoginException(err)
    }

    private fun errorMessageFromBody(body: String): String = try {
        json.decodeFromString(ErrorEnvelope.serializer(), body).error.message
    } catch (_: Throwable) {
        ""
    }

    // -----------------------------------------------------------------
    // In-memory session store (per Firebase project)
    // -----------------------------------------------------------------

    private data class Session(
        val idToken: String,
        val refreshToken: String,
        val expiresAtMs: Long,
        val uid: String,
    )

    private class SessionStore {
        private val lock = Mutex()
        private val sessions = mutableMapOf<String, Session>()
        private val refreshMutexes = mutableMapOf<String, Mutex>()

        suspend fun get(projectId: String): Session? = lock.withLock { sessions[projectId] }

        suspend fun set(projectId: String, session: Session) {
            lock.withLock { sessions[projectId] = session }
        }

        suspend fun clear(projectId: String) {
            lock.withLock { sessions.remove(projectId) }
        }

        /**
         * A per-project mutex so concurrent [idToken]/[currentUid] callers
         * don't independently hit securetoken. Kept off the top-level lock
         * so callers can await it without blocking the store.
         */
        suspend fun refreshMutex(projectId: String): Mutex = lock.withLock {
            refreshMutexes.getOrPut(projectId) { Mutex() }
        }
    }

    // -----------------------------------------------------------------
    // Wire DTOs
    // -----------------------------------------------------------------

    @Serializable
    private data class SignInRequest(
        val email: String,
        val password: String,
        val returnSecureToken: Boolean,
    )

    @Serializable
    private data class SignInResponse(
        val idToken: String,
        val refreshToken: String,
        val expiresIn: String,
        val localId: String,
    )

    @Serializable
    private data class RefreshResponse(
        val id_token: String,
        val refresh_token: String,
        val expires_in: String,
        val user_id: String,
    ) {
        val idToken get() = id_token
        val refreshToken get() = refresh_token
        val expiresIn get() = expires_in
        val userId get() = user_id
    }

    @Serializable
    private data class ErrorEnvelope(val error: ErrorBody)

    @Serializable
    private data class ErrorBody(val message: String = "", val code: Int = 0)

    private companion object {
        const val TAG = "Aromex/AuthRest"
        const val REFRESH_MARGIN_MS = 30_000L
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
