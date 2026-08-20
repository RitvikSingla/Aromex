package com.humblesolutions.aromex.model

/**
 * Public Firebase config a client needs to initialize a Firebase app at runtime.
 * Returned by the gateway's /resolve-company endpoint. No secrets — these are
 * the same values that would otherwise be bundled in google-services.json.
 */
data class FirebaseClientConfig(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val authDomain: String? = null,
    val storageBucket: String? = null,
    val messagingSenderId: String? = null,
    // iOS-registered Firebase app ID (`1:PROJECT_NUMBER:ios:HASH`). Required for
    // Firebase iOS SDK's FirebaseOptions validation, which rejects Android/web
    // app IDs. Null on companies not yet registered for iOS. Android ignores it.
    val iosApplicationId: String? = null,
)

data class ResolvedCompany(
    val companyId: String,
    val firebaseConfig: FirebaseClientConfig,
)
