package com.humblesolutions.aromex.data

import android.util.Log

/**
 * Minimal helpers for redacting auth material before it goes to logcat.
 * Production builds should disable verbose logging entirely; for the M2 dev
 * loop we want to see request/response shape but never full tokens.
 */
internal object NetLog {
    fun redact(token: String?): String {
        if (token.isNullOrEmpty()) return "<null>"
        val head = token.take(8)
        return "$head…(${token.length}ch)"
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
    }

    fun w(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
    }

    /**
     * Logcat truncates lines at ~4 KB. Use this for response bodies that might
     * exceed that. Prints a header line, then the body split into chunks.
     */
    fun logLong(tag: String, header: String, body: String) {
        Log.d(tag, header)
        val chunkSize = 3500
        if (body.length <= chunkSize) {
            Log.d(tag, body)
            return
        }
        var i = 0
        var part = 1
        while (i < body.length) {
            val end = minOf(i + chunkSize, body.length)
            Log.d(tag, "[$part] ${body.substring(i, end)}")
            i = end
            part += 1
        }
    }
}
