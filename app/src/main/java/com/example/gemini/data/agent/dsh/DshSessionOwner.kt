package com.example.gemini.data.agent.dsh

import java.util.concurrent.atomic.AtomicReference

/**
 * One harness session has one owner at a time, and the app has two surfaces that want it.
 *
 * The chat holds the process it booted (that is what makes a second message fast), and the web page boots its own
 * profile; whichever takes a conversation while the other still has it is told the session "is owned by another
 * client". So the two take turns: a surface about to open a session first asks the other to let go, through this
 * holder. It is a lambda rather than a reference to the backend because the backend belongs to a V
 * [androidx.lifecycle.ViewModel] and outlives neither the screen nor the process.
 */
object DshSessionOwner {

    private val release = AtomicReference<() -> Unit>({})
    private val refresh = AtomicReference<() -> Unit>({})

    /** Called by the chat backend when it is created, so the web page can ask it to let go. */
    fun register(action: () -> Unit) {
        release.set(action)
    }

    /** Asks whoever holds a session right now to close it. Safe to call when nothing is held. */
    fun releaseNow() {
        runCatching { release.get().invoke() }
    }

    /**
     * The opposite direction: a session started or renamed in the page is already on disk, so the chat list is rebuilt
     * from it. Registered separately because a page visit is not an app foreground and would otherwise stay invisible
     * until the next launch.
     */
    fun registerRefresh(action: () -> Unit) {
        refresh.set(action)
    }

    fun refreshNow() {
        runCatching { refresh.get().invoke() }
    }
}
