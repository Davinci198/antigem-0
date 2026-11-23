package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker offers exactly the routes this DSH home declares, so the reader of
 * `cordis.patch.yml` and `.credentials.yaml` decides whether a chat can work at all.
 * These pin the shapes DSH actually writes, not a tidied-up version of them.
 */
class DshModelCatalogTest {

    /** The `llm-pi-ai` entry DSH writes into `/root/.dsh/profiles/sdk/cordis.patch.yml`. */
    private val sdkPatch = """
        # Your patch layer for this dsh profile, applied after every bundle layer:
        - id: agent-default-model
          name: "@deepseek-ai/dsh-agent-default-model"
          config:
            provider: nvidia
            model: nvidia/nemotron-3-ultra-550b-a55b
        - id: llm-pi-ai
          name: "@deepseek-ai/dsh-llm-pi-ai"
          config:
            providers:
              nvidia:
                apiKeyEnv: NVIDIA_API_KEY
              omniroute:
                apiKeyEnv: OMNIROUTE_API_KEY
                api: openai-completions
                baseURL: http://127.0.0.1:41027/v1
                models:
                  - id: xkiro/minimax/minimax-m2.1-highspeed:free
    """.trimIndent()

    private val credentials = """
        version: 1
        refs:
          NVIDIA_API_KEY: nvapi-SECRET
          DEEPSEEK_API_KEY: "sk-quoted"
        other:
          IGNORED: nope
    """.trimIndent()

    @Test
    fun `the providers and their keys come out of the profile patch`() {
        val providers = DshConfigParser.llmProviders(sdkPatch)

        assertEquals(listOf("nvidia", "omniroute"), providers.map { it.id })
        assertEquals("NVIDIA_API_KEY", providers[0].apiKeyEnv)
        // A preset provider names only its env var; the endpoint comes from the preset.
        assertNull(providers[0].baseUrl)
        assertEquals("https://integrate.api.nvidia.com/v1", DshConfigParser.preset("nvidia")?.baseUrl)
        assertEquals("http://127.0.0.1:41027/v1", providers[1].baseUrl)
        assertEquals(listOf("xkiro/minimax/minimax-m2.1-highspeed:free"), providers[1].models)
    }

    @Test
    fun `the harness default model is read from its own patch`() {
        assertEquals(
            DshRoute("nvidia", "nvidia/nemotron-3-ultra-550b-a55b"),
            DshConfigParser.defaultModel(sdkPatch),
        )
        assertNull(DshConfigParser.defaultModel("[]\n"))
    }

    @Test
    fun `credentials are keyed by the env var a provider asks for`() {
        val refs = DshConfigParser.credentialRefs(credentials)

        assertEquals("nvapi-SECRET", refs["NVIDIA_API_KEY"])
        assertEquals("sk-quoted", refs["DEEPSEEK_API_KEY"])
        // `other:` is not the refs block, so its lines are not credentials.
        assertTrue(refs.keys.none { it == "IGNORED" })
    }

    @Test
    fun `another entry's providers are not mixed in`() {
        val patch = """
            - id: llm-pi-ai
              config:
                providers:
                  groq:
                    apiKeyEnv: GROQ_API_KEY
            - id: some-other-plugin
              config:
                providers:
                  nvidia:
                    apiKeyEnv: NVIDIA_API_KEY
        """.trimIndent()

        assertEquals(listOf("groq"), DshConfigParser.llmProviders(patch).map { it.id })
    }

    @Test
    fun `an empty or absent entry yields no providers`() {
        assertTrue(DshConfigParser.llmProviders("[]\n").isEmpty())
        assertTrue(DshConfigParser.llmProviders("- id: llm-pi-ai\n  config: {}\n").isEmpty())
        assertTrue(DshConfigParser.credentialRefs("").isEmpty())
    }

    @Test
    fun `embedding and reranker ids never reach the picker`() {
        val ids = listOf(
            "nvidia/nemotron-3-ultra-550b-a55b",
            "nvidia/nv-embed-v1",
            "bigcode/starcoder2-15b",
            "nvidia/nemoretriever-parse",
            "moonshotai/kimi-k3.1-pro",
            "nvidia/nemotron-3-ultra-550b-a55b",
        )

        assertEquals(
            listOf("nvidia/nemotron-3-ultra-550b-a55b", "moonshotai/kimi-k3.1-pro"),
            DshChatModels.filter(ids),
        )
    }

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
}