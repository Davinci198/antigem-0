package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the extraction of the web URL from the ready line `dsh web` prints.
 * The real announcement shapes come from the published `@deepseek-ai/dsh-web-app`
 * 0.2.2-rc.5: `dsh web: <url>` with the launch token as a query parameter, plus
 * other `dsh web:` chatter that must never be mistaken for the URL.
 */
class DshWebServerTest {

    @Test
    fun `extracts the ready line the web app prints`() {
        val line = "dsh web: http://127.0.0.1:4010/?token=fpi_A-b_C123"
        assertEquals("http://127.0.0.1:4010/?token=fpi_A-b_C123", DshWebServer.extractAdvertisedUrl(line))
    }

    @Test
    fun `extracts an https url with a mount path and other query parameters`() {
        val line = "dsh web: https://dsh.example.com/app?theme=dark&token=abc123"
        assertEquals(
            "https://dsh.example.com/app?theme=dark&token=abc123",
            DshWebServer.extractAdvertisedUrl(line),
        )
    }

    @Test
    fun `accepts leading and trailing whitespace`() {
        val line = "  dsh web: http://127.0.0.1:4010/?token=abc  "
        assertEquals("http://127.0.0.1:4010/?token=abc", DshWebServer.extractAdvertisedUrl(line))
    }

    @Test
    fun `ignores other dsh web chatter`() {
        assertNull(
            DshWebServer.extractAdvertisedUrl(
                "dsh web: opening the default browser; pass --no-open to disable",
            ),
        )
        assertNull(
            DshWebServer.extractAdvertisedUrl(
                "dsh web: listening on 0.0.0.0 over plain HTTP; restrict this port to a trusted proxy or network",
            ),
        )
    }

    @Test
    fun `ignores unrelated output lines`() {
        assertNull(DshWebServer.extractAdvertisedUrl("node v24.19.0"))
        assertNull(DshWebServer.extractAdvertisedUrl("[dsh] rewrote hard-link publishing"))
        assertNull(DshWebServer.extractAdvertisedUrl(""))
    }

    @Test
    fun `rejects a url without the token`() {
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: http://127.0.0.1:4010/"))
    }

    @Test
    fun `rejects a blank token`() {
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: http://127.0.0.1:4010/?token="))
    }

    @Test
    fun `rejects a url whose token parameter has no value`() {
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: http://127.0.0.1:4010/?token"))
    }

    @Test
    fun `rejects non-http schemes`() {
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: ws://127.0.0.1:4010/?token=abc"))
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: ftp://dsh.example.com/?token=abc"))
    }

    @Test
    fun `rejects malformed urls`() {
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: not a url"))
        assertNull(DshWebServer.extractAdvertisedUrl("dsh web: "))
    }
}
