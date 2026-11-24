package com.example.gemini.data.agent.dsh

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Loopback proxy that lets the phone's browser show the harness page without an
 * authentication step.
 *
 * The harness protects its GUI with a session cookie that it sets with
 * `SameSite=Strict`. Chrome refuses to *store* such a cookie when the navigation
 * starts in another app (a cross-site context), so opening the advertised
 * `?token=` URL in Chrome lands on `dsh web authentication required` while the
 * same URL works in curl. Nothing in the URL can fix that: the browser simply
 * never keeps the cookie.
 *
 * This proxy is the answer. It listens on another loopback port, adds the
 * session cookie the app obtained itself to every request, and streams the
 * answer back. The browser therefore talks to `http://127.0.0.1:<proxy>/` with
 * no token, no cookie and no login, and the URL stays valid while the harness
 * restarts underneath (the proxy is rebuilt with the new session).
 *
 * One client connection carries one request: the upstream request is sent with
 * `Connection: close`, so the response can be streamed verbatim without parsing
 * bodies. WebSocket upgrades are forwarded untouched and then piped raw in both
 * directions, which is what the harness' live updates need.
 */
object HarnessBrowserProxy {

    private const val TAG = "HarnessBrowserProxy"
    private const val MAX_HEAD_BYTES = 32 * 1024

    private val lock = Any()
    private var server: ServerSocket? = null

    @Volatile
    private var upstreamPort: Int = 0

    @Volatile
    private var cookie: String? = null

    /** Port the browser should use, or 0 while the proxy is not running. */
    val port: Int
        get() = synchronized(lock) { server?.localPort ?: 0 }

    /**
     * Starts the proxy (once) and points it at [upstreamPort] with the session
     * [cookieHeader] value exactly as the harness sent it.
     *
     * @returns the browser-facing port, or 0 when no socket could be bound.
     */
    fun ensure(upstreamPort: Int, cookieHeader: String): Int = synchronized(lock) {
        this.upstreamPort = upstreamPort
        this.cookie = cookieHeader
        server?.let { return it.localPort }
        val created = try {
            ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            Log.w(TAG, "cannot bind the browser proxy: ${e.message}")
            return 0
        }
        server = created
        thread(isDaemon = true, name = "dsh-browser-proxy") {
            while (!created.isClosed) {
                val client = try {
                    created.accept()
                } catch (e: Exception) {
                    if (!created.isClosed) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                thread(isDaemon = true, name = "dsh-proxy-conn") { serve(client) }
            }
        }
        Log.i(TAG, "browser proxy on 127.0.0.1:${created.localPort} -> harness on $upstreamPort")
        created.localPort
    }

    fun stop() = synchronized(lock) {
        runCatching { server?.close() }
        server = null
        upstreamPort = 0
        cookie = null
    }

    private fun serve(client: Socket) {
        val target = upstreamPort
        val session = cookie
        if (target == 0 || session == null) {
            runCatching { client.close() }
            return
        }
        runCatching {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val requestHead = readHead(client.getInputStream()) ?: return@runCatching
            Socket("127.0.0.1", target).use { upstream ->
                upstream.tcpNoDelay = true
                val out = upstream.getOutputStream()
                out.write(rewrite(requestHead, target, session))
                out.flush()
                // The client -> upstream direction still has to flow (a POST body, a
                // WebSocket frame) while the answer streams back.
                thread(isDaemon = true, name = "dsh-proxy-up") {
                    runCatching { client.getInputStream().copyTo(out, 8 * 1024) }
                }
                upstream.getInputStream().copyTo(client.getOutputStream(), 8 * 1024)
            }
        }.onFailure { Log.w(TAG, "connection ended: ${it.message}") }
        runCatching { client.close() }
    }

    /** Reads the request line and headers, without consuming any body byte. */
    private fun readHead(input: InputStream): String? {
        val buffer = java.io.ByteArrayOutputStream(1024)
        var matched = 0
        val terminator = byteArrayOf(13, 10, 13, 10)
        while (buffer.size() < MAX_HEAD_BYTES) {
            val next = input.read()
            if (next == -1) return null
            buffer.write(next)
            matched = if (next.toByte() == terminator[matched]) matched + 1 else if (next.toByte() == terminator[0]) 1 else 0
            if (matched == terminator.size) return buffer.toString("ISO-8859-1")
        }
        return null
    }

    /**
     * Rebuilds the request head: keeps the request line, drops the headers that
     * belong to this hop, and adds the one that authenticates.
     */
    private fun rewrite(head: String, target: Int, session: String): ByteArray {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        val upgrade = lines.any { it.startsWith("Upgrade:", ignoreCase = true) }
        val kept = lines.drop(1).filterNot { line ->
            val name = line.substringBefore(':').trim()
            name.equals("Host", true) || name.equals("Cookie", true) || name.equals("Connection", true)
        }
        val rebuilt = StringBuilder()
        rebuilt.append(lines.first()).append("\r\n")
        rebuilt.append("Host: 127.0.0.1:").append(target).append("\r\n")
        if (!upgrade) rebuilt.append("Connection: close\r\n")
        rebuilt.append("Cookie: ").append(session).append("\r\n")
        kept.forEach { rebuilt.append(it).append("\r\n") }
        rebuilt.append("\r\n")
        return rebuilt.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /** Buffered copy that flushes as it goes, so a page renders while it downloads. */
    private fun InputStream.copyTo(output: OutputStream, bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }
}
