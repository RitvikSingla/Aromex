package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.i18n.Strings
import java.util.concurrent.ExecutionException

/**
 * Turns an Admin-SDK / gRPC failure into something a cashier can act on.
 *
 * The SDK's own text is unusable at a counter — a real one read
 * `com.google.api.gax.rpc.UnauthenticatedException: io.grpc.StatusRuntimeException: UNAUTHENTICATED:
 * Request had invalid authentication credentials. Expected OAuth 2 access token, login cookie or
 * other valid...`, from which nobody can tell whether their entry saved. These map the handful of
 * failures that actually reach a user onto plain sentences that say what happened and what to do.
 *
 * Returns an i18n **key**, so the caller resolves it in the UI layer.
 */
internal object BackendErrors {

    /** True when the failure is the backend refusing our credentials, at any depth. */
    fun isAuthFailure(t: Throwable): Boolean = causeChain(t).any { e ->
        val name = e::class.qualifiedName.orEmpty()
        name.contains("Unauthenticated") ||
            name.contains("PermissionDenied") ||
            e.message?.contains("UNAUTHENTICATED") == true ||
            e.message?.contains("PERMISSION_DENIED") == true
    }

    /** True for the transient ones — worth telling someone to simply try again. */
    private fun isTransient(t: Throwable): Boolean = causeChain(t).any { e ->
        val m = e.message.orEmpty()
        m.contains("UNAVAILABLE") ||
            m.contains("DEADLINE_EXCEEDED") ||
            m.contains("ABORTED") ||
            e is java.io.IOException
    }

    /**
     * The i18n key for [t]. Deliberately conservative: anything unrecognised keeps its own message
     * rather than being flattened into a vague "something went wrong", because a message we didn't
     * anticipate is exactly the one worth reading verbatim.
     */
    fun messageKeyOrNull(t: Throwable): String? = when {
        isAuthFailure(t) -> Strings.backend_error_auth
        isTransient(t) -> Strings.backend_error_unreachable
        else -> null
    }

    private fun causeChain(t: Throwable): Sequence<Throwable> =
        generateSequence(t) { if (it.cause === it) null else it.cause }

    /** Unwraps the `ExecutionException` the Admin SDK's futures wrap everything in. */
    fun unwrap(t: Throwable): Throwable = if (t is ExecutionException) t.cause ?: t else t
}
