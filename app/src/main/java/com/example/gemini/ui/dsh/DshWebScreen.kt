package com.example.gemini.ui.dsh

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gemini.data.agent.dsh.DshWebServer
import com.example.gemini.data.agent.dsh.DshWebServerInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "DshWebScreen"

/** How long `dsh web` may take to print its URL before the screen fails. */
private const val WEB_READY_TIMEOUT_MS = 120_000L

/**
 * Full-screen overlay that runs the DSH web profile inside the Ubuntu guest and
 * shows its GUI in a WebView.
 *
 * The `dsh web` process is owned by this screen and only by this screen: it is
 * spawned the first time [isVisible] turns true, and [DshWebServerInstance.stop]
 * runs both when the screen is hidden again and when the composable leaves the
 * composition, so no guest process can outlive the screen. While the harness
 * has not announced its URL yet, a small status line is shown; once
 * [DshWebServerInstance.url] carries the advertised URL (launch token included),
 * it is loaded in a plain WebView. The page itself gets no overlays of any
 * kind — the only chrome is the slim top bar above the WebView.
 */
@Composable
fun DshWebScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var instance by remember { mutableStateOf<DshWebServerInstance?>(null) }
    var startFailed by remember { mutableStateOf(false) }

    // Spawn the harness only while the screen is visible. The finally block
    // covers the narrow race where the screen is hidden (or leaves composition)
    // while the spawn is still in flight: the assignment below never ran, so
    // the freshly started process would otherwise leak.
    LaunchedEffect(isVisible) {
        if (!isVisible || instance != null) return@LaunchedEffect
        startFailed = false
        var started: DshWebServerInstance? = null
        try {
            started = withContext(Dispatchers.IO) {
                DshWebServer.start(context) { line -> Log.d(TAG, line) }
            }
            if (started == null) {
                startFailed = true
            } else {
                instance = started
            }
        } finally {
            if (started != null && instance != started) started.stop()
        }
    }

    // Stop on hide and on leave; there is no long-lived mode for this screen.
    DisposableEffect(isVisible) {
        onDispose {
            instance?.stop()
            instance = null
            startFailed = false
        }
    }

    // A harness that boots but never announces its URL (a hang under proot is
    // real: the install verification did exactly that) must fail visibly
    // instead of spinning forever. A process that dies early (a bad
    // cordis.patch.yml aborts the boot) fails immediately.
    LaunchedEffect(instance) {
        val running = instance ?: return@LaunchedEffect
        var waited = 0L
        while (waited < WEB_READY_TIMEOUT_MS) {
            if (running.url.value != null) return@LaunchedEffect
            if (!running.isAlive) {
                instance = null
                startFailed = true
                return@LaunchedEffect
            }
            delay(500)
            waited += 500
        }
        if (instance === running) {
            running.stop()
            instance = null
            startFailed = true
        }
    }

    val idleUrlFlow = remember { MutableStateFlow<String?>(null) }
    val url by (instance?.url ?: idleUrlFlow).collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Slim top bar: the only chrome on this screen.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.background,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(48.dp)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "DeepSeek Harness",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        val currentUrl = url
        when {
            currentUrl != null -> {
                // Recreate the WebView only if the advertised URL ever changes;
                // the page area stays untouched while the harness is running.
                key(currentUrl) {
                    AndroidView(
                        factory = { ctx -> buildDshWebView(ctx, currentUrl) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            startFailed -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "DeepSeek Harness did not start: it is not installed in the container, or its web profile stopped answering.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
            else -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Starting DeepSeek Harness…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Builds the WebView that hosts the DSH web GUI and loads [url] (which already
 * carries the launch token). JavaScript, DOM storage and cookies are required by
 * the harness GUI and by its sign-in flow; the wide viewport lets its layout use
 * the full screen width.
 *
 * `setSupportMultipleWindows(false)` is the part that makes "Sign in with
 * GitHub" work: the free-pi card opens the OAuth page with `window.open`, and a
 * WebView that does not support popups shows a blank page instead. With this
 * off, the target window is loaded in this same view, cookies included.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun buildDshWebView(context: Context, url: String): WebView {
    val web = WebView(context)
    web.layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT
    )
    web.setBackgroundColor(android.graphics.Color.WHITE)
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        databaseEnabled = true
        useWideViewPort = true
        setSupportMultipleWindows(true)
        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(web, true)
    }
    web.webChromeClient = object : WebChromeClient() {
        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
            Log.d(TAG, "web console: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
            return true
        }

        /**
         * The FreePi card finishes sign-in in a popup ("finish in the browser tab
         * that opens"). Two wrong answers here were tried on device: a WebView
         * without this hook drops the window (blank page), and loading the popup's
         * URL into this view navigates the harness GUI away mid-flow, so the code
         * it shows disappears.
         *
         * The popup therefore goes to the phone's browser: GitHub's sign-in works
         * there (device code, existing session, 2FA — a WebView cannot offer the
         * passkey/password steps), while the harness keeps its own page and picks
         * the token up from its server.
         */
        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message
        ): Boolean {
            if (!isUserGesture) return false
            val popup = WebView(context)
            popup.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    popupView: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    val target = request.url.toString()
                    Log.d(TAG, "popup handoff to the browser: $target")
                    val opened = runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }.isSuccess
                    if (!opened) {
                        Log.w(TAG, "no browser for the sign-in popup; loading it here")
                        web.loadUrl(target)
                    }
                    popupView.destroy()
                    return true
                }
            }
            (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
            resultMsg.sendToTarget()
            return true
        }
    }
    web.webViewClient = object : WebViewClient() {
        // Keep every navigation inside this WebView so the launch token never
        // leaves the app for an external browser.
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            Log.d(TAG, "navigation: ${request.url}")
            return false
        }

        override fun onPageStarted(view: WebView, pageUrl: String?, favicon: android.graphics.Bitmap?) {
            Log.d(TAG, "page started: $pageUrl")
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            Log.d(TAG, "page finished: $pageUrl")
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            Log.w(TAG, "page error ${error.errorCode} on ${request.url}: ${error.description}")
        }
    }
    web.loadUrl(url)
    return web
}
