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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gemini.R
import com.example.gemini.data.agent.dsh.DshUiInjector
import com.example.gemini.data.agent.dsh.DshWebServer
import com.example.gemini.data.agent.dsh.DshWebServerInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "DshWebScreen"

/**
 * Probe run after every page load: the harness answers a token-less or stale
 * request with a small 401 page whose text body is this sentence. That page is
 * a normal, successfully loaded page, so no error callback fires for it — it has
 * to be recognised from its text.
 */
private const val AUTH_PAGE_PROBE =
    "(document.body && document.body.innerText || '').slice(0, 200)"

/** How many times one visit may reload the page after landing on the 401 page. */
private const val MAX_AUTH_RELOADS = 3

/** How long `dsh web` may take to print its URL before the screen fails. */
private const val WEB_READY_TIMEOUT_MS = 120_000L

/**
 * What must survive a visit of the screen: the harness process handle and the
 * WebView that is showing its GUI.
 *
 * Both used to be dropped when the screen was hidden, which meant the page was
 * rebuilt and reloaded — a full boot of the harness GUI — every time the user
 * stepped over to the chat and back. Keeping the view alive makes switching
 * instant and leaves scroll position, open drawer and a running answer intact.
 */
private object DshWebRetained {
    var instance: DshWebServerInstance? = null

    private var webView: WebView? = null
    /** URL plus reload counter: a changed counter means the token has to be re-read. */
    private var webKey: String? = null

    /** The retained page, so the screen can pause it while it is out of sight. */
    fun page(): WebView? = webView

    fun attach(context: Context, url: String, key: String, onAuthPage: () -> Unit): WebView {
        val existing = webView
        if (existing != null && webKey == key) {
            Log.i(TAG, "reusing the loaded page (no reload)")
            (existing.parent as? ViewGroup)?.removeView(existing)
            return existing
        }
        existing?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) }; runCatching { it.destroy() } }
        Log.i(TAG, "loading the harness page: ${url.substringBefore('?')}")
        return buildDshWebView(context, url, onAuthPage).also {
            webView = it
            webKey = key
        }
    }

    /** Drops the page on request (a restart), so the next attach loads a fresh one. */
    fun dropPage() {
        webView?.let { runCatching { (it.parent as? ViewGroup)?.removeView(it) }; runCatching { it.destroy() } }
        webView = null
        webKey = null
    }
}

/**
 * Full-screen overlay that runs the DSH web profile inside the Ubuntu guest and
 * shows its GUI in a WebView.
 *
 * The `dsh web` process is owned by this screen and only by this screen: it is
 * spawned the first time [isVisible] turns true, and [DshWebServerInstance.stop]
 * runs on request (the close action), so no guest process can outlive the app.
 * Hiding the screen — the minimize action, or a switch to the chat — keeps the
 * process and the loaded page alive, so coming back is instant and lands on the
 * same scroll position instead of booting the harness GUI again.
 *
 * While the harness has not announced its URL yet, a small status line is shown;
 * once [DshWebServerInstance.url] carries the advertised URL (launch token
 * included), it is loaded in a plain WebView. The page itself gets no overlays of
 * any kind — the only chrome is the slim top bar above the WebView.
 */
@Composable
fun DshWebScreen(
    isVisible: Boolean,
    onClose: () -> Unit,
    onMinimize: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var instance by remember { mutableStateOf(DshWebRetained.instance?.takeIf { it.isAlive }) }
    var startFailed by remember { mutableStateOf(false) }
    var restartRequested by remember { mutableIntStateOf(0) }
    /** One free re-boot per visit: Android killing the harness must not leave a dead page. */
    var autoRestartUsed by remember { mutableStateOf(false) }
    /**
     * Bumped when the page turns out to be the harness 401 page, which recreates
     * the WebView against the freshest advertised URL. The launch token is
     * printed once per `dsh web` start and rotates whenever the process boots
     * again, so a page loaded from an older URL stops being authorised.
     */
    var authReloads by remember { mutableIntStateOf(0) }

    // The harness outlives this screen: [DshWebServer] owns one process and this
    // effect only attaches to it, so reopening the screen is instant and does not
    // spawn another `dsh web`. Restarting is the user's decision (the refresh
    // action in the top bar), never a side effect of closing the overlay.
    LaunchedEffect(isVisible, restartRequested) {
        if (!isVisible) return@LaunchedEffect
        // Opening the page is a decision to run the harness, whatever the last stop said.
        com.example.gemini.data.local.ContainerHold.release()
        startFailed = false
        // NonCancellable: leaving the screen while the harness boots used to cancel this
        // effect, drop the started instance, and leave a live harness nobody was attached to;
        // the page then never loaded until the next visit.
        val started = withContext(NonCancellable + Dispatchers.IO) {
            DshWebServer.start(context) { line -> Log.d(TAG, line) }
        }
        if (started == null) {
            startFailed = true
        } else {
            instance = started
            DshWebRetained.instance = started
        }
    }

    // A fresh visit may use the free restart again; a running one must not, or a
    // harness that keeps dying would be rebooted in a loop instead of reported.
    LaunchedEffect(isVisible) {
        if (isVisible) autoRestartUsed = false
    }

    // Leaving the screen keeps the process and the page alive on purpose: the view state is
    // kept by [DshWebRetained], so switching to the chat and back does not boot the GUI again.
    // The page itself is paused while it is out of sight: a harness GUI left rendering behind the
    // chat runs its timers and animations forever and eats the phone alive (the chat stopped
    // answering because of it), so pausing costs nothing and restoring is still instant.
    DisposableEffect(isVisible) {
        val page = DshWebRetained.page()
        if (isVisible) {
            page?.onResume()
            page?.resumeTimers()
        } else {
            page?.onPause()
            page?.pauseTimers()
        }
        onDispose {
            startFailed = false
            if (!isVisible) {
                page?.onPause()
                page?.pauseTimers()
            }
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
                DshWebServer.stopNow()
                instance = null
                startFailed = true
                return@LaunchedEffect
            }
            delay(500)
            waited += 500
        }
        if (instance === running) {
            DshWebServer.stopNow()
            instance = null
            startFailed = true
        }
    }

    // A harness that was serving and then vanished was killed from outside the app:
    // Motorola's battery care suspends this package and the system then stops it,
    // which takes the guest Node process with it. Booting it again is the honest
    // answer (the page is still on screen and the user expects a chat), and it costs
    // one restart attempt — a harness that keeps dying still ends up on the message.
    LaunchedEffect(instance) {
        val running = instance ?: return@LaunchedEffect
        while (running.isAlive) delay(1_000)
        if (instance !== running) return@LaunchedEffect
        DshWebServer.stopNow()
        instance = null
        if (running.url.value != null && !autoRestartUsed) {
            // A stop from Android Development is deliberate: do not undo it seconds later.
            if (com.example.gemini.data.local.ContainerHold.recentlyStopped()) {
                Log.i(TAG, "the harness is gone and the container was just stopped on purpose; leaving it down")
                startFailed = true
                return@LaunchedEffect
            }
            Log.w(TAG, "the harness was running and is gone; restarting it once")
            autoRestartUsed = true
            restartRequested++
        } else {
            startFailed = true
        }
    }

    val idleUrlFlow = remember { MutableStateFlow<String?>(null) }
    val url by (instance?.url ?: idleUrlFlow).collectAsState()

    // A new advertised URL is authoritative: it carries the current token, so the
    // 401-reload budget starts over.
    LaunchedEffect(url) {
        authReloads = 0
    }

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
                    text = stringResource(R.string.dsh_web_title),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                )
                val prefs = remember { com.example.gemini.data.agent.dsh.DshPreferences(context) }
                val fullAccess by prefs.fullAccess.collectAsState()
                IconButton(onClick = {
                    // A running harness keeps the mode it booted with, so this is a setting for the next start rather
                    // than a live change; the restart button sits one icon away.
                    prefs.setFullAccess(!fullAccess)
                    com.example.gemini.ui.components.AppToastHelper.showToast(
                        if (fullAccess) {
                            "Workspace-only again. Restart the harness for it to apply."
                        } else {
                            "Full access on. Restart the harness for it to apply."
                        },
                        com.example.gemini.ui.components.ChatToastType.INFO
                    )
                }) {
                    Icon(
                        imageVector = if (fullAccess) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
                        contentDescription = if (fullAccess) "Full access on" else "Workspace only",
                        tint = if (fullAccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onMinimize) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.dsh_web_minimize),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = {
                    DshWebServer.stopNow()
                    DshWebRetained.instance = null
                    DshWebRetained.dropPage()
                    instance = null
                    startFailed = false
                    restartRequested++
                }) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.dsh_web_restart),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.dsh_web_close),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        val currentUrl = url
        when {
            currentUrl != null -> {
                // Recreate the WebView when the advertised URL changes, and also when
                // a load landed on the harness 401 page: that reload has to re-read the
                // freshest URL (its launch token), which only a fresh page load does.
                key(currentUrl, authReloads) {
                    AndroidView(
                        factory = { ctx ->
                            DshWebRetained.attach(
                                context = ctx,
                                url = currentUrl,
                                key = "$currentUrl#$authReloads",
                                onAuthPage = {
                                    if (authReloads < MAX_AUTH_RELOADS) {
                                        Log.w(TAG, "the page is the harness 401 page; reloading with the current token")
                                        authReloads++
                                    } else {
                                        Log.w(TAG, "still unauthorised after $MAX_AUTH_RELOADS reloads")
                                    }
                                }
                            )
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            startFailed -> {
                // Checked once per failure rather than per frame: it hits the filesystem.
                val payloadMissing = remember(startFailed, restartRequested) {
                    !DshWebServer.available(context)
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(
                                // A missing payload is a different story from a harness that died: it needs an
                                // install, not a restart, and the install is already running.
                                if (payloadMissing) R.string.dsh_web_missing_message else R.string.dsh_web_stopped_message
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(Modifier.height(12.dp))
                        // A dead harness is otherwise only revivable from the top bar, which is easy to miss.
                        OutlinedButton(
                            onClick = {
                                autoRestartUsed = false
                                restartRequested++
                            }
                        ) { Text(stringResource(R.string.dsh_web_retry)) }
                    }
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
                            text = stringResource(R.string.dsh_web_starting),
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
private fun buildDshWebView(
    context: Context,
    url: String,
    onAuthPage: () -> Unit
): WebView {
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
    val injector = DshUiInjector(context)
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
            Log.i(TAG, "page started: ${pageUrl?.substringBefore('?')}")
            // The polyfill has to be in place before the harness scripts run.
            injector.injectEarlyPolyfill(view)
        }

        override fun onPageFinished(view: WebView, pageUrl: String?) {
            Log.i(TAG, "page finished: ${pageUrl?.substringBefore('?')}")
            injector.inject(view)
            // A stale launch token is not an error: the harness answers 200 with a
            // small "authentication required" page. Read it and let the caller
            // reload against the freshest advertised URL instead of leaving the
            // user on a dead page.
            view.evaluateJavascript(AUTH_PAGE_PROBE) { text ->
                if (text != null && text.lowercase().contains("authentication required")) {
                    onAuthPage()
                }
            }
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
