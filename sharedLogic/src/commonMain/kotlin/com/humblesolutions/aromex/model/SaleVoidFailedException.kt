package com.humblesolutions.aromex.model

/**
 * Thrown when a sale **void** was requested but the `voidSale` Cloud Function settled it `FAILED`
 * (ticket #85) — e.g. the IMEI index was re-used by a different serial in the meantime (a re-added
 * handset), or an HL call couldn't complete. Carries the CF's [reason] so the detail view can tell
 * the admin *why* the reversal didn't happen, rather than a bare failure.
 *
 * The whole void path is idempotent, so a genuinely transient failure is retried by the CF and the
 * next request succeeds; this is surfaced only when the CF has recorded a terminal `voidStatus:
 * FAILED`. Nothing partial is ever left behind — the stock restore is one all-or-nothing
 * transaction and the HL calls are guarded/idempotent.
 */
class SaleVoidFailedException(val reason: String?) :
    RuntimeException(reason?.takeIf { it.isNotBlank() } ?: "The sale could not be voided.")
