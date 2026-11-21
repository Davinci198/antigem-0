package com.example.gemini.data.security

/**
 * Port of OpenCode Mobile's `ExternalLinkPolicy` (MIT, © 2026 Eslam Sabry) into AntiGem.
 *
 * Only this class creates an [ApprovedLink]. The rules are a pure Kotlin port of
 * `safeExternalLinkUri` + `externalLinkHost`: only https (and http, so the caller can warn) is
 * accepted; every other scheme, embedded credentials (also percent-encoded), a missing host,
 * empty, unparseable or overlong values are refused before anything reaches the platform.
 */

enum class LinkOrigin {
    AppAuthored,
    ServerContent,
    Markdown,
    AgentCard,
    Terminal,
    Notification,
}

class ApprovedLink internal constructor(val uri: String, val origin: LinkOrigin)

sealed interface LinkDecision {
    data class Open(val link: ApprovedLink) : LinkDecision

    data class AskFirst(
        val link: ApprovedLink,
        val shownHost: String,
        val insecure: Boolean = false,
    ) : LinkDecision

    data class Refuse(val reason: LinkRefusal) : LinkDecision
}

enum class LinkRefusal {
    BadScheme,
    CredentialsInUrl,
    TooLong,
    NotAUrl,
    IntentScheme,
}

class ExternalLinkPolicy {

    fun check(raw: String, origin: LinkOrigin): LinkDecision {
        val text = raw.trim()
        if (text.isEmpty() || text.any { it.code <= 0x20 || it.code == 0x7f || it == '\\' })
            return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        if (text.length > MAX_LENGTH) return LinkDecision.Refuse(LinkRefusal.TooLong)
        val match = URL.matchEntire(text) ?: return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        val scheme = match.groupValues[1].lowercase()
        if (scheme == "intent") return LinkDecision.Refuse(LinkRefusal.IntentScheme)
        if (scheme !in setOf("http", "https")) return LinkDecision.Refuse(LinkRefusal.BadScheme)
        if (match.groupValues[2] != "//") return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        val authority = match.groupValues[3]
        if ('@' in authority) return LinkDecision.Refuse(LinkRefusal.CredentialsInUrl)
        if ('%' in authority) return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        val parts = UrlParts.parse(text) ?: return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        if (parts.host.isEmpty() || parts.host.any { it.isWhitespace() })
            return LinkDecision.Refuse(LinkRefusal.NotAUrl)
        val normalized = scheme + text.substring(match.groupValues[1].length)
        val link = ApprovedLink(normalized, origin)
        if (origin == LinkOrigin.AppAuthored) return LinkDecision.Open(link)
        val shown = UrlParts.bracketed(parts.host) + (parts.port?.let { ":$it" } ?: "")
        return LinkDecision.AskFirst(link, shown, insecure = scheme == "http")
    }

    /**
     * The exact verified Claude sign-in page (https, claude.com, /cai/oauth/authorize, no port,
     * user info or fragment) opens without a second sheet; any other address goes through [check].
     */
    fun checkSignInPage(raw: String): LinkDecision {
        val decision = check(raw, LinkOrigin.ServerContent)
        if (decision !is LinkDecision.AskFirst) return decision
        val parts = UrlParts.parse(raw.trim()) ?: return decision
        val verified =
            raw.length <= 8192 &&
                raw.none { it.code <= 0x20 || it.code == 0x7f || it == '\\' } &&
                parts.scheme == "https" &&
                parts.userInfo == null &&
                parts.port == null &&
                parts.fragment == null &&
                parts.host == "claude.com" &&
                parts.path == "/cai/oauth/authorize"
        return if (verified) LinkDecision.Open(decision.link) else decision
    }

    private companion object {
        const val MAX_LENGTH = 2048
        val URL = Regex("""^([a-zA-Z][a-zA-Z0-9+.-]*):(//)?([^/?#\s]*)([^\s]*)$""")
    }
}

/**
 * A small absolute-URL splitter (port of OpenCode Mobile's `UrlParts`). It never decodes or
 * normalises percent-escapes; it only finds the parts the trust rules need.
 */
internal class UrlParts(
    val scheme: String,
    val userInfo: String?,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
) {
    companion object {
        private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://""")

        fun bracketed(host: String): String = if (':' in host) "[$host]" else host

        fun parse(raw: String): UrlParts? {
            val s = raw.trim()
            val schemeMatch = SCHEME.find(s) ?: return null
            val scheme = schemeMatch.groupValues[1].lowercase()
            var rest = s.substring(schemeMatch.range.last + 1)
            val fragment = rest.substringAfter('#', missingDelimiterValue = "").takeIf { '#' in rest }
            rest = rest.substringBefore('#')
            val query = rest.substringAfter('?', missingDelimiterValue = "").takeIf { '?' in rest }
            rest = rest.substringBefore('?')
            val slash = rest.indexOf('/')
            val authority = if (slash < 0) rest else rest.substring(0, slash)
            val path = if (slash < 0) "" else rest.substring(slash)
            val at = authority.lastIndexOf('@')
            val userInfo = if (at >= 0) authority.substring(0, at) else null
            val hostPort = if (at >= 0) authority.substring(at + 1) else authority
            val host: String
            val portText: String?
            if (hostPort.startsWith("[")) {
                val close = hostPort.indexOf(']')
                if (close < 0) return null
                host = hostPort.substring(1, close)
                val after = hostPort.substring(close + 1)
                portText = if (after.isEmpty()) null else if (after.startsWith(":")) after.substring(1) else return null
            } else {
                val colon = hostPort.lastIndexOf(':')
                host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
                portText = if (colon >= 0) hostPort.substring(colon + 1) else null
                if (':' in host) return null
            }
            val port =
                when {
                    portText == null -> null
                    portText.isEmpty() -> null
                    portText.all(Char::isDigit) && portText.length <= 5 -> portText.toInt().takeIf { it in 0..65535 } ?: return null
                    else -> return null
                }
            return UrlParts(scheme, userInfo, host.lowercase(), port, path, query, fragment)
        }
    }
}
