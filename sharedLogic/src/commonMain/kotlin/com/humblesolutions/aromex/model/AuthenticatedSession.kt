package com.humblesolutions.aromex.model

/**
 * A signed-in user plus the Firebase config of the company they're signed in to.
 * Both pieces are needed at runtime: the session for display/permissions, the
 * config for subsequent Firestore reads and for sign-out (which needs to look
 * up the right named FirebaseApp).
 */
data class AuthenticatedSession(
    val session: UserSession,
    val config: FirebaseClientConfig,
)
