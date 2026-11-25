package com.example.gemini.data.remote

import com.jcraft.jsch.Session

/**
 * The app's SSH connection to a remote server, which this build does not have.
 *
 * It exists because the GitHub screen was written for the build that can run an agent on a server: the same
 * code runs `gh` on the phone, and asks this object for a session only when the app is in server mode. Here
 * [controlSession] always answers null, so the server path reports "not connected" instead of running, and
 * nothing else has to know the difference.
 */
object RemoteServerManager {

    /** Null: there is no server connection in this build. */
    fun controlSession(): Session? = null

    /** Single-quoted for a POSIX shell. */
    fun q(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
