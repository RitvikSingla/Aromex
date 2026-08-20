package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.AccountStatement
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.EntityBalance
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import com.humblesolutions.aromex.model.LedgerRow
import com.humblesolutions.aromex.repository.EntityLedgerRepository
import com.humblesolutions.aromex.repository.HlTokenProvider
import com.humblesolutions.aromex.util.Money
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one HL balance-read client shared by Android, iOS, and Desktop.
 *
 * Deliberate architecture note: `CLAUDE.md` says repository *implementations* are
 * per-platform. HL REST is identical everywhere, so — per ticket #26, agreed with
 * the manager — HL access is a **single shared Ktor client** instead. It stays free
 * of platform imports; each target ships exactly one Ktor engine on its classpath and
 * `HttpClient(...)` auto-selects it (no `expect`/`actual`).
 *
 * Semantics mirror the existing OkHttp read path (androidApp/HlLedgerRepository):
 * bearer token from [HlTokenProvider]; on a 401 → invalidate the token, refetch,
 * and retry the call exactly once before surfacing [HlError.Unauthorized].
 *
 * HL returns the party balance as a signed decimal string. The field is `outstanding`
 * today; HL is standardizing on `balance` — we read whichever is present.
 */
class KtorEntityLedgerRepository private constructor(
    private val tokenProvider: HlTokenProvider,
    baseUrl: String,
    private val client: HttpClient,
    /** True when we created [client] ourselves and are therefore responsible for closing it. */
    private val ownsClient: Boolean,
) : EntityLedgerRepository {

    /** Creates and owns its own [HttpClient]. Call [close] when done to release it. */
    constructor(tokenProvider: HlTokenProvider, baseUrl: String) :
        this(tokenProvider, baseUrl, defaultHttpClient(), ownsClient = true)

    /** Uses a caller-supplied [client] (e.g. a shared app-wide client, or a MockEngine in tests). [close] is then a no-op. */
    constructor(tokenProvider: HlTokenProvider, baseUrl: String, client: HttpClient) :
        this(tokenProvider, baseUrl, client, ownsClient = false)

    private val customersUrl: String = "${baseUrl.trimEnd('/')}/api/v1/customers"
    private val ledgerUrl: String = "${baseUrl.trimEnd('/')}/api/v1/ledger"
    private val transactionsUrl: String = "${baseUrl.trimEnd('/')}/api/v1/transactions"

    override suspend fun getBalances(): Map<String, EntityBalance> {
        val out = LinkedHashMap<String, EntityBalance>()
        // Bounded dataset (client-side cache). MAX_PAGES is a safety stop so a
        // misbehaving upstream that never sets hasMore=false can't loop forever.
        var page = 1
        while (page <= MAX_PAGES) {
            val env = parseList(
                getWithAuth {
                    url(customersUrl)
                    parameter("limit", PAGE_SIZE)
                    parameter("page", page)
                },
            )
            for (customer in env.data) {
                val externalId = customer.externalId ?: continue
                out[externalId] = customer.toBalance()
            }
            if (env.meta?.hasMore != true) break
            page++
        }
        return out
    }

    /** Releases the underlying [HttpClient] — but only if this repo created it. */
    override fun close() {
        if (ownsClient) client.close()
    }

    override suspend fun getBalance(externalId: String): EntityBalance? {
        val env = parseList(
            getWithAuth {
                url(customersUrl)
                parameter("externalId", externalId)
                parameter("limit", 1)
            },
        )
        return env.data.firstOrNull()?.toBalance()
    }

    /**
     * A page of the party's statement (ticket #91). Two hops, because `/ledger` is keyed on the AR
     * **sub-account** while the rest of the app knows parties by their Aromex entity id: resolve the
     * customer to get its `accountId`, then read the ledger.
     *
     * The running balance in each row is HL's, passed through untouched — see [LedgerRow.balance].
     */
    override suspend fun getStatement(
        externalId: String,
        from: String?,
        to: String?,
        page: Int,
        limit: Int,
    ): AccountStatement? {
        val customer = parseList(
            getWithAuth {
                url(customersUrl)
                parameter("externalId", externalId)
                parameter("limit", 1)
            },
        ).data.firstOrNull() ?: return null
        val accountId = customer.accountId?.takeIf { it.isNotBlank() } ?: return null

        // Reversal pairs are dropped before the rows are handed up. A reversal means "this didn't
        // happen"; showing the cancelled transaction next to its mirror is an audit trail, which is
        // exactly what a shopkeeper reading their own statement does not want. HL keeps the full
        // record either way. A REVERSAL's sourceId is "reversal_<originalTransactionId>", which is
        // what makes the pairing exact rather than guesswork on descriptions.
        val hidden = reversalPairIds(accountId)

        val resp = getWithAuth {
            url(ledgerUrl)
            parameter("accountId", accountId)
            from?.let { parameter("from", it) }
            to?.let { parameter("to", it) }
            parameter("page", page)
            parameter("limit", limit)
        }
        if (!resp.status.isSuccess()) throw HlException(HlError.HlUnreachable)
        val env = try {
            resp.body<LedgerEnvelope>()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw HlException(HlError.Unexpected("HL returned a malformed ledger body"))
        }
        if (!env.success || env.data == null) {
            throw HlException(HlError.Unexpected("HL ledger success=false"))
        }
        val statement = env.data.toStatement(accountId, hidden)
        // Join each row to its Aromex sourceId so a sale's charge and the cash taken for it can be
        // shown as one event rather than as two double-entry lines.
        return statement.copy(rows = withSourceIds(accountId, from, to, statement.rows))
    }

    /**
     * `transactionId → (sourceId, createdAt)` for the rows on this page, attached to each row.
     *
     * HL's `/ledger` projection doesn't carry `sourceId` (it selects date/description/postingType/id
     * only), so it's read from `/transactions` over the same window and joined here. Best-effort by
     * design: a row we can't resolve keeps `sourceId = null` and simply renders on its own, which is
     * the pre-existing behaviour — a statement must never fail over a grouping nicety.
     */
    private suspend fun withSourceIds(
        accountId: String,
        from: String?,
        to: String?,
        rows: List<LedgerRow>,
    ): List<LedgerRow> {
        if (rows.isEmpty()) return rows
        val wanted = rows.mapTo(HashSet()) { it.transactionId }
        val meta = HashMap<String, TransactionDto>(wanted.size)
        var page = 1
        while (page <= TXN_MAX_PAGES && meta.size < wanted.size) {
            val env = fetchTransactions(page) {
                parameter("accountId", accountId)
                from?.let { parameter("from", it) }
                to?.let { parameter("to", it) }
            } ?: break
            for (txn in env.data) {
                if (txn.id in wanted) meta[txn.id] = txn
            }
            if (env.data.size < HL_MAX_LIMIT) break // last page
            page++
        }
        if (meta.isEmpty()) return rows
        return rows.map { row ->
            val m = meta[row.transactionId] ?: return@map row
            row.copy(sourceId = m.sourceId ?: row.sourceId, recordedAt = m.createdAt)
        }
    }

    /** Transaction ids to omit: every REVERSAL touching this account, plus what each one cancelled. */
    private suspend fun reversalPairIds(accountId: String): Set<String> = buildSet {
        // Deliberately not date-filtered: a transaction reversed *after* the window still didn't
        // happen, so it must stay hidden when an earlier window is viewed.
        var page = 1
        while (page <= TXN_MAX_PAGES) {
            val env = fetchTransactions(page) {
                parameter("accountId", accountId)
                parameter("postingType", "REVERSAL")
            } ?: return@buildSet // never fail a statement over this
            env.data.forEach { txn ->
                add(txn.id)
                txn.sourceId?.removePrefix(REVERSAL_SOURCE_PREFIX)
                    ?.takeIf { it.isNotBlank() && it != txn.sourceId }
                    ?.let { add(it) }
            }
            if (env.data.size < HL_MAX_LIMIT) break
            page++
        }
    }

    /**
     * One page of `/transactions`, or null if it couldn't be read.
     *
     * [HL_MAX_LIMIT] is not a preference — HL's `transactionsQuerySchema` caps `limit` at 100 and
     * validates with `zod.parse`, so asking for more is a 400, not a clamp. This previously sent
     * [PAGE_SIZE] (200): every reversal lookup failed, the error was swallowed, and reversed
     * transactions kept appearing on statements.
     */
    private suspend fun fetchTransactions(
        page: Int,
        params: HttpRequestBuilder.() -> Unit,
    ): TransactionsEnvelope? {
        val resp = getWithAuth {
            url(transactionsUrl)
            params()
            parameter("page", page)
            parameter("limit", HL_MAX_LIMIT)
        }
        if (!resp.status.isSuccess()) return null
        return try {
            resp.body<TransactionsEnvelope>()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            null
        }
    }

    /** GET with the bearer token; on 401 invalidate + retry once, then give up. */
    private suspend fun getWithAuth(block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val first = authedGet(block)
        if (first.status != HttpStatusCode.Unauthorized) return first
        tokenProvider.invalidate()
        val second = authedGet(block)
        if (second.status == HttpStatusCode.Unauthorized) throw HlException(HlError.Unauthorized)
        return second
    }

    private suspend fun authedGet(block: HttpRequestBuilder.() -> Unit): HttpResponse {
        val token = tokenProvider.currentToken()
        return try {
            client.get {
                block()
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        } catch (c: CancellationException) {
            throw c
        } catch (h: HlException) {
            throw h
        } catch (t: Throwable) {
            throw HlException(HlError.NetworkUnavailable)
        }
    }

    private suspend fun parseList(resp: HttpResponse): CustomersListEnvelope {
        if (!resp.status.isSuccess()) throw HlException(HlError.HlUnreachable)
        val env = try {
            resp.body<CustomersListEnvelope>()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            throw HlException(HlError.Unexpected("HL returned a malformed customers body"))
        }
        if (!env.success) throw HlException(HlError.Unexpected("HL customers success=false"))
        return env
    }

    @Serializable
    private data class CustomersListEnvelope(
        val success: Boolean = false,
        val data: List<CustomerDto> = emptyList(),
        val meta: PageMeta? = null,
    )

    @Serializable
    private data class PageMeta(
        val page: Int = 1,
        val limit: Int = 0,
        val total: Int = 0,
        val totalPages: Int = 0,
        val hasMore: Boolean = false,
    )

    @Serializable
    private data class CustomerDto(
        val id: String = "",
        val externalId: String? = null,
        /** The party's AR sub-account — what `GET /ledger` is keyed on (ticket #91). */
        val accountId: String? = null,
        val balance: String? = null,
        val outstanding: String? = null,
    ) {
        /** Whichever signed-balance field HL sent; defaults to "0" (settled). */
        private val signedBalance: String get() = balance ?: outstanding ?: "0"

        fun toBalance(): EntityBalance = EntityBalance(
            net = Money.abs(signedBalance),
            direction = BalanceDirection.fromBalance(signedBalance),
        )
    }

    @Serializable
    private data class LedgerEnvelope(
        val success: Boolean = false,
        val data: LedgerDataDto? = null,
    )

    @Serializable
    private data class LedgerDataDto(
        val account: LedgerAccountDto? = null,
        val closingBalance: String? = null,
        val rows: List<LedgerRowDto> = emptyList(),
        val meta: PageMeta? = null,
    ) {
        fun toStatement(accountId: String, hidden: Set<String>) = AccountStatement(
            accountId = accountId,
            accountName = account?.name.orEmpty(),
            closingBalance = closingBalance ?: "0",
            rows = rows.filterNot { it.transactionId in hidden }.map { it.toRow() },
            page = meta?.page ?: 1,
            totalRows = meta?.total ?: rows.size,
            hasMore = meta?.hasMore ?: false,
        )
    }

    @Serializable
    private data class LedgerAccountDto(val id: String = "", val name: String = "")

    @Serializable
    private data class LedgerRowDto(
        val date: String = "",
        val description: String = "",
        val postingType: String = "",
        val debit: String? = null,
        val credit: String? = null,
        val balance: String? = null,
        val transactionId: String = "",
    ) {
        fun toRow() = LedgerRow(
            date = date,
            description = description,
            postingType = postingType,
            debit = debit,
            credit = credit,
            // HL's running balance, never recomputed here (see LedgerRow.balance).
            balance = balance ?: "0",
            transactionId = transactionId,
        )
    }

    @Serializable
    private data class TransactionsEnvelope(
        val success: Boolean = false,
        val data: List<TransactionDto> = emptyList(),
    )

    @Serializable
    private data class TransactionDto(
        val id: String = "",
        val sourceId: String? = null,
        /** When HL recorded the posting. Distinct from the transaction's accounting `date`, which
         *  HL stores as a calendar date with no time of day. */
        val createdAt: String? = null,
    )

    companion object {
        /** HL stamps a reversal's sourceId as `reversal_<originalTransactionId>`. */
        private const val REVERSAL_SOURCE_PREFIX = "reversal_"
        private const val PAGE_SIZE = 200
        private const val MAX_PAGES = 1000 // safety stop: 200k parties, far beyond a bounded book

        /** HL's hard cap on `/transactions?limit` — see [fetchTransactions]. */
        private const val HL_MAX_LIMIT = 100

        /** 100 pages × 100 = 10k transactions on one party. Past that, rows just don't group. */
        private const val TXN_MAX_PAGES = 100

        fun defaultHttpClient(): HttpClient = HttpClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
    }
}
