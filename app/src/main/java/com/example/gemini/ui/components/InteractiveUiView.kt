package com.example.gemini.ui.components

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.security.ExternalLinks
import com.example.gemini.data.security.LinkDecision
import com.example.gemini.data.security.LinkOrigin
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay

class InteractiveUiJsBridge(
    private val context: Context,
    private val onToast: (String) -> Unit
) {
    @JavascriptInterface
    fun showToast(message: String) {
        onToast(message)
    }

    @JavascriptInterface
    fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Interactive UI", text)
        clipboard.setPrimaryClip(clip)
        onToast("Copied to clipboard")
    }
}

@SuppressLint("SetJavaScriptEnabled")
/**
 * P8: where the screenshot button hands captured PNG bytes. Set by ChatScreen so the image lands
 * as an attachment in the input; null falls back to saving into Downloads.
 */
object ScreenshotSink {
    var sink: ((ByteArray) -> Unit)? = null
}

private fun performCapture(view: android.view.View): ByteArray? = try {
    val w = view.width.coerceAtLeast(1)
    val h = view.height.coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    view.draw(canvas)
    val out = java.io.ByteArrayOutputStream()
    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    bitmap.recycle()
    out.toByteArray()
} catch (_: Exception) {
    null
}

private fun saveToDownloads(context: Context, bytes: ByteArray) {
    try {
        val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = java.io.File(dir, "AntiGem-${System.currentTimeMillis()}.png")
        file.writeBytes(bytes)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
        Toast.makeText(context, "Screenshot saved to Downloads", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not save screenshot: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun InteractiveUiView(
    htmlCode: String,
    title: String = "Interactive App",
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val webRef = remember { mutableListOf<WebView?>() }
    val isDark = isAppInDarkTheme()
    var isFullscreen by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = App, 1 = Code
    var reloadKey by remember { mutableIntStateOf(0) }
    var isCopied by remember { mutableStateOf(false) }

    LaunchedEffect(isCopied) {
        if (isCopied) {
            delay(2000)
            isCopied = false
        }
    }

    val primaryHex = if (isDark) "#E07A5F" else "#D97757"
    val bgHex = if (isDark) "#1E1E1E" else "#F7F7F8"
    val textHex = if (isDark) "#ECECF1" else "#1A1A1A"
    val cardBgHex = if (isDark) "#2A2A2A" else "#FFFFFF"
    val borderHex = if (isDark) "#3A3A3A" else "#E5E5E5"

    val fullHtml = remember(htmlCode, isDark, reloadKey) {
        if (htmlCode.trimStart().startsWith("<!DOCTYPE", ignoreCase = true) ||
            htmlCode.trimStart().startsWith("<html", ignoreCase = true)
        ) {
            htmlCode
        } else {
            """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=3.0, user-scalable=yes">
                <style>
                    :root {
                        --primary: $primaryHex;
                        --bg: $bgHex;
                        --text: $textHex;
                        --card-bg: $cardBgHex;
                        --border: $borderHex;
                    }
                    * { box-sizing: border-box; margin: 0; padding: 0; }
                    body {
                        background-color: var(--bg);
                        color: var(--text);
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
                        padding: 12px;
                        display: flex;
                        flex-direction: column;
                        align-items: center;
                        justify-content: center;
                        min-height: 100vh;
                    }
                    button, input, select, textarea {
                        font-family: inherit;
                    }
                </style>
            </head>
            <body>
                $htmlCode
            </body>
            </html>
            """.trimIndent()
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.SmartButton,
                        contentDescription = null,
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(17.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Tab Selector: App vs Code
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedTab == 0) ClaudeTerracotta else Color.Transparent)
                                .clickable { selectedTab = 0 }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "App",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 0) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (selectedTab == 1) ClaudeTerracotta else Color.Transparent)
                                .clickable { selectedTab = 1 }
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "Code",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selectedTab == 1) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    // Reload Button
                    IconButton(
                        onClick = { reloadKey++ },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Restart App",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(15.dp)
                        )
                    }

                    // Fullscreen / Expand Button
                    IconButton(
                        onClick = { isFullscreen = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Open Fullscreen",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Screenshot Button (P8): capture the preview and send it back into the chat
                    IconButton(
                        onClick = {
                            val view = webRef.firstOrNull()
                            val bytes = view?.let { performCapture(it) }
                            if (bytes == null) {
                                Toast.makeText(context, "Nothing to capture yet", Toast.LENGTH_SHORT).show()
                                return@IconButton
                            }
                            val sink = ScreenshotSink.sink
                            if (sink != null) {
                                sink(bytes)
                                Toast.makeText(context, "Screenshot attached to chat", Toast.LENGTH_SHORT).show()
                            } else {
                                saveToDownloads(context, bytes)
                            }
                        },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoCamera,
                            contentDescription = "Capture screenshot",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Body Area
            if (selectedTab == 0) {
                // Interactive Web App Canvas
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 220.dp, max = 420.dp)
                ) {
                    key(reloadKey, isDark) {
                        // kept alive while the chat is open (ChatWebViewCache): scrolling back keeps the app's state
                        KeptWebView(
                            part = "app",
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    webRef.add(this)
                                    setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        loadWithOverviewMode = true
                                        useWideViewPort = true
                                        builtInZoomControls = false
                                        displayZoomControls = false
                                        cacheMode = WebSettings.LOAD_NO_CACHE
                                    }
                                    webChromeClient = WebChromeClient()
                                    webViewClient = object : WebViewClient() {
                                        // a dead renderer would otherwise crash the app
                                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                            ChatWebViewCache.onRendererGone(view)
                                            return true
                                        }

                                        // P4 egress: inside the preview, only http(s)/about/data load in place;
                                        // anything else is refused (never launched externally from generated HTML).
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                            val scheme = request?.url?.scheme?.lowercase() ?: return false
                                            if (scheme == "http" || scheme == "https" || scheme == "about" || scheme == "data") {
                                                return false
                                            }
                                            val raw = request.url.toString()
                                            if (ExternalLinks.policy.check(raw, LinkOrigin.ServerContent) !is LinkDecision.Refuse) {
                                                ExternalLinks.open(ctx, raw, LinkOrigin.ServerContent)
                                            }
                                            return true
                                        }
                                    }
                                    addJavascriptInterface(
                                        InteractiveUiJsBridge(ctx) { msg ->
                                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                        },
                                        "antiGemini"
                                    )
                                }
                            },
                            update = { view ->
                                // load only when the page changed (a kept view comes back with its page running)
                                val pageKey = "$reloadKey:$isDark:${fullHtml.hashCode()}:${fullHtml.length}"
                                if (view.tag != pageKey) {
                                    view.tag = pageKey
                                    view.loadDataWithBaseURL("https://antigravity.local/", fullHtml, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
                // Source Code Inspector
                Column(modifier = Modifier.fillMaxWidth()) {
                    CodeBlock(code = htmlCode, language = "html")
                }
            }
        }
    }

    // Fullscreen Immersive Dialog for Games & Large Apps
    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Fullscreen Top Bar
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .statusBarsPadding()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.SmartButton,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { reloadKey++ }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Restart App",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            IconButton(onClick = { isFullscreen = false }) {
                                Icon(
                                    imageVector = Icons.Default.FullscreenExit,
                                    contentDescription = "Exit Fullscreen",
                                    tint = ClaudeTerracotta
                                )
                            }
                        }
                    }

                    // Fullscreen WebView Canvas
                    Box(modifier = Modifier.fillMaxSize()) {
                        key(reloadKey, isDark) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                                        settings.apply {
                                            javaScriptEnabled = true
                                            domStorageEnabled = true
                                            loadWithOverviewMode = true
                                            useWideViewPort = true
                                            builtInZoomControls = true
                                            displayZoomControls = false
                                            cacheMode = WebSettings.LOAD_NO_CACHE
                                        }
                                        webChromeClient = WebChromeClient()
                                        webViewClient = WebViewClient()
                                        addJavascriptInterface(
                                            InteractiveUiJsBridge(ctx) { msg ->
                                                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                                            },
                                            "antiGemini"
                                        )
                                        loadDataWithBaseURL("https://antigravity.local/", fullHtml, "text/html", "UTF-8", null)
                                    }
                                },
                                update = { view ->
                                    view.loadDataWithBaseURL("https://antigravity.local/", fullHtml, "text/html", "UTF-8", null)
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
