package com.example.gemini.data.agent.dsh

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model picker's ids carry a whole DSH route, and the gateway answer decides
 * whether a provider can be offered at all. Both are one string each, so both are
 * worth pinning here rather than discovering in a chat that quietly fails.
 */
class DshModelCatalogTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `an id keeps the provider and the model apart`() {
        val id = DshModelKeys.of("nvidia", "nvidia/nemotron-3-ultra-550b-a55b")

        assertEquals("nvidia", DshModelKeys.provider(id, fallback = "deepseek-official"))
        assertEquals("nvidia/nemotron-3-ultra-550b-a55b", DshModelKeys.model(id))
    }

    @Test
    fun `a plain model id still resolves to a provider`() {
        assertEquals("anthropic", DshModelKeys.provider("claude-sonnet-4", fallback = "anthropic"))
        assertEquals("claude-sonnet-4", DshModelKeys.model("claude-sonnet-4"))
    }

    @Test
    fun `a label reads the model name, not the vendor path`() {
        assertEquals("Nemotron 3 Ultra 550b A55b", DshModelKeys.label("nvidia/nemotron-3-ultra-550b-a55b"))
        assertEquals("Deepseek V4 Flash", DshModelKeys.label("deepseek-v4-flash"))
    }

    @Test
    fun `a gateway answer is readable and its models survive`() {
        val text = """
            {"success":true,"provider":"nvidia","base_url":"http://127.0.0.1:38837/v1",
             "api":"openai-completions","models":["nvidia/nemotron-3-ultra-550b-a55b","moonshotai/kimi-k3.1-pro"],
             "model":"z-ai/glm-5.3-flash"}
        """.trimIndent()

        val info = json.decodeFromString<DshGatewayInfo>(text)

        assertTrue(info.success)
        assertEquals("http://127.0.0.1:38837/v1", info.base_url)
        assertEquals(2, info.models.size)
    }

    @Test
    fun `a disabled bridge answer is not a gateway`() {
        val info = json
            .decodeFromString<DshGatewayInfo>("""{"success":false,"disabled":true,"error":"Harness is turned off"}""")

        assertFalse(info.success)
        assertTrue(info.base_url.isBlank())
    }
}
