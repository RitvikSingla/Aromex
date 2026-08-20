package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.model.HlError
import com.humblesolutions.aromex.model.HlException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class KtorEntityLedgerRepositoryTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun clientReturning(
        handler: (callIndex: Int) -> Pair<String, HttpStatusCode>,
    ): HttpClient {
        var calls = 0
        val engine = MockEngine {
            calls++
            val (body, status) = handler(calls)
            respond(content = body, status = status, headers = jsonHeaders)
        }
        return HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
    }

    private fun repo(client: HttpClient, tokenProvider: FakeTokenProvider = FakeTokenProvider()) =
        KtorEntityLedgerRepository(tokenProvider, "https://hl.test", client)

    @Test
    fun getBalances_parsesBulkAndDerivesDirection_byExternalId() = runTest {
        val body = """
            {"success":true,"data":[
              {"id":"c1","externalId":"e1","outstanding":"500.00"},
              {"id":"c2","externalId":"e2","balance":"-200.00"},
              {"id":"c3","externalId":"e3","balance":"0.00"},
              {"id":"c4","balance":"999"}
            ],"meta":{"hasMore":false}}
        """.trimIndent()
        val result = repo(clientReturning { body to HttpStatusCode.OK }).getBalances()

        assertEquals(3, result.size) // c4 has no externalId → dropped
        assertEquals("500.00", result["e1"]!!.net)
        assertEquals(BalanceDirection.RECEIVABLE, result["e1"]!!.direction)
        assertEquals("200.00", result["e2"]!!.net) // absolute value
        assertEquals(BalanceDirection.CREDIT, result["e2"]!!.direction)
        assertEquals(BalanceDirection.SETTLED, result["e3"]!!.direction)
    }

    @Test
    fun on401_invalidatesTokenAndRetriesOnce() = runTest {
        val body = """{"success":true,"data":[{"id":"c1","externalId":"e1","balance":"10.00"}],"meta":{"hasMore":false}}"""
        val tokenProvider = FakeTokenProvider()
        val client = clientReturning { call ->
            if (call == 1) "" to HttpStatusCode.Unauthorized else body to HttpStatusCode.OK
        }
        val result = repo(client, tokenProvider).getBalances()

        assertEquals(1, tokenProvider.invalidateCount)
        assertEquals("10.00", result["e1"]!!.net)
    }

    @Test
    fun persistent401_surfacesUnauthorized() = runTest {
        val tokenProvider = FakeTokenProvider()
        val client = clientReturning { "" to HttpStatusCode.Unauthorized }
        val ex = assertFailsWith<HlException> { repo(client, tokenProvider).getBalances() }
        assertEquals(HlError.Unauthorized, ex.error)
        assertEquals(1, tokenProvider.invalidateCount) // exactly one retry
    }

    @Test
    fun getBalance_single_returnsFirstMatch() = runTest {
        val body = """{"success":true,"data":[{"id":"c9","externalId":"e9","balance":"-42.00"}]}"""
        val balance = repo(clientReturning { body to HttpStatusCode.OK }).getBalance("e9")
        assertEquals("42.00", balance!!.net)
        assertEquals(BalanceDirection.CREDIT, balance.direction)
    }

    @Test
    fun getBalance_single_missing_returnsNull() = runTest {
        val body = """{"success":true,"data":[]}"""
        assertNull(repo(clientReturning { body to HttpStatusCode.OK }).getBalance("nope"))
    }

    @Test
    fun close_withInjectedClient_isNoOp_clientStillUsable() = runTest {
        val body = """{"success":true,"data":[{"id":"c1","externalId":"e1","balance":"1.00"}],"meta":{"hasMore":false}}"""
        val repo = repo(clientReturning { body to HttpStatusCode.OK })
        repo.close() // injected client → must NOT close the caller's client
        // Still usable afterwards:
        assertEquals("1.00", repo.getBalances()["e1"]!!.net)
    }

    // ── statement (ticket #91) ───────────────────────────────────────────────

    private val customerBody = """
        {"success":true,"data":[{"id":"c1","externalId":"e1","accountId":"acct-1","balance":"75"}],"meta":{"hasMore":false}}
    """.trimIndent()

    private val ledgerBody = """
        {"success":true,"data":{
          "account":{"id":"acct-1","name":"Rajesh Traders","type":"ASSET","isActive":true},
          "closingBalance":"75",
          "rows":[
            {"date":"2026-07-28T00:00:00.000Z","description":"Sale (Aromex)","postingType":"SALE","debit":"550","credit":null,"balance":"550","transactionId":"t1"},
            {"date":"2026-07-28T00:00:00.000Z","description":"Payment from Rajesh","postingType":"PAYMENT","debit":null,"credit":"475","balance":"75","transactionId":"t2"},
            {"date":"2026-07-30T00:00:00.000Z","description":"Reversal: ...","postingType":"REVERSAL","debit":null,"credit":"0","balance":"75","transactionId":"t3"}
          ],
          "meta":{"page":1,"limit":50,"total":3,"totalPages":1,"hasMore":false}
        }}
    """.trimIndent()

    /** Two hops: resolve the party's AR sub-account, then read that account's ledger. */
    @Test
    fun getStatement_resolvesTheAccount_thenReturnsHlsOwnRunningBalance() = runTest {
        val client = clientReturning { call -> (if (call == 1) customerBody else ledgerBody) to HttpStatusCode.OK }
        val statement = repo(client).getStatement("e1")!!

        assertEquals("acct-1", statement.accountId)
        assertEquals("Rajesh Traders", statement.accountName)
        assertEquals("75", statement.closingBalance)
        assertEquals(3, statement.rows.size)
        assertEquals(false, statement.hasMore)

        // Balances are passed through verbatim — never recomputed, so they stay correct across
        // pages and date ranges where a client-side accumulator would drift.
        assertEquals(listOf("550", "75", "75"), statement.rows.map { it.balance })
        assertEquals("550", statement.rows[0].debit)
        assertNull(statement.rows[0].credit)
        assertEquals("475", statement.rows[1].credit)
    }

    /** A reversal and its original together are the audit trail — flagged, never filtered. */
    @Test
    fun getStatement_marksReversalRows() = runTest {
        val client = clientReturning { call -> (if (call == 1) customerBody else ledgerBody) to HttpStatusCode.OK }
        val rows = repo(client).getStatement("e1")!!.rows

        assertEquals(listOf(false, false, true), rows.map { it.isReversal })
    }

    @Test
    fun getStatement_isNull_whenHlHasNoCustomerForThisParty() = runTest {
        val empty = """{"success":true,"data":[],"meta":{"hasMore":false}}"""
        assertNull(repo(clientReturning { empty to HttpStatusCode.OK }).getStatement("unknown"))
    }

    /** A synced party with no AR sub-account yet can't have a statement — null, not a crash. */
    @Test
    fun getStatement_isNull_whenTheCustomerHasNoAccountId() = runTest {
        val noAccount = """{"success":true,"data":[{"id":"c1","externalId":"e1","balance":"0"}],"meta":{"hasMore":false}}"""
        assertNull(repo(clientReturning { noAccount to HttpStatusCode.OK }).getStatement("e1"))
    }

    @Test
    fun getStatement_surfacesAnUnreachableLedger_ratherThanAnEmptyHistory() = runTest {
        val client = clientReturning { call ->
            if (call == 1) customerBody to HttpStatusCode.OK else "" to HttpStatusCode.ServiceUnavailable
        }
        assertFailsWith<HlException> { repo(client).getStatement("e1") }
    }
}
