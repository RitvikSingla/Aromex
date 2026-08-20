package com.humblesolutions.aromex.devlog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** A single captured log line. [level] is one of INFO / WARN / ERROR. */
data class LogEntry(
    val seq: Long,
    val timeMs: Long,
    val level: String,
    val message: String,
)

/**
 * In-memory log sink behind the desktop dev-log overlay (bottom-right "Logs" button →
 * floating window). Holds the last [MAX] lines in a [StateFlow] the UI observes.
 *
 * [install] redirects `System.out` / `System.err` and attaches a `java.util.logging`
 * handler so existing `println`s, uncaught stack traces, and JUL-based SDK logs (Firestore,
 * gRPC) surface here **without touching any call sites**. Desktop-only dev tooling — not
 * shipped business logic, and it never lives in `sharedLogic`.
 */
object LogBus {
    private const val MAX = 2000
    private var seq = 0L
    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    @Synchronized
    fun log(level: String, message: String) {
        val entry = LogEntry(seq++, System.currentTimeMillis(), level, message)
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX) next.takeLast(MAX) else next
        }
    }

    fun clear() {
        _entries.value = emptyList()
    }

    @Volatile
    private var installed = false

    /** Idempotently wire stdout/stderr + java.util.logging into this sink. Call once at startup. */
    @Synchronized
    fun install() {
        if (installed) return
        installed = true
        System.setOut(tee(System.out, error = false))
        System.setErr(tee(System.err, error = true))
        attachJul()
        log("INFO", "Logger started — capturing stdout, stderr and java.util.logging.")
    }

    /** A PrintStream that writes through to [original] (the real console) and mirrors each
     *  completed line into the sink. Bytes are buffered and decoded as UTF-8 per line. */
    private fun tee(original: PrintStream, error: Boolean): PrintStream {
        val line = ByteArrayOutputStream(256)
        val sink = object : OutputStream() {
            @Synchronized
            override fun write(b: Int) {
                original.write(b)
                when (b) {
                    '\n'.code -> emit()
                    '\r'.code -> Unit
                    else -> line.write(b)
                }
            }

            @Synchronized
            override fun flush() = original.flush()

            private fun emit() {
                val text = line.toString(Charsets.UTF_8)
                line.reset()
                if (text.isNotBlank()) log(if (error) "ERROR" else "INFO", text)
            }
        }
        return PrintStream(sink, true, Charsets.UTF_8)
    }

    private fun attachJul() {
        Logger.getLogger("").addHandler(object : Handler() {
            override fun publish(record: LogRecord) {
                val level = when {
                    record.level.intValue() >= Level.SEVERE.intValue() -> "ERROR"
                    record.level.intValue() >= Level.WARNING.intValue() -> "WARN"
                    else -> "INFO"
                }
                val tag = record.loggerName?.substringAfterLast('.').orEmpty()
                val msg = record.message.orEmpty()
                log(level, if (tag.isBlank()) msg else "[$tag] $msg")
            }

            override fun flush() = Unit
            override fun close() = Unit
        })
    }
}

/** Convenience for app code that wants to log explicitly to the overlay. */
object AppLog {
    fun i(message: String) = LogBus.log("INFO", message)
    fun w(message: String) = LogBus.log("WARN", message)
    fun e(message: String) = LogBus.log("ERROR", message)
}
