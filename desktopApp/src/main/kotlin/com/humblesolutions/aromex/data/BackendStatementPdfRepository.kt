package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.StatementDocument
import com.humblesolutions.aromex.repository.AuthRepository
import com.humblesolutions.aromex.repository.StatementPdfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Desktop [StatementPdfRepository] (ticket #109): renders a party's statement PDF via the
 * `renderStatement` Cloud Function.
 *
 * Desktop has no Firebase client SDK (it reaches Firestore through the Admin SDK), so — unlike the
 * mobile impls — it invokes the callable over its plain HTTPS endpoint using the **callable
 * protocol**: the request is wrapped as `{ "data": … }`, the Firebase ID token goes in the
 * `Authorization` header, and success comes back as `{ "result": { "url": … } }`. The engine itself
 * is never touched from here — the callable holds that boundary and re-checks `profiles: view`.
 */
class BackendStatementPdfRepository(
    private val authRepo: AuthRepository,
    private val config: FirebaseClientConfig,
    private val client: OkHttpClient = OkHttpClient(),
    region: String = "us-central1",
) : StatementPdfRepository {

    private val endpoint = "https://$region-${config.projectId}.cloudfunctions.net/renderStatement"

    override suspend fun render(entityId: String, document: StatementDocument): String =
        withContext(Dispatchers.IO) {
            val token = authRepo.idToken(config, false)
            val payload = buildJsonObject {
                put("data", buildJsonObject {
                    put("entityId", entityId)
                    put("statement", buildJsonObject {
                        document.periodFrom?.let { put("periodFrom", it) }
                        put("periodTo", document.periodTo)
                        put("openingBalance", document.openingBalance)
                        put("totalDebits", document.totalDebits)
                        put("totalCredits", document.totalCredits)
                        put("closingBalance", document.closingBalance)
                        put("agingBuckets", buildJsonArray {
                            document.agingBuckets.forEach { b ->
                                add(buildJsonObject { put("label", b.label); put("amount", b.amount) })
                            }
                        })
                        put("statementRows", buildJsonArray {
                            document.rows.forEach { r ->
                                add(buildJsonObject {
                                    put("date", r.date)
                                    put("description", r.description)
                                    r.note?.let { put("note", it) }
                                    // Money columns carry money; the sale's value rides alongside.
                                    put("billed", r.billed)
                                    put("kind", r.kind)
                                    put("moneyIn", r.moneyIn)
                                    put("moneyOut", r.moneyOut)
                                    put("balance", r.balance)
                                })
                            }
                        })
                    })
                })
            }

            val request = Request.Builder()
                .url(endpoint)
                .header("Authorization", "Bearer $token")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException(parseError(text) ?: "Couldn't generate the statement (${resp.code}).")
                }
                val result = Json.parseToJsonElement(text).jsonObject["result"]?.jsonObject
                result?.get("url")?.jsonPrimitive?.contentOrNull
                    ?: throw RuntimeException("The statement service returned no URL.")
            }
        }

    /** The Firebase callable error envelope is `{ "error": { "message", "status" } }`. */
    private fun parseError(text: String): String? = runCatching {
        Json.parseToJsonElement(text).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
