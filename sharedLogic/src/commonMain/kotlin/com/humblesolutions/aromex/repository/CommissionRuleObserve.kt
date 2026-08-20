package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.CommissionRule
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Handle to detach a platform's native listener (e.g. a Firestore `ListenerRegistration`).
 * Returned by the `subscribe` lambda passed to [commissionRulesCallbackFlow]; invoked when
 * the [Flow] is cancelled. A `fun interface` so SKIE-bridged Swift closures can supply it
 * as a single lambda.
 */
fun interface CommissionRuleObservation {
    fun cancel()
}

/**
 * Adapts a platform's native listener into a Kotlin [Flow] so iOS (whose Firestore snapshot
 * listener can't produce a `Flow` directly) can still feed the shared observe path (ticket
 * #97). Android/Desktop build their `Flow` directly from the platform SDK and don't use this.
 *
 * [subscribe] receives `onEach` (call with the latest `[CommissionRule]` snapshot on every
 * change) and `onError` (call with a message to fail the stream), and returns a
 * [CommissionRuleObservation] used to detach the listener when the flow is cancelled.
 */
fun commissionRulesCallbackFlow(
    subscribe: (onEach: (List<CommissionRule>) -> Unit, onError: (String) -> Unit) -> CommissionRuleObservation,
): Flow<List<CommissionRule>> = callbackFlow {
    val observation = subscribe(
        { rules -> trySend(rules) },
        { message -> close(ObserveCommissionRulesException(message)) },
    )
    awaitClose { observation.cancel() }
}

/** Thrown into the observe [Flow] when the native listener reports an error. */
class ObserveCommissionRulesException(message: String) : Exception(message)
