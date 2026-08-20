package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.CompanySettingsChange
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Handle to detach a platform's native listener (e.g. a Firestore `ListenerRegistration`).
 * Returned by the `subscribe` lambda passed to [companyProfileCallbackFlow]; invoked when the
 * [Flow] is cancelled. A `fun interface` so SKIE-bridged Swift closures can supply it as a
 * single lambda.
 */
fun interface CompanyProfileObservation {
    fun cancel()
}

/**
 * Adapts a platform's native listener into a Kotlin [Flow] so iOS (whose Firestore snapshot
 * listener can't produce a `Flow` directly) can feed the shared observe path — the same shape as
 * [commissionRulesCallbackFlow] and [entitiesCallbackFlow]. Android/Desktop build their `Flow`
 * straight from the platform SDK and don't use this.
 *
 * This exists so a phone's till follows a tax-rate change: `UserSession.tax` is captured at
 * sign-in and never refreshed, so without a live profile an admin changing GST at noon leaves
 * every open till charging the old rate for the rest of the day, silently.
 *
 * [subscribe] receives `onEach` (call with the latest profile on every change) and `onError`
 * (call with a message to fail the stream), and returns a [CompanyProfileObservation] used to
 * detach the listener when the flow is cancelled.
 */
fun companyProfileCallbackFlow(
    subscribe: (onEach: (CompanyProfile) -> Unit, onError: (String) -> Unit) -> CompanyProfileObservation,
): Flow<CompanyProfile> = callbackFlow {
    val observation = subscribe(
        { profile -> trySend(profile) },
        { message -> close(ObserveCompanyProfileException(message)) },
    )
    awaitClose { observation.cancel() }
}

/**
 * The settings **change log** for a platform that doesn't surface it.
 *
 * The Settings screen — and therefore the audit log — is Desktop-only for now. A mobile
 * implementation returns this rather than leaving the method unimplemented, so the contract stays
 * honest: an empty history, not a pretence that one was fetched.
 */
fun emptySettingsChangesFlow(): Flow<List<CompanySettingsChange>> = flowOf(emptyList())

/** Thrown into the observe [Flow] when the native listener reports an error. */
class ObserveCompanyProfileException(message: String) : Exception(message)
