package com.example.gemini.data.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.example.gemini.data.local.LocalEnvironmentManager
import java.io.File

/**
 * Browsable starting points for the folder manager.
 *
 * ## Why this exists
 * Every listing in the file browser goes through the IDE bridge (`/api/fs/browse`),
 * and that handler does `os.ReadDir(filepath.Clean(dir))` on whatever path it is
 * given — it has no allow-list and no home jail. The app could therefore always read
 * shared storage; what was missing was (a) a place to start from that is not `~`, and
 * (b) a runtime check for the all-files grant, so a failed listing explains itself
 * instead of silently falling back to home.
 *
 * ## The two families
 * - [Kind.TERMUX] — the embedded Termux-like tree this app ships (`filesDir/home`,
 *   `filesDir/usr`). `~` is resolved by the bridge through `os.UserHomeDir()`.
 * - [Kind.DEVICE] — shared storage (`/storage/emulated/0` and its common subfolders).
 *   Readable only while MANAGE_EXTERNAL_STORAGE is effectively granted; the manifest
 *   declares it, but Android 11+ requires the settings screen to be opened.
 *
 * ## What this deliberately does NOT offer
 * The *standalone Termux app's* private data (`/data/data/com.termux/files/home`)
 * belongs to another uid. An app without root cannot read it, no matter which intent
 * it sends, so no dead root is shown. [termuxAccessNote] explains the two real ways to
 * get those files visible instead. If the build is the `termux` flavor
 * (`applicationId = com.termux`) or the device is rooted and access exists, the probe
 * in [all] simply finds the directory readable and adds it — nothing is hardcoded.
 */
data class FsRoot(
    val id: String,
    val label: String,
    val path: String,
    val kind: Kind
) {
    enum class Kind { TERMUX, DEVICE }

    /** True when [path] is this root or lives inside it. */
    fun contains(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return false
        val base = this.path.trim()
        return p == base || p.startsWith(if (base.endsWith("/")) base else "$base/")
    }
}

object FsRoots {

    const val ID_TERMUX_HOME = "termux-home"
    const val ID_TERMUX_USR = "termux-usr"
    const val ID_TERMUX_APP = "termux-app"
    const val ID_DEVICE = "device"
    const val ID_DOWNLOADS = "downloads"
    const val ID_DOCUMENTS = "documents"
    const val ID_DCIM = "dcim"

    /** Path of the standalone Termux app's home; only readable when this app owns it. */
    const val EXTERNAL_TERMUX_HOME = "/data/data/com.termux/files/home"

    private val DEVICE_SUBFOLDERS = listOf(
        ID_DOWNLOADS to "Download",
        ID_DOCUMENTS to "Documents",
        ID_DCIM to "DCIM"
    )

    /** Primary shared storage, with the legacy alias as a fallback for odd ROMs. */
    fun deviceStoragePath(): String = try {
        Environment.getExternalStorageDirectory()?.absolutePath?.takeIf { it.isNotBlank() }
            ?: "/storage/emulated/0"
    } catch (e: Exception) {
        "/storage/emulated/0"
    }

    /**
     * Whether shared storage is actually readable right now.
     * Below Android 11 the manifest permissions are enough.
     */
    fun hasDeviceAccess(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    } catch (e: Exception) {
        false
    }

    /** True for shared-storage paths, which are the only ones gated by all-files access. */
    fun isDevicePath(path: String): Boolean {
        val p = path.trim()
        if (p.isEmpty()) return false
        val storage = deviceStoragePath()
        return p == storage || p.startsWith("$storage/") || p.startsWith("/sdcard")
    }

    /**
     * Ordered roots for the location strip: the embedded Termux tree first (it is the
     * app's own sandbox and always present), then shared storage and its common folders.
     */
    fun all(context: Context): List<FsRoot> {
        val roots = mutableListOf<FsRoot>()

        roots += FsRoot(ID_TERMUX_HOME, "Termux", "~", FsRoot.Kind.TERMUX)

        val prefix = LocalEnvironmentManager.getPrefixDir(context)
        if (prefix.isDirectory) {
            roots += FsRoot(ID_TERMUX_USR, "usr", prefix.absolutePath, FsRoot.Kind.TERMUX)
        }

        // Probe, never assume: this is only true on the com.termux flavor or with root.
        val externalTermux = File(EXTERNAL_TERMUX_HOME)
        if (context.packageName != "com.termux" && externalTermux.isDirectory && externalTermux.canRead()) {
            roots += FsRoot(ID_TERMUX_APP, "Termux app", EXTERNAL_TERMUX_HOME, FsRoot.Kind.TERMUX)
        }

        val storage = File(deviceStoragePath())
        if (storage.isDirectory || hasDeviceAccess()) {
            roots += FsRoot(ID_DEVICE, "Device", storage.absolutePath, FsRoot.Kind.DEVICE)
            DEVICE_SUBFOLDERS.forEach { (id, name) ->
                val dir = File(storage, name)
                if (dir.isDirectory || hasDeviceAccess()) {
                    roots += FsRoot(id, name, dir.absolutePath, FsRoot.Kind.DEVICE)
                }
            }
        }

        return roots
    }

    /** The root that contains [path], if any — used to highlight the active chip. */
    fun rootFor(roots: List<FsRoot>, path: String): FsRoot? =
        roots.filter { it.contains(path) }.maxByOrNull { it.path.length }

    /** Intent that opens this app's all-files access screen. */
    fun accessSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Shown when the user asks for the standalone Termux app's files on a build that
     * cannot read them. Both options are real, root-free paths to the same content.
     */
    fun termuxAccessNote(): String =
        "The Termux app's private folder (/data/data/com.termux) belongs to another " +
            "app, so it cannot be opened from here without root.\n\n" +
            "Two ways to see those files:\n" +
            "1. In Termux run `termux-setup-storage` once, then copy what you need into " +
            "shared storage: `cp -r ~/project /sdcard/Download/`. It appears under Device " +
            "→ Download.\n" +
            "2. Keep working inside this app's own Termux tree (the \"Termux\" root above) — " +
            "it is a full environment with its own home and usr prefix.\n\n" +
            "Files under shared storage are visible from both sides: Termux reaches them " +
            "at ~/storage/shared."
}
