package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.AttributeValue
import com.humblesolutions.aromex.model.Product
import com.humblesolutions.aromex.model.Serial
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Handle to detach a platform's native inventory listener (e.g. a Firestore
 * `ListenerRegistration`). Returned by the `subscribe` lambda passed to
 * [productsCallbackFlow] / [serialsCallbackFlow]; invoked when the [Flow] is cancelled.
 *
 * A `fun interface` so callers (including SKIE-bridged Swift closures) can supply it as
 * a single lambda. Mirrors [EntityObservation] — the same #34 pattern, for inventory.
 */
fun interface InventoryObservation {
    fun cancel()
}

/**
 * Adapts a platform's native listener into a Kotlin [Flow] of [Product]s, so a platform
 * whose SDK can't produce a `Flow` directly (iOS: a Firestore snapshot listener) can
 * still feed the shared observe path
 * ([com.humblesolutions.aromex.usecase.ObserveInventoryUseCase]), keeping the list rules
 * (permission gate + `isActive` filter) in one place.
 *
 * [subscribe] receives two callbacks and must attach the native listener:
 *  - `onEach` — call with the latest `[Product]` snapshot on every change.
 *  - `onError` — call with a message to fail the stream (the collector throws it).
 * It returns an [InventoryObservation] used to detach the listener on cancellation.
 *
 * Android/Desktop don't use this — they build their `Flow` directly from the platform SDK.
 */
fun productsCallbackFlow(
    subscribe: (onEach: (List<Product>) -> Unit, onError: (String) -> Unit) -> InventoryObservation,
): Flow<List<Product>> = callbackFlow {
    val observation = subscribe(
        { products -> trySend(products) },
        { message -> close(ObserveInventoryException(message)) },
    )
    awaitClose { observation.cancel() }
}

/**
 * The [Serial] twin of [productsCallbackFlow] — adapts a native listener into a Kotlin
 * [Flow] of in-stock units for iOS, feeding the same shared observe path.
 */
fun serialsCallbackFlow(
    subscribe: (onEach: (List<Serial>) -> Unit, onError: (String) -> Unit) -> InventoryObservation,
): Flow<List<Serial>> = callbackFlow {
    val observation = subscribe(
        { serials -> trySend(serials) },
        { message -> close(ObserveInventoryException(message)) },
    )
    awaitClose { observation.cancel() }
}

/**
 * The [AttributeValue] twin of [productsCallbackFlow] — adapts a native listener into a
 * Kotlin [Flow] of managed-vocabulary values for iOS, feeding the same shared path.
 */
fun attributesCallbackFlow(
    subscribe: (onEach: (List<AttributeValue>) -> Unit, onError: (String) -> Unit) -> InventoryObservation,
): Flow<List<AttributeValue>> = callbackFlow {
    val observation = subscribe(
        { values -> trySend(values) },
        { message -> close(ObserveInventoryException(message)) },
    )
    awaitClose { observation.cancel() }
}

/** Thrown into an inventory observe [Flow] when the native listener reports an error. */
class ObserveInventoryException(message: String) : Exception(message)
