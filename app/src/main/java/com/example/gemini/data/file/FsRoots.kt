package com.example.gemini.data.file

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import java.io.File

/**
 * Browsable roots for the folder manager, deliberately limited to two trees:
 *
 * - the app's own Termux home ([Kind.TERMUX]),
 * - shared storage and its common folders ([Kind.DEVICE]).
 *
 * ## Why only these two
 * Everything else on the device belongs to a different uid or to Android itself:
 * `/`, `/data`, `/data/user/0`, the standalone Termux app's private tree, other apps'
 * sandboxes. The file manager must never offer a way in — those paths are not user
 * files, and a listing of them is a security surface, not a feature. [isAllowed] is the
 * single gate every navigation goes through, so a breadcrumb, a persisted path from an
 * older build, or a pasted path cannot escape the two trees either.
 *
 * ## Why the device root is `/sdcard` and not `/storage/emulated/0`
 * Every listing is resolved by the IDE bridge, which runs *inside the app's proot rootfs*
 * (`--rootfs=…/files/ubuntu`) with shared storage bound at `/sdcard`. Inside that
 * namespace `/storage/emulated/0` is an empty stub: measured on device, `/sdcard`
 * returned 253 entries and `/storage/emulated/0` returned 1 unrelated folder. `/sdcard`
 * also exists on a normal Android host, so it is the only path valid in both
 * namespaces; [normalize] rewrites the host form for anything that still carries it.
 *
 * ## Why the home root is `/root` in bridge terms
 * The bridge answers `~` with the concrete path it resolves through `os.UserHomeDir()`,
 * which is `/root` inside the rootfs. Allowing the concrete form keeps the gate honest
 * after the first listing, when the UI no longer holds `~`.
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
        if (p == base) return true
        return p.startsWith(if (base.endsWith("/")) base else "$base/")
    }
}

object FsRoots {

    const val ID_TERMUX_HOME = "termux-home"
    const val ID_DEVICE = "device"
    const val ID_DOWNLOADS = "downloads"
    const val ID_DOCUMENTS = "documents"
    const val ID_DCIM = "dcim"

    /** Shared storage as the bridge sees it (see the class comment). */
    const val DEVICE_STORAGE = "/sdcard"

    /** Concrete home path the bridge resolves `~` to inside the proot rootfs. */
    const val BRIDGE_HOME = "/root"

    private val DEVICE_SUBFOLDERS = listOf(
        ID_DOWNLOADS to "Download",
        ID_DOCUMENTS to "Documents",
        ID_DCIM to "DCIM"
    )

    /** Shared storage path in bridge terms. */
    fun deviceStoragePath(): String = DEVICE_STORAGE

    /**
     * Home of the embedded Termux tree. The `termux` flavor runs its bridge without proot,
     * so `~` resolves to the real prefix home there and [BRIDGE_HOME] would be wrong.
     */
    fun homePath(context: Context): String =
        if (context.packageName == "com.termux") {
            File(context.filesDir, "home").absolutePath
        } else {
            BRIDGE_HOME
        }

    /**
     * Expands a `~` path to the concrete home the bridge resolves it to. [isAllowed] only
     * accepts concrete paths, so a caller holding a `~/…` shortcut must expand it first;
     * otherwise the file manager falls back to `~` and opens the wrong directory.
     */
    fun expandHome(context: Context, path: String): String {
        val p = path.trim()
        return when {
            p == "~" -> homePath(context)
            p.startsWith("~/") -> homePath(context).trimEnd('/') + p.removePrefix("~")
            else -> p
        }
    }

    /**
     * Rewrites the host form (`/storage/emulated/0/…`, `/storage/self/primary/…`) into the
     * `/sdcard` form the bridge resolves.
     */
    fun normalize(path: String): String {
        val p = path.trim()
        val prefixes = listOf("/storage/emulated/0", "/storage/self/primary")
        for (prefix in prefixes) {
            if (p == prefix) return DEVICE_STORAGE
            if (p.startsWith("$prefix/")) return DEVICE_STORAGE + p.removePrefix(prefix)
        }
        return p
    }

    /**
     * The navigation gate. A path is addressable only when it lies inside the Termux home
     * or shared storage; everything else (system directories, other apps' sandboxes, the
     * rootfs itself) is refused before any listing request is sent.
     */
    fun isAllowed(context: Context, path: String): Boolean {
        val p = normalize(path).trim()
        if (p.isEmpty()) return false
        if (p == "~") return true
        val trimmed = p.trimEnd('/')
        // The bridge lists `filepath.Clean(dir)`, so "/sdcard/../data" would pass a plain
        // prefix check and then be opened as /data. Refuse any parent segment instead of
        // relying on the cleaned result.
        if (trimmed.split('/').any { it == ".." }) return false
        val bases = listOf(homePath(context), DEVICE_STORAGE)
        return bases.any { base -> trimmed == base || trimmed.startsWith("$base/") }
    }

    /**
     * Whether shared storage is readable right now. Below Android 11 the manifest
     * permissions are enough; above them the user has to grant all-files access.
     */
    fun hasDeviceAccess(): Boolean = try {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
    } catch (e: Exception) {
        false
    }

    /** True for shared-storage paths, which are the only ones gated by all-files access. */
    fun isDevicePath(path: String): Boolean {
        val p = normalize(path).trim()
        if (p.isEmpty()) return false
        return p == DEVICE_STORAGE || p.startsWith("$DEVICE_STORAGE/")
    }

    /** Ordered roots for the location strip: the app's home first, then shared storage. */
    fun all(context: Context): List<FsRoot> {
        val roots = mutableListOf<FsRoot>()
        roots += FsRoot(ID_TERMUX_HOME, "Termux", "~", FsRoot.Kind.TERMUX)

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
    fun rootFor(context: Context, roots: List<FsRoot>, path: String): FsRoot? {
        val normalized = normalize(path)
        val home = homePath(context)
        // The home root is stored as "~" but the bridge answers with its concrete path, so
        // rewrite the concrete form back before matching chips.
        val effective = when {
            normalized == home -> "~"
            normalized.startsWith("$home/") -> "~" + normalized.removePrefix(home)
            else -> normalized
        }
        return roots.filter { it.contains(effective) }.maxByOrNull { it.path.length }
    }

    /** Intent that opens this app's all-files access screen. */
    fun accessSettingsIntent(context: Context): Intent = Intent(
        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Message shown when a navigation target falls outside the two allowed trees. */
    fun outsideRootsMessage(): String =
        "That folder is outside the file manager. Only this app's Termux home and shared " +
            "storage (Device) are browsable; system and other apps' folders stay closed."

    /**
     * Shown when the user asks for the standalone Termux app's files. They belong to a
     * different uid and are intentionally not reachable from here.
     */
    fun termuxAccessNote(): String =
        "The Termux app's private folder (/data/data/com.termux) belongs to another app, " +
            "so it cannot be opened from here without root — and this file manager is kept " +
            "away from system and other apps' folders on purpose.\n\n" +
            "Two ways to work with those files:\n" +
            "1. In Termux run `termux-setup-storage` once, then copy what you need into " +
            "shared storage: `cp -r ~/project /sdcard/Download/`. It appears under Device " +
            "→ Download.\n" +
            "2. Keep working inside this app's own Termux home (the \"Termux\" root above) " +
            "— it is a full environment with its own home and tools.\n\n" +
            "Files under shared storage are visible from both sides: Termux reaches them " +
            "at ~/storage/shared."
}
