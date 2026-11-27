package com.example.gemini.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * theme_mcp: lets a model see, change and create the app's colours.
 *
 * Served by AndroidLocalBridgeServer at /theme. Everything goes through [ThemePrefs], the same place
 * Settings → Appearance → Accent & background writes, so a change a model makes shows up at once.
 *
 * The app has three colour inputs (accent, background, second accent) and the rest of the palette is derived; the
 * eight tokens are the escape hatch. A model that wants a coherent theme should set the three and read
 * [warnings] before going further.
 */
object ThemeMcp {

    const val INSTRUCTIONS = "Controls AntiGem's colours. theme_list shows the theme in force and every shipped theme; " +
        "theme_apply_preset applies one by name. To make a theme of your own: theme_tokens explains each colour, then " +
        "theme_set with accent, background, second (hex like #1E1E2E) and optionally tokens for the few surfaces you " +
        "want by hand. Read the readability warnings it returns and fix them; theme_reset goes back to the app palette."

    private fun prop(type: String, description: String) = JSONObject().apply {
        put("type", type)
        put("description", description)
    }

    private fun tool(name: String, description: String, required: List<String> = emptyList(), vararg props: Pair<String, JSONObject>) =
        JSONObject().apply {
            put("name", name)
            put("description", description)
            put("inputSchema", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
                if (required.isNotEmpty()) put("required", JSONArray(required))
            })
        }

    fun tools(): JSONArray = JSONArray().apply {
        put(tool("theme_list", "The theme in force (its colours and tokens) and every theme that ships with the app."))
        put(tool("theme_tokens", "Every colour that can be set by hand, in editor order, with what it paints."))
        put(tool("theme_get", "The resolved palette as hex, with the readability of body, secondary and card text."))
        put(
            tool(
                "theme_apply_preset", "Apply one of the shipped themes by name.", listOf("name"),
                "name" to prop("string", "Theme name, e.g. \"Nord\" or \"Sakura\". See theme_list.")
            )
        )
        put(
            tool(
                "theme_set", "Set the three colours and any tokens. Omitted values keep what is there now.",
                emptyList(),
                "accent" to prop("string", "Hex, e.g. #7C3AED"),
                "background" to prop("string", "Hex, e.g. #121214"),
                "second" to prop("string", "Hex for the second accent; omit to let it follow the accent"),
                "tokens" to JSONObject().apply {
                    put("type", "object")
                    put("description", "Token → hex, keys from theme_tokens. An empty string removes an override.")
                }
            )
        )
        put(tool("theme_reset", "Back to the app's own palette (Amber Night) and drop every token override."))
    }

    fun call(context: Context, name: String, args: JSONObject): JSONObject {
        ThemePrefs.init(context)
        return runCatching {
            when (name) {
                "theme_list" -> list()
                "theme_tokens" -> tokenList()
                "theme_get" -> get()
                "theme_apply_preset" -> applyPreset(args)
                "theme_set" -> set(args)
                "theme_reset" -> reset()
                else -> error("Unknown tool $name")
            }
        }.getOrElse { error("theme_mcp failed: ${it.message ?: it.javaClass.simpleName}") }
    }

    // ------------------------------------------------------------------ tools

    private fun list(): JSONObject {
        val body = JSONObject().apply {
            put("enabled", ThemePrefs.enabled)
            put("accent", ThemePrefs.accent.hex())
            put("background", ThemePrefs.background.hex())
            put("second", ThemePrefs.secondAccent?.hex() ?: JSONObject.NULL)
            put("tokens", JSONObject().apply {
                ThemePrefs.tokens().forEach { (k, v) -> put(k, v.hex()) }
            })
            put("themes", JSONArray().apply {
                colorPresets.forEach { preset ->
                    put(JSONObject().apply {
                        put("name", preset.label)
                        put("dark", preset.dark)
                        put("accent", preset.accent.hex())
                        put("background", preset.background.hex())
                        put("second", preset.second?.hex() ?: JSONObject.NULL)
                    })
                }
            })
            put("current_preset", matchingPreset()?.label ?: "Custom")
        }
        return text(body.toString(2))
    }

    private fun tokenList(): JSONObject {
        val body = JSONObject().apply {
            put("tokens", JSONArray().apply {
                ThemeToken.keys.forEach { key ->
                    put(JSONObject().apply {
                        put("key", key)
                        put("paints", ThemeToken.about(key))
                        put("current", ThemePrefs.token(key)?.hex() ?: JSONObject.NULL)
                    })
                }
            })
            put(
                "note",
                "accent, background and second are the inputs the palette is derived from; surface, surface_variant, " +
                    "outline, muted and code_bg are derived and can be overridden one by one."
            )
        }
        return text(body.toString(2))
    }

    private fun get(): JSONObject {
        val palette = ThemePrefs.palette() ?: ThemePrefs.previewPalette(
            ThemePrefs.accent,
            ThemePrefs.background,
            ThemePrefs.secondAccent,
            ThemePrefs.tokens()
        )
        val body = JSONObject().apply {
            put("enabled", ThemePrefs.enabled)
            put("accent", palette.accent.hex())
            put("second", palette.accentSoft.hex())
            put("background", palette.background.hex())
            put("surface", palette.surface.hex())
            put("surface_variant", palette.surfaceVariant.hex())
            put("outline", palette.outline.hex())
            put("muted", palette.muted.hex())
            put("code_bg", palette.codeBg.hex())
            put("on_surface", palette.darkColors.onSurface.hex())
            put("readability", JSONObject().apply {
                put("body_on_background", round(contrastRatio(palette.darkColors.onSurface, palette.background)))
                put("secondary_on_background", round(contrastRatio(palette.muted, palette.background)))
                put("body_on_surface", round(contrastRatio(palette.darkColors.onSurface, palette.surface)))
                put("button_text", round(contrastRatio(palette.accent.bestForeground(), palette.accent)))
            })
            put("warnings", warnings(palette))
        }
        return text(body.toString(2))
    }

    private fun applyPreset(args: JSONObject): JSONObject {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return error("Pass name. See theme_list for the shipped themes.")
        val preset = presetNamed(name) ?: return error("No theme named \"$name\". See theme_list.")
        ThemePrefs.applyPreset(preset)
        return get().also { it.put("applied", preset.label) }
    }

    private fun set(args: JSONObject): JSONObject {
        val accentText = args.optString("accent").trim()
        val backgroundText = args.optString("background").trim()
        val secondText = args.optString("second").trim()

        val accent = if (accentText.isEmpty()) ThemePrefs.accent else parseHexColor(accentText)
            ?: return error("accent must be a hex colour like #7C3AED")
        val background = if (backgroundText.isEmpty()) ThemePrefs.background else parseHexColor(backgroundText)
            ?: return error("background must be a hex colour like #121214")
        val second: Color? = when {
            secondText.isEmpty() -> ThemePrefs.secondAccent
            secondText.equals("none", ignoreCase = true) -> null
            else -> parseHexColor(secondText) ?: return error("second must be a hex colour like #4752C4")
        }

        val tokens = ThemePrefs.tokens().toMutableMap()
        val given = args.optJSONObject("tokens")
        if (given != null) {
            given.keys().forEach { key ->
                if (key !in ThemeToken.keys) return error("Unknown token \"$key\". See theme_tokens.")
                val raw = given.optString(key).trim()
                if (raw.isEmpty()) {
                    tokens.remove(key)
                } else {
                    tokens[key] = parseHexColor(raw) ?: return error("Token $key must be a hex colour or an empty string")
                }
            }
        }

        ThemePrefs.setTheme(accent, background, second, tokens)
        return get()
    }

    private fun reset(): JSONObject {
        ThemePrefs.reset()
        return text("Back to the app's own palette. Ask theme_list for what is in force now.")
    }

    // ------------------------------------------------------------------ helpers

    private fun warnings(palette: CustomPalette): JSONArray {
        val out = JSONArray()
        val body = contrastRatio(palette.darkColors.onSurface, palette.background)
        val muted = contrastRatio(palette.muted, palette.background)
        val onCard = contrastRatio(palette.darkColors.onSurface, palette.surface)
        if (body < 4.5f) out.put("Body text on the background is ${round(body)}:1, needs 4.5:1 — lighten on_surface or darken the background.")
        if (muted < 3.0f) out.put("Secondary text is ${round(muted)}:1, needs 3:1 — give muted more contrast against the background.")
        if (onCard < 4.5f) out.put("Body text on cards is ${round(onCard)}:1, needs 4.5:1 — surface is too close to the text colour.")
        if (contrastRatio(palette.accent, palette.background) < 1.6f) {
            out.put("The accent barely differs from the background — buttons will not stand out.")
        }
        return out
    }

    private fun matchingPreset(): ColorPreset? {
        val accent = ThemePrefs.accent
        val background = ThemePrefs.background
        return colorPresets.firstOrNull { it.accent == accent && it.background == background }
    }

    private fun Color.hex(): String = "#%06X".format(toArgb() and 0xFFFFFF)

    private fun round(value: Float): Double = (Math.round(value * 10f) / 10.0)

    private fun json(o: JSONObject) = text(o.toString(2))
    private fun text(message: String) = JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", message)))
    private fun error(message: String) = text(message).put("isError", true)
}
