package com.humblesolutions.aromex.usecase

import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.repository.AuthRepository
import kotlin.coroutines.cancellation.CancellationException

class LogoutUseCase(
    private val auth: AuthRepository,
) {
    @Throws(LoginException::class, CancellationException::class)
    suspend fun execute(config: FirebaseClientConfig) {
        auth.signOut(config)
    }
}
