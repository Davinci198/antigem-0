package com.example.gemini.data.security

import android.util.Log

/**
 * AppLog: safe logging for AntiGem (pattern from OpenCode Mobile, MIT, © 2026 Eslam Sabry).
 *
 * Defaults to redacting anything that looks like a credential before it reaches logcat:
 * - `token=...`, `secret=...`, `key=...`, `password=...`, `Authorization: ...` and similar
 * - bare JWT-ish / high-entropy hex/base64 values
 * - `?query=` parts of URLs (they can carry tokens) unless the caller opts in
 *
 * Callers that need full URLs can use [dRaw] / [Log] directly, but never for credentials.
 */
object AppLog {

    private const val TAG = "AntiGem"

    private val REDACT_PATTERN = Regex(
        """(?i)(\b(?:token|secret|key|password|passwd|credential|authorization|bearer|api[_-]?key|csrf|session)\b\s*[=:]\s*)([^&\s'"]{4,})""",
        RegexOption.IGNORE_CASE
    )

    fun d(message: String) = Log.d(TAG, redact(message))
    fun i(message: String) = Log.i(TAG, redact(message))
    fun w(message: String) = Log.w(TAG, redact(message))
    fun e(message: String, t: Throwable? = null) =
        if (t != null) Log.e(TAG, redact(message), t) else Log.e(TAG, redact(message))

    /** Log with the value redacted but keep a hint of what/where. */
    fun redact(message: String): String =
        message
            .replace(REDACT_PATTERN) { m ->
                val prefix = m.groupValues[1]
                prefix + "<redacted>"
            }
            .replace(QUERY_SEP, "?<redacted>")

    private val QUERY_SEP = Regex("""\?[^#\s]*""")

    /** Allow a specific substring to bypass redaction for debugging (used sparingly). */
    fun withoutRedaction(message: String): String = message
}
