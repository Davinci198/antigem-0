package com.example.gemini.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 (ExternalLinkPolicy): port of OpenCode Mobile's LinksTest scenarios into AntiGem.
 * Tests the pure policy rules; no Android involved.
 */
class ExternalLinkPolicyTest {

    private val policy = ExternalLinkPolicy()

    private fun check(raw: String, origin: LinkOrigin = LinkOrigin.ServerContent) = policy.check(raw, origin)

    @Test
    fun `app authored https opens directly`() {
        val d = check("https://example.com/a?q=1#f", LinkOrigin.AppAuthored)
        assertTrue(d is LinkDecision.Open)
        assertEquals("https://example.com/a?q=1#f", (d as LinkDecision.Open).link.uri)
    }

    @Test
    fun `server content https asks first with shown host`() {
        val d = check("https://example.com/a")
        assertTrue(d is LinkDecision.AskFirst)
        with(d as LinkDecision.AskFirst) {
            assertEquals("example.com", shownHost)
            assertFalse(insecure)
        }
    }

    @Test
    fun `server content http asks first and is marked insecure`() {
        val d = check("http://example.com/a")
        assertTrue(d is LinkDecision.AskFirst)
        assertTrue((d as LinkDecision.AskFirst).insecure)
    }

    @Test
    fun `intent scheme is refused`() {
        assertEquals(LinkRefusal.IntentScheme, (check("intent://scan/#Intent;scheme=zxing;package=com.google.zxing.client.android;end") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `non-http scheme is refused`() {
        assertEquals(LinkRefusal.BadScheme, (check("ftp://example.com") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.BadScheme, (check("javascript:alert(1)") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.BadScheme, (check("file:///etc/passwd") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `credentials in url are refused`() {
        assertEquals(LinkRefusal.CredentialsInUrl, (check("https://user:pass@example.com/") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.CredentialsInUrl, (check("https://user@example.com/") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `percent-encoded authority is refused`() {
        assertEquals(LinkRefusal.NotAUrl, (check("https://exa%6Dple.com/") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `empty blank and control characters are refused`() {
        assertEquals(LinkRefusal.NotAUrl, (check("") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.NotAUrl, (check("   ") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.NotAUrl, (check("https://example.com/\n") as LinkDecision.Refuse).reason)
        assertEquals(LinkRefusal.NotAUrl, (check("https://exa\\mple.com") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `overlong url is refused`() {
        val long = "https://example.com/" + "a".repeat(2100)
        assertEquals(LinkRefusal.TooLong, (check(long) as LinkDecision.Refuse).reason)
    }

    @Test
    fun `missing host is refused`() {
        assertEquals(LinkRefusal.NotAUrl, (check("https://") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `non-url text is refused`() {
        assertEquals(LinkRefusal.NotAUrl, (check("just some text") as LinkDecision.Refuse).reason)
    }

    @Test
    fun `ipv6 host is parsed and bracketed`() {
        val d = check("https://[::1]:8080/a")
        assertTrue(d is LinkDecision.AskFirst)
        assertEquals("[::1]:8080", (d as LinkDecision.AskFirst).shownHost)
    }

    @Test
    fun `known claude sign-in page opens without asking`() {
        val d = policy.checkSignInPage("https://claude.com/cai/oauth/authorize")
        assertTrue(d is LinkDecision.Open)
    }

    @Test
    fun `claude sign-in page with extra parts still asks`() {
        val d = policy.checkSignInPage("https://claude.com/cai/oauth/authorize?client_id=x")
        assertTrue(d is LinkDecision.AskFirst)
    }

    @Test
    fun `other sign-in hosts ask`() {
        val d = policy.checkSignInPage("https://accounts.google.com/o/oauth2/v2/auth")
        assertTrue(d is LinkDecision.AskFirst)
    }

    @Test
    fun `urlparts rejects malformed ports`() {
        assertNull(UrlParts.parse("https://example.com:99999/"))
        assertNull(UrlParts.parse("https://example.com:abc/"))
    }

    @Test
    fun `urlparts strips userinfo`() {
        val parts = UrlParts.parse("https://user:pass@example.com:8443/x") ?: error("parse failed")
        assertEquals("user:pass", parts.userInfo)
        assertEquals("example.com", parts.host)
        assertEquals(8443, parts.port)
        assertEquals("/x", parts.path)
    }
}
