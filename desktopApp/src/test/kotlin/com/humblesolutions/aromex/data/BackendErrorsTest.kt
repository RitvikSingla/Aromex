package com.humblesolutions.aromex.data

import com.humblesolutions.aromex.i18n.Strings
import java.io.IOException
import java.util.concurrent.ExecutionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Turning backend failures into something a cashier can act on.
 *
 * The message that prompted this read `com.google.api.gax.rpc.UnauthenticatedException:
 * io.grpc.StatusRuntimeException: UNAUTHENTICATED: Request had invalid authentication credentials.
 * Expected OAuth 2 access token, login cookie or other valid...` — from which nobody can tell
 * whether their entry saved.
 */
class BackendErrorsTest {

    @Test
    fun recognisesAnAuthFailure_evenWrappedInTheSdkFuture() {
        val raw = RuntimeException(
            "io.grpc.StatusRuntimeException: UNAUTHENTICATED: Request had invalid authentication credentials",
        )
        assertTrue(BackendErrors.isAuthFailure(raw))
        // The Admin SDK wraps everything its futures throw.
        assertTrue(BackendErrors.isAuthFailure(ExecutionException(raw)))
        assertEquals(Strings.backend_error_auth, BackendErrors.messageKeyOrNull(raw))
    }

    @Test
    fun recognisesPermissionDenied() {
        val e = RuntimeException("PERMISSION_DENIED: caller lacks permission")
        assertTrue(BackendErrors.isAuthFailure(e))
        assertEquals(Strings.backend_error_auth, BackendErrors.messageKeyOrNull(e))
    }

    @Test
    fun recognisesTheTransientOnes() {
        listOf("UNAVAILABLE: connection closed", "DEADLINE_EXCEEDED", "ABORTED: too much contention")
            .forEach { m ->
                assertEquals(
                    Strings.backend_error_unreachable,
                    BackendErrors.messageKeyOrNull(RuntimeException(m)),
                    "should recognise '$m'",
                )
            }
        assertEquals(Strings.backend_error_unreachable, BackendErrors.messageKeyOrNull(IOException("socket")))
    }

    /**
     * Anything unrecognised keeps its own message. A failure we didn't anticipate is precisely the
     * one worth reading verbatim rather than flattening into "something went wrong".
     */
    @Test
    fun leavesUnrecognisedFailuresAlone() {
        assertNull(BackendErrors.messageKeyOrNull(IllegalArgumentException("From and To must differ")))
        assertFalse(BackendErrors.isAuthFailure(IllegalStateException("nope")))
    }

    @Test
    fun unwrapsTheFutureWrapper_andSurvivesACauseCycle() {
        val inner = RuntimeException("UNAUTHENTICATED")
        assertEquals(inner, BackendErrors.unwrap(ExecutionException(inner)))
        assertEquals(inner, BackendErrors.unwrap(inner))
        // A self-referencing cause must not spin forever.
        val looped = object : RuntimeException("boom") {
            override val cause: Throwable get() = this
        }
        assertFalse(BackendErrors.isAuthFailure(looped))
    }
}
