package com.humblesolutions.aromex.model

/**
 * Outcomes the HL read path can fail with. Repositories throw these (wrapped in
 * HlException) and use cases surface them to the ViewModel for display.
 */
sealed class HlError {
    object NetworkUnavailable : HlError()
    object GatewayUnreachable : HlError()
    object TokenRejected : HlError()
    object HlUnreachable : HlError()
    object Unauthorized : HlError()
    data class Unexpected(val message: String) : HlError()
}

class HlException(val error: HlError) : RuntimeException(error.toString())
