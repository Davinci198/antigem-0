package com.example.gemini.data.agent.dsh

/**
 * The provider half of a DSH profile patch.
 *
 * The `web` profile is where a user adds providers (through DSH's own settings UI),
 * while the in-app chat boots the `sdk` profile. Without this step a fresh install
 * has an `sdk` profile that declares no provider at all, so every chat route fails
 * with an unknown provider even though the harness is configured correctly next door.
 *
 * Only missing providers are added, in place, and the rest of the file is left
 * byte-for-byte alone: this file belongs to DSH and to the user.
 */
internal object DshProfilePatch {

    private const val ENTRY_ID = "llm-pi-ai"
    private const val ENTRY_NAME = "@deepseek-ai/dsh-llm-pi-ai"

    /**
     * [existing] with every provider in [providers] that it does not declare yet.
     *
     * Returns [existing] untouched when there is nothing to add, so the caller can
     * skip the write (and the install can stay idempotent).
     */
    fun ensureProviders(
        existing: String,
        providers: List<DshConfigParser.DeclaredProvider>,
    ): String {
        if (providers.isEmpty()) return existing
        val declared = DshConfigParser.llmProviders(existing).map { it.id }.toSet()
        val missing = providers.filter { it.id !in declared }.distinctBy { it.id }
        if (missing.isEmpty()) return existing

        val lines = existing.lines().toMutableList()
        val insertAt = providersInsertIndex(lines)
        if (insertAt == null) {
            val block = buildEntry(missing)
            val body = existing.trimEnd()
            return if (body.isBlank() || body.trim() == "[]") {
                block
            } else {
                // An existing entry list stays an entry list: append one entry.
                body + "\n" + block
            }
        }

        val indent = " ".repeat(insertAt.indent + 2)
        val block = missing.joinToString("\n") { provider ->
            buildString {
                append(indent).append(provider.id.trim()).append(":")
                append("\n")
                if (!provider.apiKeyEnv.isNullOrBlank()) {
                    append(indent).append("  apiKeyEnv: ").append(provider.apiKeyEnv)
                    append("\n")
                }
                if (!provider.api.isNullOrBlank() && provider.api != DshConfigParser.DEFAULT_API) {
                    append(indent).append("  api: ").append(provider.api)
                    append("\n")
                }
                if (!provider.baseUrl.isNullOrBlank()) {
                    append(indent).append("  baseURL: \"").append(provider.baseUrl).append("\"")
                    append("\n")
                }
                if (provider.models.isNotEmpty()) {
                    append(indent).append("  models:")
                    append("\n")
                    provider.models.forEach { append(indent).append("    - id: ").append(it).append("\n") }
                }
            }.trimEnd('\n')
        }
        lines.add(insertAt.line + 1, block)
        return lines.joinToString("\n")
    }

    /** Where a new provider block goes: after the last provider the entry already lists. */
    private fun providersInsertIndex(lines: List<String>): Insert? {
        var inEntry = false
        for (index in lines.indices) {
            val raw = lines[index]
            val line = raw.trim()
            val indent = raw.length - raw.trimStart().length
            if (indent == 0 && line.startsWith("-")) {
                val header = line.removePrefix("-").trim()
                inEntry = header.startsWith("id:") &&
                    header.substringAfter(':').trim().trim('"', '\'') == ENTRY_ID
                continue
            }
            if (!inEntry || line != "providers:") continue
            // Walk the block: everything deeper than `providers:` is a provider.
            var last = index
            for (next in index + 1 until lines.size) {
                val nextRaw = lines[next]
                val nextLine = nextRaw.trim()
                val nextIndent = nextRaw.length - nextRaw.trimStart().length
                if (nextLine.isNotEmpty() && nextIndent <= indent) break
                last = next
            }
            return Insert(last, indent)
        }
        return null
    }

    private fun buildEntry(providers: List<DshConfigParser.DeclaredProvider>): String = buildString {
        appendLine("- id: $ENTRY_ID")
        appendLine("  name: \"$ENTRY_NAME\"")
        appendLine("  config:")
        appendLine("    providers:")
        providers.forEach { provider ->
            appendLine("      ${provider.id.trim()}:")
            if (!provider.apiKeyEnv.isNullOrBlank()) appendLine("        apiKeyEnv: ${provider.apiKeyEnv}")
            if (!provider.api.isNullOrBlank() && provider.api != DshConfigParser.DEFAULT_API) {
                appendLine("        api: ${provider.api}")
            }
            if (!provider.baseUrl.isNullOrBlank()) appendLine("        baseURL: \"${provider.baseUrl}\"")
            if (provider.models.isNotEmpty()) {
                appendLine("        models:")
                provider.models.forEach { appendLine("          - id: $it") }
            }
        }
    }

    private data class Insert(val line: Int, val indent: Int)
}