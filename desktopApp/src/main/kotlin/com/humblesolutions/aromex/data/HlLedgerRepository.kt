package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.AccountCategory
import com.humblesolutions.aromex.model.AccountType
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LedgerAccount
import com.humblesolutions.aromex.repository.HlTokenProvider
import com.humblesolutions.aromex.repository.LedgerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JVM port of Android's HlLedgerRepository. Reads a company's accounts +
 * balances from HL via /api/v1/reports/balance-sheet?date=2999-12-31 (the
 * only endpoint that bundles accounts + balances — /accounts has no
 * balances). Money kept as decimal String.
 */
class HlLedgerRepository(
    private val tokenProvider: HlTokenProvider,
    baseUrl: String = HL_API_BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : LedgerRepository {

    private val balanceSheetUrl = "$baseUrl/api/v1/reports/balance-sheet?date=2999-12-31"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun getAccounts(): List<LedgerAccount> {
        NetLog.d(TAG, "getAccounts() → GET $balanceSheetUrl (attempt 1)")
        val firstResponse = fetch(tokenProvider.currentToken())
        firstResponse.use { resp ->
            if (resp.code == 401) {
                NetLog.w(TAG, "attempt 1 → HTTP 401, invalidating HL token and retrying once")
                tokenProvider.invalidate()
            } else {
                return parse(resp, attempt = 1)
            }
        }
        NetLog.d(TAG, "getAccounts() → GET $balanceSheetUrl (attempt 2 with fresh token)")
        val retryResponse = fetch(tokenProvider.currentToken())
        retryResponse.use { resp ->
            if (resp.code == 401) {
                NetLog.w(TAG, "attempt 2 → HTTP 401, surfacing Unauthorized")
                throw HlException(HlError.Unauthorized)
            }
            return parse(resp, attempt = 2)
        }
    }

    private suspend fun fetch(hlToken: String): Response = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(balanceSheetUrl)
            .get()
            .header("Authorization", "Bearer $hlToken")
            .build()
        NetLog.d(TAG, "  Authorization=Bearer ${NetLog.redact(hlToken)}")
        try {
            client.newCall(request).execute()
        } catch (io: IOException) {
            NetLog.w(TAG, "GET $balanceSheetUrl → network error: ${io.message}")
            throw HlException(HlError.NetworkUnavailable)
        }
    }

    private fun parse(resp: Response, attempt: Int): List<LedgerAccount> {
        if (!resp.isSuccessful) {
            NetLog.w(TAG, "attempt $attempt → HTTP ${resp.code} (not 2xx)")
            throw HlException(HlError.HlUnreachable)
        }
        val text = resp.body?.string().orEmpty()
        val envelope = try {
            json.decodeFromString(BalanceSheetEnvelope.serializer(), text)
        } catch (t: Throwable) {
            NetLog.w(TAG, "attempt $attempt → HTTP ${resp.code} but malformed body", t)
            throw HlException(HlError.Unexpected("HL returned malformed balance-sheet body"))
        }
        if (!envelope.success || envelope.data == null) {
            NetLog.w(TAG, "attempt $attempt → HTTP ${resp.code}, envelope.success=false")
            throw HlException(HlError.Unexpected("HL balance-sheet success=false"))
        }
        val data = envelope.data
        val out = mutableListOf<LedgerAccount>()
        data.assets.forEach { out += it.toDomain(AccountCategory.ASSET) }
        data.liabilities.forEach { out += it.toDomain(AccountCategory.LIABILITY) }
        data.equity.forEach { out += it.toDomain(AccountCategory.EQUITY) }
        NetLog.d(
            TAG,
            "attempt $attempt → parsed: ${out.size} accounts " +
                "(${data.assets.size} assets, ${data.liabilities.size} liab, ${data.equity.size} equity)",
        )
        return out
    }

    @Serializable
    private data class BalanceSheetEnvelope(val success: Boolean = false, val data: BalanceSheetData? = null)

    @Serializable
    private data class BalanceSheetData(
        val assets: List<BalanceSheetRow> = emptyList(),
        val liabilities: List<BalanceSheetRow> = emptyList(),
        val equity: List<BalanceSheetRow> = emptyList(),
    )

    @Serializable
    private data class BalanceSheetRow(val accountId: String, val name: String, val balance: String) {
        fun toDomain(category: AccountCategory): LedgerAccount = LedgerAccount(
            id = accountId,
            name = name,
            category = category,
            type = AccountType.fromName(name),
            balance = balance,
        )
    }

    private companion object {
        const val TAG = "Aromex/HlLedger"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
