package com.humblesolutions.aromex.entities

import com.humblesolutions.aromex.data.KtorEntityLedgerRepository
import com.humblesolutions.aromex.model.BalanceDirection
import com.humblesolutions.aromex.repository.HlTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Live smoke test of the REAL shipped [KtorEntityLedgerRepository] against a real
 * Humble Ledger company. Self-skips unless credentials are provided via env vars,
 * so it never runs (or needs secrets) in normal CI:
 *
 *   HL_BASE_URL=https://ledger.humblesolutions.in \
 *   HL_EMAIL=aromex-test@yourco.com HL_PASSWORD=... \
 *   ./gradlew :sharedLogic:jvmTest --tests '*LiveHlLedgerIntegrationTest*'
 *
 * Expects the two seed customers (externalId t1-smoke-recv = +500 RECEIVABLE,
 * t1-smoke-cred = -300 CREDIT) to exist on that company.
 */
class LiveHlLedgerIntegrationTest {

    @Test
    fun realClient_readsSeededBalancesWithCorrectDirection() {
        val base = System.getenv("HL_BASE_URL")
        val email = System.getenv("HL_EMAIL")
        val password = System.getenv("HL_PASSWORD")
        if (base.isNullOrBlank() || email.isNullOrBlank() || password.isNullOrBlank()) {
            println("LiveHlLedgerIntegrationTest skipped — set HL_BASE_URL/HL_EMAIL/HL_PASSWORD to run.")
            return
        }

        runBlocking {
            val token = login(base, email, password)
            val repo = KtorEntityLedgerRepository(StaticToken(token), base)
            try {
                val balances = repo.getBalances()

                val recv = balances["t1-smoke-recv"]
                assertNotNull(recv, "expected seed customer t1-smoke-recv")
                assertEquals(BalanceDirection.RECEIVABLE, recv.direction)
                assertEquals("500.00", recv.net)

                val cred = balances["t1-smoke-cred"]
                assertNotNull(cred, "expected seed customer t1-smoke-cred")
                assertEquals(BalanceDirection.CREDIT, cred.direction)
                assertEquals("300.00", cred.net)

                // Single-read path too.
                val single = repo.getBalance("t1-smoke-recv")
                assertNotNull(single)
                assertEquals(BalanceDirection.RECEIVABLE, single.direction)
            } finally {
                repo.close()
            }
        }
    }

    private class StaticToken(private val token: String) : HlTokenProvider {
        override suspend fun currentToken() = token
        override suspend fun invalidate() {}
    }

    @Serializable private data class LoginEnvelope(val success: Boolean = false, val data: LoginData? = null)
    @Serializable private data class LoginData(val accessToken: String = "")

    private suspend fun login(base: String, email: String, password: String): String {
        val client = HttpClient {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val env: LoginEnvelope = client.post("${base.trimEnd('/')}/api/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email, "password" to password))
        }.body()
        return env.data?.accessToken.orEmpty()
    }
}
