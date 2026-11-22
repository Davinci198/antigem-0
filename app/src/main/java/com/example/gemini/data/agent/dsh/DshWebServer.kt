package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import com.example.gemini.data.local.LocalEnvironmentManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URI

/**
 * Runs the DSH web profile (`dsh web`) inside the Ubuntu container and surfaces
 * the URL the harness prints once its HTTP server is ready.
 *
 * `dsh web` announces itself with one line, `dsh web: <url>`, where the URL
 * carries the process launch token as its `token` query parameter; without that
 * token the web GUI answers 401, so [extractAdvertisedUrl] only accepts lines
 * whose URL is absolute http(s) and carries a non-blank token. The spawn passes
 * `--no-open --port 0`: no browser launch inside the guest, and the OS picks a
 * port so a second instance never fights the first.
 *
 * The guest process is spawned through [LocalEnvironmentManager.startGuestProcess],
 * which merges stderr into stdout, so one reader sees everything the harness
 * prints on either stream. [stop] kills the process; the web profile is meant
 * to be long-lived, so nothing here times it out.
 */
object DshWebServer {

    internal const val TAG = "DshWebServer"

    /** Prefix of the ready line `dsh web` prints (`dsh web: <url>?token=<launch token>`). */
    const val URL_LINE_PREFIX = "dsh web: "

    /** Query parameter carrying the process launch token. */
    const val TOKEN_QUERY_PARAM = "token"

    /**
     * Extracts the advertised web URL from one output line, token included.
     * Returns null for anything that is not a usable announcement: other
     * `dsh web:` chatter, URLs without the token, or non-http(s) schemes.
     */
    fun extractAdvertisedUrl(line: String): String? {
        val trimmed = line.trim()
        if (!trimmed.startsWith(URL_LINE_PREFIX)) return null
        val candidate = trimmed.substring(URL_LINE_PREFIX.length).trim()
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        val hasToken = uri.rawQuery?.split('&').orEmpty().any { parameter ->
            parameter.substringBefore('=', missingDelimiterValue = "") == TOKEN_QUERY_PARAM &&
                parameter.substringAfter('=', missingDelimiterValue = "").isNotBlank()
        }
        if (!hasToken) return null
        return candidate
    }

    /** Cheap preflight, same contract as [ProotDshHost.available]. */
    fun available(context: Context): Boolean {
        val rootfs = LocalEnvironmentManager.getUbuntuRootDir(context)
        return File(rootfs, DshGuestPaths.DSH_PAYLOAD).let { it.isFile || it.canExecute() } &&
            File(rootfs, DshGuestPaths.NODE_BIN).canExecute()
    }

    /**
     * Repairs a `cordis.patch.yml` an older build left empty for a route without
     * a custom provider. The harness refuses to boot on it ("must be a top-level
     * YAML array of loader patch entries"), and the file is ours, so rewriting it
     * as an empty array is safe and heals installs that are already broken.
     * Returns true when it rewrote the file.
     */
    fun repairPatchFile(rootfs: File): Boolean {
        val patch = File(rootfs, DshGuestPaths.DSH_HOME.removePrefix("/")).resolve("cordis.patch.yml")
        if (!patch.isFile) return false
        val body = runCatching { patch.readText() }.getOrNull()?.trim() ?: return false
        if (body.startsWith("-") || body.startsWith("[")) return false
        return runCatching { patch.writeText("[]\n") }.isSuccess
    }

    /** How long a harness nobody is looking at keeps its port and its Node VM. */
    private const val IDLE_STOP_MILLIS = 10 * 60_000L

    private val lock = Any()
    private var current: DshWebServerInstance? = null
    private var idleStop: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Returns a running web profile, spawning one only when there is none.
     *
     * A boot under proot costs tens of seconds (the harness loads every plugin
     * and probes its model context), so a screen that opened once keeps using
     * the same process: closing and reopening it renders immediately instead of
     * paying the boot again.
     */
    fun start(context: Context, log: (String) -> Unit = {}): DshWebServerInstance? = synchronized(lock) {
        idleStop?.cancel()
        idleStop = null
        current?.let { existing ->
            if (existing.isAlive) {
                log("[dsh-web] reusing the running web profile")
                return existing
            }
            current = null
        }
        startLocked(context, log)?.also { current = it }
    }

    /**
     * Called when the screen that asked for the harness goes away: the server
     * stays warm for [IDLE_STOP_MILLIS] so a reopen is instant, then stops.
     */
    fun release() {
        synchronized(lock) {
            idleStop?.cancel()
            idleStop = scope.launch {
                delay(IDLE_STOP_MILLIS)
                stopNow()
            }
        }
    }

    /** Stops the warm server now (the app is leaving the foreground, or the screen failed). */
    fun stopNow() {
        val running = synchronized(lock) {
            idleStop?.cancel()
            idleStop = null
            current.also { current = null }
        }
        running?.stop()
    }

    private fun startLocked(context: Context, log: (String) -> Unit): DshWebServerInstance? {
        if (!available(context)) {
            log("[dsh-web] DSH payload or Node runtime is missing; web profile not started")
            return null
        }
        if (repairPatchFile(LocalEnvironmentManager.getUbuntuRootDir(context))) {
            log("[dsh-web] rewrote an empty cordis.patch.yml as [] before booting")
        }
        return try {
            val process = LocalEnvironmentManager.startGuestProcess(
                context = context,
                guestArgv = listOf(DshGuestPaths.DSH_BIN, "web", "--no-open", "--port", "0"),
                guestCwd = "/root",
                environment = mapOf(
                    "NARB_DISABLE_NATIVE_CACHE" to "1",
                    "DSH_HOME" to DshGuestPaths.DSH_HOME,
                ),
            )
            log("[dsh-web] dsh web spawned; waiting for its URL...")
            DshWebServerInstance(process, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        } catch (e: IOException) {
            Log.w(TAG, "dsh web spawn failed: ${e.message}")
            log("[dsh-web] could not spawn dsh web: ${e.message}")
            null
        }
    }
}

/**
 * One running `dsh web` guest process. [url] holds the advertised URL once the
 * harness prints it, and stays at null until then (or forever if the harness
 * never announces, e.g. when its port is taken).
 */
class DshWebServerInstance internal constructor(
    private val process: Process,
    private val scope: CoroutineScope,
) {
    private val urlState = MutableStateFlow<String?>(null)

    /** The advertised web URL (token included), or null until it is printed. */
    val url: StateFlow<String?> = urlState.asStateFlow()

    val isAlive: Boolean get() = process.isAlive

    init {
        scope.launch {
            try {
                BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isBlank()) continue
                        Log.i(DshWebServer.TAG, "dsh web: $line")
                        DshWebServer.extractAdvertisedUrl(line)?.let { urlState.value = it }
                    }
                }
            } catch (e: IOException) {
                Log.w(DshWebServer.TAG, "dsh web stream closed: ${e.message}")
            }
        }
    }

    /** Stops the guest process and this instance's reader. */
    fun stop() {
        runCatching { process.outputStream.close() }
        runCatching { process.destroy() }
        if (process.isAlive) runCatching { process.destroyForcibly() }
        scope.cancel()
    }
}
