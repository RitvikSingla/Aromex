package com.humblesolutions.aromex.model

/**
 * Outcomes the login flow can fail with. Repositories throw these (wrapped in
 * LoginException) and use cases surface them to the ViewModel for display.
 */
sealed class LoginError {
    object UnknownEmail : LoginError()
    object WrongPassword : LoginError()
    object AccountDisabled : LoginError()
    object MissingUserRecord : LoginError()
    object NetworkUnavailable : LoginError()
    object GatewayUnreachable : LoginError()
    data class FirebaseFailure(val message: String) : LoginError()
    data class Unexpected(val message: String) : LoginError()
}

class LoginException(val error: LoginError) : RuntimeException(error.toString())
