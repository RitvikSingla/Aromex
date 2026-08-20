package com.humblesolutions.aromex.model

/**
 * Thrown when a cashier-facing invoice **Retry** was recorded but the backend never picked it up
 * (ticket #77).
 *
 * Only Desktop can hit this. Mobile calls the `retryInvoice` callable, so an absent or broken
 * Cloud Function surfaces as a call failure; Desktop instead nudges the sale doc with the Admin
 * SDK, and *that write succeeds regardless* — with functions undeployed nothing throws, the
 * snapshot listener re-emits the same `FAILED`, and the cashier sees the spinner blink and
 * nothing else. `retryInvoiceCore` flips the doc to `PENDING` before it re-renders, so that flip
 * is the acknowledgement Desktop waits for; its absence means the trigger isn't running.
 *
 * Nothing was lost when this is thrown: the sale and the books are already correct and the
 * reconcile sweep still owns the invoice. It only tells the UI that *this* click achieved
 * nothing, so it can say so instead of implying a retry is under way.
 */
class InvoiceRetryNotAcknowledgedException :
    RuntimeException(
        "The invoice retry was recorded but the backend did not pick it up; " +
            "it will be retried automatically.",
    )
