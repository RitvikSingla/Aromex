package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.humblesolutions.aromex.model.FirebaseClientConfig

/**
 * Lazily creates (or fetches) a *named* secondary FirebaseApp for the given
 * per-client config. The default FirebaseApp would be whatever google-services.json
 * provides at build time, but Aromex is multi-tenant — no client is baked in,
 * so the FirebaseApp must be created at runtime from gateway-supplied config.
 *
 * The app's `name` is the projectId, so two distinct client projects produce two
 * distinct FirebaseApp instances. Re-calling with the same config is cheap:
 * FirebaseApp.getInstance() is called first and the construction path only runs
 * the first time per app name.
 */
internal object FirebaseAppFactory {
    fun get(context: Context, config: FirebaseClientConfig): FirebaseApp {
        val appName = config.projectId
        return try {
            FirebaseApp.getInstance(appName)
        } catch (_: IllegalStateException) {
            val optionsBuilder = FirebaseOptions.Builder()
                .setApiKey(config.apiKey)
                .setApplicationId(config.applicationId)
                .setProjectId(config.projectId)
            config.storageBucket?.let { optionsBuilder.setStorageBucket(it) }
            config.messagingSenderId?.let { optionsBuilder.setGcmSenderId(it) }
            FirebaseApp.initializeApp(
                context.applicationContext,
                optionsBuilder.build(),
                appName,
            )
        }
    }
}
