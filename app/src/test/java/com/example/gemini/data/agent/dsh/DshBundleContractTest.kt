package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The plugin's rows exist twice: in `dsh-plugins/dsh-antigem/cordis.patch.yml`, which is the bundle a harness could load
 * on its own, and in [DshProfilePatch.mcpClients], which is what the app writes into the sdk profile because a
 * config-only bundle cannot install itself. Two copies of the same table drift — that drift is exactly what made two
 * branches fight over the same file for a day.
 *
 * So the bundle is parsed as text here and compared entry by entry with what the app generates. If this fails, the
 * plugin and the app have stopped describing the same seven-plus-one servers.
 */
class DshBundleContractTest {

    private fun bundleFile(): File {
        val candidates = listOf(
            File("../dsh-plugins/dsh-antigem/cordis.patch.yml"),
            File("dsh-plugins/dsh-antigem/cordis.patch.yml"),
            File("../../dsh-plugins/dsh-antigem/cordis.patch.yml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("the dsh-antigem bundle is not where the test looks: ${candidates.map { it.absolutePath }}")
    }

    /** id → (route, timeout) for one of the two descriptions of the same table. */
    private fun rows(text: String): Map<String, Pair<String, Int>> {
        val path = Regex("""/([a-z_]+)['"`]*\s*$""")
        val rows = linkedMapOf<String, Pair<String, Int>>()
        var id: String? = null
        var route: String? = null
        var timeout = 0
        fun flush() {
            val i = id
            val r = route
            if (i != null && r != null) rows[i] = r to timeout
        }
        text.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("- id: antigem-mcp-") -> {
                    flush()
                    id = trimmed.removePrefix("- id: antigem-mcp-")
                    route = null
                    timeout = 0
                }
                trimmed.startsWith("url:") -> {
                    // Both shapes end the same way: `/route` then maybe a closing quote or a template backtick.
                    route = path.find(trimmed)?.groupValues?.get(1)
                }
                trimmed.startsWith("toolCallTimeoutMs:") ->
                    timeout = trimmed.substringAfter(":").trim().toIntOrNull() ?: 0
            }
        }
        flush()
        return rows
    }

    @Test
    fun `the bundle and the generated rows describe the same servers`() {
        val bundle = rows(bundleFile().readText())
        val generated = rows(DshProfilePatch.mcpClients(8767, "device-id", "bridge-key", null))

        assertEquals("the ids differ", bundle.keys.sorted(), generated.keys.sorted())
        assertEquals("what each row points at differs", bundle, generated)
    }

    @Test
    fun `the plugin's own tools are the first row`() {
        val generated = rows(DshProfilePatch.mcpClients(8767, "device-id", "bridge-key", null))
        assertEquals("antigem", generated.keys.first())
        // The parser keeps the route without its slash, which is what the two descriptions are compared on.
        assertEquals("antigem", generated["antigem"]!!.first)
    }

    @Test
    fun `a row carries the launch key and not only the stable device id`() {
        val text = DshProfilePatch.mcpClients(8767, "device-id", "bridge-key", null)
        assertEquals(8, text.split("X-Bridge-Token:").size - 1)
        assertEquals(8, text.split("X-Device-Id:").size - 1)
        // Without a key nothing changes: an older caller keeps writing what it always wrote.
        assertTrue(DshProfilePatch.mcpClients(8767, "device-id", null, null).contains("X-Device-Id: 'device-id'"))
        assertTrue(!DshProfilePatch.mcpClients(8767, "device-id", null, null).contains("X-Bridge-Token"))
    }

    @Test
    fun `a server switched off gets no row, and a missing port writes nothing at all`() {
        val only = rows(DshProfilePatch.mcpClients(8767, "d", "k", setOf("antigem", "theme")))
        assertEquals(listOf("antigem", "theme"), only.keys.toList())
        assertEquals("", DshProfilePatch.mcpClients(null, "d", "k", null))
        assertEquals("", DshProfilePatch.mcpClients(0, "d", "k", null))
    }
}
