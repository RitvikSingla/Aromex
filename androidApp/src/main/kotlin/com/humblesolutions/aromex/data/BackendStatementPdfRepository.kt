package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.functions.FirebaseFunctions
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.StatementDocument
import com.humblesolutions.aromex.repository.StatementPdfRepository
import kotlinx.coroutines.tasks.await

/**
 * Calls the `renderStatement` Cloud Function to turn an assembled [StatementDocument] into a PDF
 * (ticket #109). The device never touches the Bill Engine directly — the callable holds that
 * boundary, re-checks `profiles: view`, adds the seller/buyer letterhead, and returns the URL.
 */
class BackendStatementPdfRepository(
    private val context: Context,
    private val config: FirebaseClientConfig,
) : StatementPdfRepository {

    private val functions: FirebaseFunctions
        get() = FirebaseFunctions.getInstance(FirebaseAppFactory.get(context, config))

    override suspend fun render(entityId: String, document: StatementDocument): String {
        val payload = mapOf(
            "entityId" to entityId,
            "statement" to buildMap<String, Any?> {
                document.periodFrom?.let { put("periodFrom", it) }
                put("periodTo", document.periodTo)
                put("openingBalance", document.openingBalance)
                put("totalDebits", document.totalDebits)
                put("totalCredits", document.totalCredits)
                put("closingBalance", document.closingBalance)
                put("agingBuckets", document.agingBuckets.map { mapOf("label" to it.label, "amount" to it.amount) })
                put(
                    "statementRows",
                    document.rows.map { r ->
                        buildMap<String, Any?> {
                            put("date", r.date)
                            put("description", r.description)
                            r.note?.let { put("note", it) }
                            // Money columns carry money; the sale's value rides alongside.
                            put("billed", r.billed)
                            put("kind", r.kind)
                            put("moneyIn", r.moneyIn)
                            put("moneyOut", r.moneyOut)
                            put("balance", r.balance)
                        }
                    },
                )
            },
        )

        val result = functions.getHttpsCallable("renderStatement").call(payload).await()
        @Suppress("UNCHECKED_CAST")
        val data = result.getData() as? Map<String, Any?>
            ?: throw IllegalStateException("renderStatement returned no data")
        return data["url"] as? String
            ?: throw IllegalStateException("renderStatement returned no url")
    }
}
