package com.example.gemini.data.diagnostics

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records what the main thread was doing when it stopped answering.
 *
 * This phone ANRs the app while a PRoot guest boots (the system reports "Input
 * dispatching timed out" and Android drops the trace somewhere only root can
 * read), so the only way to see the culprit is from inside: this posts a task to
 * the main looper every half second and, when the previous one is still pending
 * after [BLOCKED_AFTER_MS], writes every thread's stack to logcat under one tag.
 *
 * The watchdog stays silent while the app is healthy: nothing is written unless
 * the main thread actually stalls, and at most one dump per [DUMP_COOLDOWN_MS] so
 * a long stall cannot flood the buffer.
 */
object MainThreadWatchdog {

    const val TAG = "MainThreadWatchdog"

    /** A stall this long is what Android itself calls "not responding". */
    private const val BLOCKED_AFTER_MS = 3_000L

    /** Never dump more than once per stall window. */
    private const val DUMP_COOLDOWN_MS = 15_000L

    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        val main = Handler(Looper.getMainLooper())
        val pending = AtomicBoolean(false)
        var lastDump = 0L
        Thread({
            while (true) {
                try {
                    Thread.sleep(500)
                    if (pending.get()) {
                        val now = System.currentTimeMillis()
                        if (now - lastDump > DUMP_COOLDOWN_MS) {
                            lastDump = now
                            dumpStacks()
                        }
                        continue
                    }
                    pending.set(true)
                    val posted = main.post {
                        pending.set(false)
                    }
                    if (!posted) pending.set(false)
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (e: Exception) {
                    Log.w(TAG, "watchdog loop failed: ${e.message}")
                }
            }
        }, "main-thread-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun dumpStacks() {
        val stacks = Thread.getAllStackTraces()
        val main = stacks.entries.firstOrNull { it.key.name == "main" }
        Log.w(TAG, "main thread blocked for more than ${BLOCKED_AFTER_MS}ms")
        main?.let { (thread, trace) ->
            Log.w(TAG, "--- ${thread.name} (${thread.state})")
            trace.take(40).forEach { Log.w(TAG, "    at $it") }
        }
        stacks.entries
            .filter { it.key.name != "main" && it.key.state == Thread.State.RUNNABLE }
            .take(4)
            .forEach { (thread, trace) ->
                Log.w(TAG, "--- ${thread.name} (${thread.state})")
                trace.take(12).forEach { Log.w(TAG, "    at $it") }
            }
    }
}
