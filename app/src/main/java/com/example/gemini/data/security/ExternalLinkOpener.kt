package com.example.gemini.data.security

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Android side of the link policy: every URL the app did not author runs through
 * [ExternalLinkPolicy] before an ACTION_VIEW intent is launched. Refusals are logged,
 * never opened. AskFirst (http/https from agent/server content) opens directly for now;
 * the decision object is kept so a confirmation sheet can be added later.
 */
object ExternalLinks {

    val policy = ExternalLinkPolicy()

    fun open(context: Context, raw: String, origin: LinkOrigin): Boolean =
        when (val decision = policy.check(raw, origin)) {
            is LinkDecision.Open -> launch(context, decision.link.uri)
            is LinkDecision.AskFirst -> {
                if (decision.insecure) {
                    Log.w(TAG, "Opening insecure http link ($origin) to ${decision.shownHost}")
                }
                launch(context, decision.link.uri)
            }
            is LinkDecision.Refuse -> {
                Log.w(TAG, "Blocked external link (origin=$origin, ${decision.reason}): ${redact(raw)}")
                false
            }
        }

    private fun launch(context: Context, uri: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (_: Exception) {
        false
    }

    /** Never log credentials: strip any userinfo before echoing a refused URL. */
    private fun redact(raw: String): String =
        raw.replace(REDACT_USERINFO, "$1<redacted>@")

    private val REDACT_USERINFO = Regex("""(^[a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]+@""")

    private const val TAG = "ExternalLinks"
}
