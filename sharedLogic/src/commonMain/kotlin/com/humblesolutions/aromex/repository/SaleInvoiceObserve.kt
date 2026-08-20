package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.SaleInvoice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Handle to detach a platform's native listener on the sale doc (mirror of
 * [EntityObservation]). Returned by the `subscribe` lambda passed to
 * [saleInvoiceCallbackFlow]; invoked when the [Flow] is cancelled.
 */
fun interface SaleInvoiceObservation {
    fun cancel()
}

/**
 * Adapts a platform's native `sales/{saleId}` snapshot listener into a Kotlin [Flow] so iOS
 * (whose Firestore SDK can't produce a `Flow` directly) can feed the shared
 * [com.humblesolutions.aromex.usecase.ObserveSaleInvoiceUseCase]. Android/Desktop build their
 * `Flow` directly from the Kotlin SDK and don't need this.
 *
 * [subscribe] attaches the listener and pushes each [SaleInvoice] snapshot through `onEach`;
 * `onError` fails the stream. It returns a [SaleInvoiceObservation] used to detach on cancel.
 */
fun saleInvoiceCallbackFlow(
    subscribe: (onEach: (SaleInvoice) -> Unit, onError: (String) -> Unit) -> SaleInvoiceObservation,
): Flow<SaleInvoice> = callbackFlow {
    val observation = subscribe(
        { invoice -> trySend(invoice) },
        { message -> close(ObserveSaleInvoiceException(message)) },
    )
    awaitClose { observation.cancel() }
}

/** Thrown into the observe [Flow] when the native sale-doc listener reports an error. */
class ObserveSaleInvoiceException(message: String) : Exception(message)
