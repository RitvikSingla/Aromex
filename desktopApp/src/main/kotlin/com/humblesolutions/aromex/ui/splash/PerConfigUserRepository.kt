package com.humblesolutions.aromex.ui.splash

import com.humblesolutions.aromex.data.FirestoreUserRepository
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.repository.CompanyProfile
import com.humblesolutions.aromex.repository.UserProfile
import com.humblesolutions.aromex.repository.UserRepository

/**
 * Small adapter: shared use cases expect a single [UserRepository], but on
 * Desktop the underlying Firestore client is per-config (built with a
 * gateway-brokered datastore token that's scoped to one Firebase project).
 * This adapter lazily builds — and caches — one [FirestoreUserRepository]
 * per config, then delegates the interface method to it.
 */
internal class PerConfigUserRepository(
    private val builder: (FirebaseClientConfig) -> FirestoreUserRepository,
) : UserRepository {

    private val cache = mutableMapOf<String, FirestoreUserRepository>()

    private fun repoFor(config: FirebaseClientConfig): FirestoreUserRepository =
        cache.getOrPut(config.projectId) { builder(config) }

    override suspend fun getUserProfile(config: FirebaseClientConfig, uid: String): UserProfile? =
        repoFor(config).getUserProfile(config, uid)

    override suspend fun getCompanyProfile(config: FirebaseClientConfig): CompanyProfile? =
        repoFor(config).getCompanyProfile(config)
}
