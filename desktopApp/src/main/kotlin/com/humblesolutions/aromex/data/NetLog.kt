package com.humblesolutions.aromex.data

/**
 * Tiny redacting logger for the Desktop network layer. Mirrors Android's
 * NetLog convention: never let a full JWT / refresh token / access token
 * reach stdout, even at DEBUG. Prefer redact() around any token before
 * putting it in a log line.
 */
internal object NetLog {
    fun d(tag: String, message: String) {
        println("[$tag] $message")
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        System.err.println("[$tag] WARN $message")
        t?.printStackTrace(System.err)
    }

    /**
     * Return a short, non-reversible marker for a token so log traces can
     * distinguish "same token" vs "new token" without leaking the full JWT.
     */
    fun redact(token: String?): String {
        if (token.isNullOrEmpty()) return "<empty>"
        val prefix = token.take(8)
        return "${prefix}…(${token.length}ch)"
    }
}
