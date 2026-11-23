package com.example.gemini.data.agent.dsh

import android.content.Context
import android.webkit.WebView

/**
 * Injects the mobile adaptation assets into the harness web UI.
 *
 * The harness GUI is a desktop layout: its composer is not pinned to the bottom of
 * the viewport, the summary bar under the composer overflows the right edge on a
 * narrow screen, and the floating controls end up on top of the message actions.
 * The three bundled assets (an early polyfill, a stylesheet and a script, all
 * written against the 0.2.x DOM and shipped by the DSH mobile build) fix that
 * without touching the harness itself.
 *
 * Every call is idempotent: the stylesheet is only added once, and both scripts
 * guard their own setup, so a navigation or a reload can run them again safely.
 */
class DshUiInjector(context: Context) {

    private val appContext = context.applicationContext

    private val polyfill: String by lazy { read("dsh/early-polyfill.js") }
    private val css: String by lazy { read("dsh/mobile-ng.css") }
    private val js: String by lazy { read("dsh/inject-ng.js") }

    /** Must run before the harness scripts, so it is injected on page start. */
    fun injectEarlyPolyfill(webView: WebView) {
        if (polyfill.isNotBlank()) webView.evaluateJavascript(polyfill, null)
    }

    /** Injected once the page has loaded; adds the stylesheet and the viewport meta. */
    fun inject(webView: WebView) {
        val script = """
            (function(){
              if(!document.getElementById('dsh-mobile-style')){
                var st=document.createElement('style');
                st.id='dsh-mobile-style';
                st.textContent=${jsString(css)};
                document.head.appendChild(st);
              }
              var content='width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no, viewport-fit=cover, interactive-widget=resizes-content';
              var v=document.querySelector('meta[name=viewport]');
              if(v){v.setAttribute('content',content);}
              else{var m=document.createElement('meta');m.name='viewport';m.content=content;document.head.appendChild(m);}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        if (js.isNotBlank()) webView.evaluateJavascript(js, null)
    }

    private fun read(name: String): String = try {
        appContext.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        // A missing asset only costs the mobile adaptation, never the screen.
        ""
    }

    /** Escapes a file into a JavaScript string literal. */
    private fun jsString(raw: String): String {
        val out = StringBuilder(raw.length + 16)
        out.append('"')
        for (c in raw) {
            when (c) {
                '\\' -> out.append("\\\\")
                '"' -> out.append("\\\"")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\u2028' -> out.append("\\u2028")
                '\u2029' -> out.append("\\u2029")
                else -> if (c < ' ') out.append(String.format("\\u%04x", c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }
}
