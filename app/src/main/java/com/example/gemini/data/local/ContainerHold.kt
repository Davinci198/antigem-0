package com.example.gemini.data.local

/**
 * Records that the user stopped the container on purpose.
 *
 * Everything that watches the guest revives it when it disappears — the harness web page restarts `dsh web` as
 * soon as its process dies, and a chat turn boots a new one. That is right when Android kills them, and wrong
 * right after "Stop the container": the button looked like it did nothing because the harness was back within
 * seconds. Those watchers ask [recentlyStopped] first and stay down until the user starts something.
 */
object ContainerHold {

    @Volatile
    private var stoppedAt: Long = 0L

    fun markStopped() {
        stoppedAt = System.currentTimeMillis()
    }

    /** The user started the container again (opening a screen that boots it): the hold is over. */
    fun release() {
        stoppedAt = 0L
    }

    /** True while a deliberate stop is still recent enough that a silent restart would be a surprise. */
    fun recentlyStopped(windowMs: Long = DEFAULT_WINDOW_MS): Boolean =
        stoppedAt > 0L && System.currentTimeMillis() - stoppedAt < windowMs

    const val DEFAULT_WINDOW_MS = 3 * 60_000L
}
