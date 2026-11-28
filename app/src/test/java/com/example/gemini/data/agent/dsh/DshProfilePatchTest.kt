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
        // Everything the user had is still there, and in the order they had it.
        assertTrue(merged.indexOf("groq:") < merged.indexOf("nvidia:"))
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

    @Test
    fun `a preset provider is declared so the harness finds its adapter`() {
        // The sdk profile starts empty; without this entry every turn dies with
        // 'no adapter registered for provider "nvidia"'.
        val entry = DshProfilePatch.presetEntry("nvidia", "NVIDIA_API_KEY", model = "minimaxai/minimax-m3.1-flash")
        val declared = DshConfigParser.llmProviders("- id: llm-pi-ai\n  config:\n    providers:\n      nvidia:\n        apiKeyEnv: NVIDIA_API_KEY")
        assertEquals(listOf("nvidia"), declared.map { it.id })
        assertEquals("NVIDIA_API_KEY", declared.single().apiKeyEnv)
        // Without a completion budget the harness reserves the whole context window and compaction fails.
        assertTrue(entry.contains("maxTokens: ${DshProfilePatch.DEFAULT_MAX_TOKENS}"))
    }

    @Test
    fun `an empty patch gains the preset entry, and a declared one is left alone`() {
        val empty = DshProfilePatch.ensurePreset("[]\n", "nvidia", "NVIDIA_API_KEY", "minimaxai/minimax-m3.1-flash")
        assertEquals(listOf("nvidia"), DshConfigParser.llmProviders(empty).map { it.id })
        assertTrue(empty.contains("maxTokens: 8192"))

        val onDisk = """
            - id: llm-pi-ai
              config:
                providers:
                  nvidia:
                    apiKeyEnv: NVIDIA_API_KEY
        """.trimIndent()
        // Second start: the entry is already there, so the file must come back byte for byte.
        assertEquals(onDisk, DshProfilePatch.ensurePreset(onDisk, "nvidia", "NVIDIA_API_KEY", "minimaxai/minimax-m3.1-flash"))
        assertEquals("", DshProfilePatch.ensurePreset("", "", "NVIDIA_API_KEY"))
    }

    @Test
    fun `the placeholder above an entry is dropped, not left to break the yaml`() {
        // What the previous build wrote: DSH's comment header, the empty array, then the entry below it.
        val broken = """
            # Your patch layer for this dsh profile, applied after every bundle layer:
            []
            - id: llm-pi-ai
              config:
                providers:
                  nvidia:
                    apiKeyEnv: NVIDIA_API_KEY
        """.trimIndent()
        val fixed = DshProfilePatch.normalizePatch(broken)
        assertTrue("no bare [] left", fixed.lines().none { it.trim() == "[]" })
        assertEquals(listOf("nvidia"), DshConfigParser.llmProviders(fixed).map { it.id })
        // A file that is only the placeholder keeps it: dsh needs a top-level array to boot.
        assertEquals("[]\n", DshProfilePatch.normalizePatch("[]\n"))
    }

    @Test
    fun `an entry appended to the comment header does not leave the empty array behind`() {
        val header = "# Your patch layer for this dsh profile\n# a top-level YAML array\n[]\n"
        val next = DshProfilePatch.ensurePreset(header, "nvidia", "NVIDIA_API_KEY", "x/y")
        assertTrue(next.lines().none { it.trim() == "[]" })
        assertEquals(2, next.lines().count { it.trimStart().startsWith("#") })
        assertEquals(listOf("nvidia"), DshConfigParser.llmProviders(next).map { it.id })
    }

    @Test
    fun `a provider the harness does not know carries its api and endpoint`() {
        // Anything added through the harness' own Models page (a free-pi route, say) is invisible to the installed
        // catalogue, so the entry has to spell out the wire protocol.
        val entry = DshProfilePatch.presetEntry(
            provider = "inception",
            apiKeyEnv = "INCEPTION_API_KEY",
            model = "mercury-3",
            api = "openai-completions",
            baseUrl = "https://api.example.test/v1",
        )
        assertTrue(entry.contains("api: openai-completions"))
        assertTrue(entry.contains("baseURL: https://api.example.test/v1"))
        val parsed = DshConfigParser.llmProviders(entry).single()
        assertEquals("inception", parsed.id)
        assertEquals("openai-completions", parsed.api)
    }
}
