package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `sdk` profile is what the in-app chat boots, and it must declare every provider
 * the `web` profile declares — otherwise the chat cannot resolve a route the harness
 * next door can. These pin both directions: the missing provider is added, and the
 * bytes the user owns are left alone.
 */
class DshProfilePatchTest {

    private val nvidia = DshConfigParser.DeclaredProvider(
        id = "nvidia",
        apiKeyEnv = "NVIDIA_API_KEY",
        api = null,
        baseUrl = null,
        models = emptyList(),
    )

    @Test
    fun `a provider missing from the sdk profile is added under providers`() {
        val patch = """
            - id: agent-default-model
              config:
                provider: nvidia
                model: nvidia/model
            - id: llm-pi-ai
              name: "@deepseek-ai/dsh-llm-pi-ai"
              config:
                providers:
                  groq:
                    apiKeyEnv: GROQ_API_KEY
        """.trimIndent()

        val merged = DshProfilePatch.ensureProviders(patch, listOf(nvidia))

        assertEquals(listOf("groq", "nvidia"), DshConfigParser.llmProviders(merged).map { it.id })
        // Everything the user had is still there.
        assertTrue(merged.contains("provider: nvidia"))
        assertTrue(merged.contains("apiKeyEnv: GROQ_API_KEY"))
    }

    @Test
    fun `an already declared provider leaves the file untouched`() {
        val patch = "- id: llm-pi-ai\n  config:\n    providers:\n      nvidia:\n        apiKeyEnv: NVIDIA_API_KEY\n"

        assertEquals(patch, DshProfilePatch.ensureProviders(patch, listOf(nvidia)))
    }

    @Test
    fun `a profile with no llm entry gains one without losing its entries`() {
        val patch = "- id: agent-default-model\n  config:\n    provider: nvidia\n    model: nvidia/model\n"

        val merged = DshProfilePatch.ensureProviders(patch, listOf(nvidia))

        assertTrue(merged.startsWith(patch))
        assertEquals(listOf("nvidia"), DshConfigParser.llmProviders(merged).map { it.id })
        assertEquals(DshRoute("nvidia", "nvidia/model"), DshConfigParser.defaultModel(merged))
    }

    @Test
    fun `an empty profile patch stays a top level array`() {
        val merged = DshProfilePatch.ensureProviders("[]\n", listOf(nvidia))

        assertTrue(merged.trimStart().startsWith("- id: llm-pi-ai"))
        assertFalse(merged.contains("[]"))
        assertEquals(1, DshConfigParser.llmProviders(merged).size)
    }

    @Test
    fun `a custom endpoint keeps its base url and models`() {
        val custom = DshConfigParser.DeclaredProvider(
            id = "myproxy",
            apiKeyEnv = "MYPROXY_API_KEY",
            api = "openai-responses",
            baseUrl = "http://127.0.0.1:9000/v1",
            models = listOf("acme/chat-1"),
        )

        val merged = DshProfilePatch.ensureProviders("- id: llm-pi-ai\n  config:\n    providers:\n", listOf(custom))
        val parsed = DshConfigParser.llmProviders(merged).single()

        assertEquals("myproxy", parsed.id)
        assertEquals("http://127.0.0.1:9000/v1", parsed.baseUrl)
        assertEquals("openai-responses", parsed.api)
        assertEquals(listOf("acme/chat-1"), parsed.models)
    }
}