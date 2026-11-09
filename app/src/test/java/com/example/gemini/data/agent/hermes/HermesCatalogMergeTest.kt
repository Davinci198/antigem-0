package com.example.gemini.data.agent.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two model pickers share one catalog: a Hermes preset that is the same
 * endpoint as an app custom provider (NVIDIA NIM vs nvidia) must appear once,
 * under the custom provider id both agents use.
 */
class HermesCatalogMergeTest {
    private fun preset(slug: String, name: String, vararg models: String) =
        HermesPresetProvider(slug, name, models.toList())

    private fun custom(id: String, name: String, vararg models: String) =
        HermesCustomProvider(
            id = id,
            name = name,
            baseUrl = "https://example.test/v1",
            keyHint = "…0000",
            models = models.map { HermesCustomModel(it, "") },
        )

    @Test
    fun `preset mirrored by a custom provider collapses into it`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("nvidia", "NVIDIA NIM", "a/one", "b/two", "c/three")),
            custom = listOf(custom("https", "nvidia", "a/one", "b/two")),
        )

        assertEquals(setOf("https"), entries.map { it.providerId }.toSet())
        assertEquals(setOf("nvidia"), entries.map { it.providerLabel }.toSet())
        assertEquals(listOf("a/one", "b/two", "c/three"), entries.map { it.modelId })
        assertTrue(entries.all { it.isCustom })
    }

    @Test
    fun `models only the preset knows survive under the shared id`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("nvidia", "NVIDIA NIM", "a/one", "z/extra")),
            custom = listOf(custom("https", "nvidia", "a/one")),
        )

        assertEquals(listOf("a/one", "z/extra"), entries.map { it.modelId })
        val extra = entries.single { it.modelId == "z/extra" }
        assertEquals("https", extra.providerId)
        assertEquals("nvidia", extra.providerLabel)
    }

    @Test
    fun `model shared by both catalogs is listed once`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("nvidia", "NVIDIA NIM", "a/one", "b/two")),
            custom = listOf(custom("https", "nvidia", "a/one", "b/two")),
        )

        assertEquals(entries.size, entries.map { it.key }.toSet().size)
    }

    @Test
    fun `unrelated providers stay in their own sections`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("custom:omniroute", "omniroute", "x/one", "y/two")),
            custom = listOf(custom("openrouter", "OpenRouter", "z/three")),
        )

        assertEquals(setOf("custom:omniroute", "openrouter"), entries.map { it.providerId }.toSet())
        assertFalse(entries.any { it.providerId == "openrouter" && it.modelId == "x/one" })
    }

    @Test
    fun `a small overlap alone does not merge two different providers`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("acme", "Acme AI", "shared/one", "shared/two", "acme/a", "acme/b")),
            custom = custom(
                "zenith", "Zenith",
                "shared/one", "shared/two", "zenith/1", "zenith/2", "zenith/3",
                "zenith/4", "zenith/5", "zenith/6", "zenith/7", "zenith/8",
            ).let(::listOf),
        )

        assertEquals(setOf("acme", "zenith"), entries.map { it.providerId }.toSet())
    }

    @Test
    fun `a generic custom name never steals a preset`() {
        val entries = mergeHermesCatalog(
            presets = listOf(preset("nvidia", "NVIDIA NIM", "a/one")),
            custom = listOf(custom("https", "api", "a/one")),
        )

        assertEquals(setOf("nvidia", "https"), entries.map { it.providerId }.toSet())
    }
}
