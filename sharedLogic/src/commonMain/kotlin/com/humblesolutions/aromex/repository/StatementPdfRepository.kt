package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.StatementDocument

/**
 * Renders an assembled [StatementDocument] to a PDF and returns its URL (ticket #109).
 *
 * The Humble Bill Engine is **unauthenticated and may only be reached from a Cloud Function**, so
 * every platform implementation is a thin call to the `renderStatement` callable — never a direct
 * engine request from the device. The callable re-checks `profiles: view`, wraps the seller/buyer
 * letterhead around the document, and calls the engine.
 */
interface StatementPdfRepository {
    /**
     * @param entityId the party the statement is for (the Aromex entity id == HL externalId); the
     *   callable reads the buyer letterhead from `entities/{entityId}` server-side.
     * @param document the fully-assembled statement (rows, opening balance, totals, aging).
     * @return the permanent, public PDF URL.
     * @throws Exception with a readable message when rendering fails (permission, engine, network).
     */
    suspend fun render(entityId: String, document: StatementDocument): String
}
