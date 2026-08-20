package com.humblesolutions.aromex.repository

import com.humblesolutions.aromex.model.FirebaseClientConfig

interface AuthRepository {
    /**
     * Signs in to the Firebase project described by `config` with email+password.
     * Initializes a named/secondary Firebase app at runtime if needed.
     * Returns the resulting Firebase Auth uid.
     * Throws LoginException for wrong password, unknown user, or transport failures.
     */
    suspend fun signIn(config: FirebaseClientConfig, email: String, password: String): String

    /** Signs out the currently signed-in user on this company's Firebase app. */
    suspend fun signOut(config: FirebaseClientConfig)

    /**
     * Returns the currently signed-in uid for this company's Firebase app, or null if
     * nobody is signed in. Used to restore a session on app launch.
     */
    suspend fun currentUid(config: FirebaseClientConfig): String?

    /**
     * Returns a fresh Firebase ID token for the currently signed-in user on this
     * company's Firebase app. Set [forceRefresh] to true to skip Firebase's own
     * in-memory cache (typically because the gateway rejected the previous token).
     * Throws LoginException if no user is signed in or Firebase fails.
     */
    suspend fun idToken(config: FirebaseClientConfig, forceRefresh: Boolean = false): String
}
