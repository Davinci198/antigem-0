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
     * The patch entry that declares a provider DSH already knows: its adapter and endpoint come from the harness's own
     * table, so the entry only needs the name, the variable holding the key and — for the model in use — a completion
     * budget.
     *
     * [maxTokens] is not cosmetic: with it unset the harness reserves the model's whole context window for the
     * completion (262144 of 262144 for `minimaxai/minimax-m3.1-flash`), which leaves no room for the conversation and
     * makes compaction fail with "reserves ... leaving no message budget".
     */
    fun presetEntry(
        provider: String,
        apiKeyEnv: String,
        model: String? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        api: String? = null,
        baseUrl: String? = null,
    ): String =
        buildString {
            appendLine("- id: $ENTRY_ID")
            appendLine("  config:")
            appendLine("    providers:")
            appendLine("      $provider:")
            appendLine("        apiKeyEnv: $apiKeyEnv")
            // A provider the installed harness catalogue does not describe (anything the user added through the
            // harness' own Models page, e.g. a free-pi route) has to say which wire protocol it speaks, and where,
            // or the turn dies with 'needs an api; the installed catalog does not describe it'.
            if (!api.isNullOrBlank()) appendLine("        api: $api")
            if (!baseUrl.isNullOrBlank()) appendLine("        baseURL: ${yamlQuote(baseUrl)}")
            if (!model.isNullOrBlank()) {
                appendLine("        models:")
                appendLine("          - id: ${yamlQuote(model)}")
                appendLine("            maxTokens: $maxTokens")
            }
        }.trimEnd('\n')

    /** A completion budget small enough to leave a conversation in a 262k window and large enough to be useful. */
    const val DEFAULT_MAX_TOKENS = 8192

    /**
     * [existing] plus the entry for a preset provider — or [existing] untouched when it already declares that
     * provider. Never returns a blank string for a non-blank input: blanking this file is how the declaration the
     * previous turn wrote gets lost again.
     */
    /**
     * The `sdk` profile patch for exactly one route, keeping the file's comment header.
     *
     * The sdk profile belongs to the app: the harness UI only ever writes the `web` one, and a chat runs one route at a
     * time. Rewriting it instead of appending is what keeps a provider declaration from an older build (one that named
     * a key variable but no api) from surviving and failing every turn with "needs an api".
     */
    fun rewriteFor(
        existing: String,
        provider: String,
        apiKeyEnv: String,
        model: String?,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        api: String? = null,
        baseUrl: String? = null,
        mcpPort: Int? = null,
        deviceId: String? = null,
    ): String {
        val header = existing.lines().filter { it.trimStart().startsWith("#") }.joinToString("\n")
        val entry = presetEntry(provider, apiKeyEnv, model, maxTokens, api, baseUrl)
        val mcp = mcpClients(mcpPort, deviceId)
        return listOf(header, entry, mcp).filter { it.isNotBlank() }.joinToString("\n") + "\n"
    }

    /**
     * The app's own MCP servers, handed to the harness session.
     *
     * Same rows the `dsh-plugins/antigem-native` bundle declares, written out here with the port and the device id the
     * app actually has: the bridge binds whatever port is free, and its `X-Device-Id` check compares against the value
     * only the app can read. Without a port the rows are skipped entirely, so a session started without the bridge
     * still boots.
     */
    fun mcpClients(port: Int?, deviceId: String?): String {
        if (port == null || port <= 0) return ""
        val id = deviceId.orEmpty()
        val servers = listOf(
            Triple("browser", "/browser", 120_000),
            Triple("terminal", "/terminal", 120_000),
            Triple("google_flow", "/google_flow", 180_000),
            Triple("custom_plugin_developer", "/extension_developer", 120_000),
            Triple("android_automation", "/android", 180_000),
            Triple("notes", "/notes", 120_000),
            Triple("theme", "/theme", 120_000),
        )
        return buildString {
            appendLine("- insert:")
            servers.forEach { (name, path, timeout) ->
                appendLine("    - id: antigem-mcp-${name.replace('_', '-')}")
                appendLine("      name: '@deepseek-ai/dsh-mcp-client'")
                appendLine("      config:")
                appendLine("        serverName: $name")
                appendLine("        transport: streamable-http")
                appendLine("        url: 'http://127.0.0.1:$port$path'")
                appendLine("        headers:")
                appendLine("          X-Device-Id: '$id'")
                appendLine("        toolCallTimeoutMs: $timeout")
                appendLine("        startupTimeoutMs: 15000")
                // One server failing to start must not take the session down with it.
                appendLine("        failOnStartupError: false")
            }
        }.trimEnd('\n')
    }

    fun ensurePreset(
        existing: String,
        provider: String,
        apiKeyEnv: String,
        model: String? = null,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        api: String? = null,
        baseUrl: String? = null,
    ): String {
        if (provider.isBlank()) return existing
        val declared = DshConfigParser.llmProviders(existing).map { it.id }.toSet()
        if (provider in declared) return existing
        val entry = presetEntry(provider, apiKeyEnv, model, maxTokens, api, baseUrl)
        val cleaned = normalizePatch(existing)
        // The file DSH writes carries a comment header and an empty `[]` until something is added; appending below
        // that `[]` would produce a file with a flow array and a block item, which is not YAML at all.
        val body = cleaned.lines().filterNot { it.trimStart().startsWith("#") }.joinToString("\n").trim()
        val header = cleaned.lines().filter { it.trimStart().startsWith("#") }.joinToString("\n")
        return when {
            body.isBlank() || body == "[]" -> listOf(header, entry).filter { it.isNotBlank() }.joinToString("\n")
            else -> cleaned.trimEnd() + "\n" + entry
        }
    }

    /**
     * Drops a lone `[]` placeholder that sits above real entries.
     *
     * An earlier build wrote the placeholder and then appended the entry under it, and `[]` followed by a block
     * sequence item does not parse. A file whose only content is the placeholder is left alone.
     */
    fun normalizePatch(existing: String): String {
        if (existing.lines().none { it.trimStart().startsWith("- id:") }) return existing
        return existing.lines().filterNot { it.trim() == "[]" }.joinToString("\n")
    }

    private fun yamlQuote(value: String): String =
        if (value.matches(Regex("[A-Za-z0-9_./:@-]+"))) value else "'${value.replace("'", "''")}'"

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