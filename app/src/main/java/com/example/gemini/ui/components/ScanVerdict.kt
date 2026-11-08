package com.example.gemini.ui.components

/**
 * How a model answered a scan probe, mirroring Mobile-Harness' ModelHealthStatus.
 *
 * A boolean cannot say why a model failed, and the difference matters: a 429 is
 * a rate limit that clears in a minute, a 402 means the key has no credit, a
 * timeout means the model is slow rather than dead. Collapsing those into
 * "broken" made the scan look like it was failing when it was probing too hard.
 */
enum class ScanKind {
    /** 2xx: the model answered. */
    OK,

    /** The probe exceeded its deadline; the model may be fine, just slow. */
    TIMEOUT,

    /** 402: the provider is reachable but this key cannot pay for the model. */
    CREDITS,

    /** 429: throttled. Says nothing about the model; retry later. */
    THROTTLED,

    /** 4xx/5xx that is not the above, or an unparseable answer. */
    FAIL,

    /** The request never completed (DNS, connection refused, bad URL). */
    ERROR,
    ;

    /** Only these two mean the model itself is unusable. */
    val isBroken: Boolean get() = this == FAIL || this == ERROR
}

/** A scan verdict as the UI shows it, independent of which backend produced it. */
data class ScanVerdict(
    val kind: ScanKind,
    val latencyMs: Long = 0,
    val status: Int = 0,
    val detail: String? = null,
) {
    val ok: Boolean get() = kind == ScanKind.OK

    /** The short badge text: a latency, or the reason there is none. */
    fun badge(): String = when (kind) {
        ScanKind.OK -> "%.1fs".format(latencyMs / 1000.0)
        ScanKind.TIMEOUT -> "timeout"
        ScanKind.CREDITS -> "no credits"
        ScanKind.THROTTLED -> "429"
        ScanKind.FAIL -> if (status > 0) "HTTP $status" else "failed"
        ScanKind.ERROR -> "unreachable"
    }
}

/**
 * Maps an HTTP result onto a [ScanKind]. Kept next to the type so the bridge's
 * answer and the UI's wording cannot drift apart.
 */
fun scanKindOf(ok: Boolean, status: Int, error: String?): ScanKind = when {
    ok -> ScanKind.OK
    status == 429 -> ScanKind.THROTTLED
    status == 402 -> ScanKind.CREDITS
    error != null && (error.contains("timeout", true) || error.contains("timed out", true) ||
        error.contains("deadline", true)) -> ScanKind.TIMEOUT
    status == 0 && !error.isNullOrBlank() -> ScanKind.ERROR
    status == 0 -> ScanKind.ERROR
    else -> ScanKind.FAIL
}
