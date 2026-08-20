package com.humblesolutions.aromex.data

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import com.humblesolutions.aromex.model.FirebaseClientConfig
import com.humblesolutions.aromex.model.LoginError
import com.humblesolutions.aromex.model.LoginException
import com.humblesolutions.aromex.repository.AuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class FirebaseAuthRepository(
    private val context: Context,
) : AuthRepository {

    private fun authFor(config: FirebaseClientConfig): FirebaseAuth {
        val app = FirebaseAppFactory.get(context, config)
        return Firebase.auth(app)
    }

    override suspend fun signIn(
        config: FirebaseClientConfig,
        email: String,
        password: String,
    ): String = withContext(Dispatchers.IO) {
        try {
            val result = authFor(config).signInWithEmailAndPassword(email, password).await()
            result.user?.uid ?: throw LoginException(LoginError.FirebaseFailure("no user in result"))
        } catch (le: LoginException) {
            throw le
        } catch (e: FirebaseAuthInvalidCredentialsException) {
            throw LoginException(LoginError.WrongPassword)
        } catch (e: FirebaseAuthInvalidUserException) {
            throw LoginException(LoginError.WrongPassword)
        } catch (t: Throwable) {
            throw LoginException(LoginError.FirebaseFailure(t.message ?: t::class.simpleName.orEmpty()))
        }
    }

    override suspend fun signOut(config: FirebaseClientConfig) {
        withContext(Dispatchers.IO) {
            authFor(config).signOut()
        }
    }

    override suspend fun currentUid(config: FirebaseClientConfig): String? =
        withContext(Dispatchers.IO) {
            authFor(config).currentUser?.uid
        }

    override suspend fun idToken(config: FirebaseClientConfig, forceRefresh: Boolean): String =
        withContext(Dispatchers.IO) {
            val user = authFor(config).currentUser
                ?: throw LoginException(LoginError.Unexpected("no signed-in user"))
            try {
                val result = user.getIdToken(forceRefresh).await()
                result.token
                    ?: throw LoginException(LoginError.Unexpected("Firebase returned null ID token"))
            } catch (le: LoginException) {
                throw le
            } catch (t: Throwable) {
                throw LoginException(LoginError.FirebaseFailure(t.message ?: t::class.simpleName.orEmpty()))
            }
        }
}
