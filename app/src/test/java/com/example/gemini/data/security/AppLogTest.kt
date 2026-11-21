package com.example.gemini.data.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun `token value is redacted`() {
        val out = AppLog.redact("fetch failed with token=abc123xyz")
        assertTrue(out.contains("token=<redacted>"))
        assertTrue(!out.contains("abc123xyz"))
    }

    @Test
    fun `authorization header is redacted`() {
        val out = AppLog.redact("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.signature")
        assertTrue(out.contains("Authorization: <redacted>"))
        assertTrue(!out.contains("eyJhbGciOiJIUzI1NiJ9"))
    }

    @Test
    fun `password assignment is redacted`() {
        val out = AppLog.redact("password = hunter2")
        assertTrue(out.contains("password = <redacted>"))
        assertTrue(!out.contains("hunter2"))
    }

    @Test
    fun `query string token is redacted`() {
        val out = AppLog.redact("url: https://api.example.com/auth?token=secretvalue&refresh=1")
        assertTrue(out.contains("?<redacted>"))
        assertTrue(!out.contains("secretvalue"))
    }

    @Test
    fun `plain text stays intact`() {
        val out = AppLog.redact("All good, no secrets here.")
        assertEquals("All good, no secrets here.", out)
    }

    @Test
    fun `key without separator is not redacted`() {
        val out = AppLog.redact("the monkey ate a banana; session management works")
        assertEquals("the monkey ate a banana; session management works", out)
    }
}
