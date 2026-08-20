package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.Entity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Handle to detach a platform's native listener (e.g. a Firestore
 * `ListenerRegistration`). Returned by the `subscribe` lambda passed to
 * [entitiesCallbackFlow]; invoked when the [Flow] is cancelled.
 *
 * A `fun interface` so callers (including SKIE-bridged Swift closures) can supply it
 * as a single lambda.
 */
fun interface EntityObservation {
    fun cancel()
}

/**
 * Adapts a platform's native listener into a Kotlin [Flow] so a platform whose SDK
 * can't produce a `Flow` directly (iOS: a Firestore snapshot listener) can still feed
 * the shared observe path — [com.humblesolutions.aromex.usecase.ObserveEntitiesUseCase]
 * — keeping the list rules (permission gate + `isActive` filter) in one place.
 *
 * [subscribe] receives two callbacks and must attach the native listener:
 *  - `onEach` — call with the latest `[Entity]` snapshot on every change.
 *  - `onError` — call with a message to fail the stream (the collector throws it).
 * It returns an [EntityObservation] used to detach the listener when the flow is
 * cancelled.
 *
 * Android/Desktop don't use this — they build their `Flow` directly from the platform
 * SDK (e.g. Firestore's Kotlin snapshot API / a `channelFlow`).
 */
fun entitiesCallbackFlow(
    subscribe: (onEach: (List<Entity>) -> Unit, onError: (String) -> Unit) -> EntityObservation,
): Flow<List<Entity>> = callbackFlow {
    val observation = subscribe(
        { entities -> trySend(entities) },
        { message -> close(ObserveEntitiesException(message)) },
    )
    awaitClose { observation.cancel() }
}

/** Thrown into the observe [Flow] when the native listener reports an error. */
class ObserveEntitiesException(message: String) : Exception(message)
