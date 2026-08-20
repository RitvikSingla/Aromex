package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.model.ResolvedCompany
import com.humblesolutions.aromex.repository.CompanyDirectoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Straight port of Android's HttpCompanyDirectoryRepository.
 * POSTs the email to the gateway's /resolve-company and parses the resulting
 * FirebaseClientConfig — both the Android `appId` and (per ticket #16) the
 * optional `iosAppId`. Desktop ignores both app IDs — it uses `apiKey` +
 * `projectId` for Firebase Auth REST — but the DTO must decode successfully
 * regardless of which combination the gateway returns.
 */
class HttpCompanyDirectoryRepository(
    baseUrl: String = GATEWAY_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : CompanyDirectoryRepository {

    private val resolveUrl = "$baseUrl/resolve-company"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun resolveCompanies(email: String): List<ResolvedCompany> =
        withContext(Dispatchers.IO) {
            val body = json.encodeToString(
                ResolveRequest.serializer(),
                ResolveRequest(email = email),
            ).toRequestBody(JSON_MEDIA)
            val request = Request.Builder()
                .url(resolveUrl)
                .post(body)
                .build()

            NetLog.d(TAG, "POST $resolveUrl  body={email=$email}")
            val response = try {
                client.newCall(request).execute()
            } catch (io: IOException) {
                NetLog.w(TAG, "POST $resolveUrl  → network error: ${io.message}")
                throw LoginException(LoginError.NetworkUnavailable)
            }

            response.use { resp ->
                if (!resp.isSuccessful) {
                    NetLog.w(TAG, "POST $resolveUrl  → HTTP ${resp.code} (not 2xx)")
                    throw LoginException(LoginError.GatewayUnreachable)
                }
                val text = resp.body?.string().orEmpty()
                val parsed = try {
                    json.decodeFromString(ResolveResponse.serializer(), text)
                } catch (t: Throwable) {
                    NetLog.w(TAG, "POST $resolveUrl  → HTTP ${resp.code} but malformed body", t)
                    throw LoginException(LoginError.GatewayUnreachable)
                }
                NetLog.d(TAG, "POST $resolveUrl  → HTTP ${resp.code}, ${parsed.companies.size} candidate(s)")
                parsed.companies.map { it.toDomain() }
            }
        }

    @Serializable
    private data class ResolveRequest(val email: String)

    @Serializable
    private data class ResolveResponse(val companies: List<ResolvedCompanyDto> = emptyList())

    @Serializable
    private data class ResolvedCompanyDto(
        val companyId: String,
        val firebaseConfig: FirebaseConfigDto,
    ) {
        fun toDomain(): ResolvedCompany = ResolvedCompany(
            companyId = companyId,
            firebaseConfig = firebaseConfig.toDomain(),
        )
    }

    @Serializable
    private data class FirebaseConfigDto(
        val apiKey: String,
        @SerialName("appId") val applicationId: String,
        val projectId: String,
        val authDomain: String? = null,
        val storageBucket: String? = null,
        val messagingSenderId: String? = null,
        val iosAppId: String? = null,
    ) {
        fun toDomain(): FirebaseClientConfig = FirebaseClientConfig(
            apiKey = apiKey,
            applicationId = applicationId,
            projectId = projectId,
            authDomain = authDomain,
            storageBucket = storageBucket,
            messagingSenderId = messagingSenderId,
            iosApplicationId = iosAppId,
        )
    }

    private companion object {
        const val TAG = "Aromex/Gateway"
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
