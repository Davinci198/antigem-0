package com.example.gemini.data.agent.dsh

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Installs the `dsh-antigem` plugin into the harness profile the app boots.
 *
 * The rows in the sdk patch are what the session actually gets, and they work without this: they are the app's own MCP
 * servers, written with the port and the key of this launch. What this adds is the plugin as a *thing* — its manifest,
 * its locale, its docs and its icon in the harness's own plugin list — so the app is not the only place that can say what
 * it is.
 *
 * Files come from the APK's assets, which are built from `dsh-plugins/dsh-antigem` by the Gradle Sync task, so there is
 * one copy of the bundle in the repository and not two that drift. A marker file carries the version, and a matching
 * marker means the copy is skipped: this runs before every session.
 */
object DshPluginInstaller {

    private const val TAG = "DshPluginInstaller"
    const val ASSET_DIR = "dsh-antigem"
    private const val MARKER = ".antigem-plugin-version"

    /** The installed plugin's version, or null when it is not there. */
    fun installedVersion(context: Context): String? = runCatching {
        marker(context).readText().trim().takeIf { it.isNotBlank() }
    }.getOrNull()

    fun installed(context: Context): Boolean = installedVersion(context) != null

    /**
     * Copies the bundle into every profile the app starts, unless the marker already says [DshPluginVersion.VERSION].
     * Returns true when files were written.
     */
    fun install(context: Context, force: Boolean = false): Boolean {
        if (!force && installedVersion(context) == DshPluginVersion.VERSION) return false
        val profiles = listOf("sdk", "web")
            .map { File(root(context), DshGuestPaths.DSH_HOME.trimStart('/') + "/profiles/$it") }
            .filter { it.isDirectory }
        if (profiles.isEmpty()) {
            Log.i(TAG, "no harness profile on disk yet; the plugin waits for the first session")
            return false
        }
        var wrote = false
        profiles.forEach { profile ->
            val target = File(profile, "node_modules/@local/dsh-antigem")
            runCatching {
                if (target.exists() && !target.deleteRecursively()) error("cannot replace $target")
                copyAssets(context, ASSET_DIR, target)
                File(target, MARKER).writeText(DshPluginVersion.VERSION)
                wrote = true
                Log.i(TAG, "installed dsh-antigem ${DshPluginVersion.VERSION} into ${profile.name}")
            }.onFailure { Log.w(TAG, "could not install the plugin into ${profile.name}: ${it.message}") }
        }
        return wrote
    }

    private fun root(context: Context): File = File(context.filesDir, "ubuntu")

    private fun marker(context: Context): File {
        val profiles = File(root(context), DshGuestPaths.DSH_HOME.trimStart('/') + "/profiles")
        val sdk = File(profiles, "sdk/node_modules/@local/dsh-antigem/$MARKER")
        val web = File(profiles, "web/node_modules/@local/dsh-antigem/$MARKER")
        return if (sdk.isFile) sdk else web
    }

    private fun copyAssets(context: Context, from: String, to: File) {
        val assets = context.assets
        val children = assets.list(from).orEmpty()
        if (children.isEmpty()) {
            // A file, not a directory: assets.list returns nothing for both, so the stream decides.
            to.parentFile?.mkdirs()
            assets.open(from).use { input -> to.outputStream().use { input.copyTo(it) } }
            return
        }
        to.mkdirs()
        children.forEach { child -> copyAssets(context, "$from/$child", File(to, child)) }
    }
}

/** One place for the plugin's version: the bundle's manifest and the app must not disagree. */
object DshPluginVersion {
    const val VERSION = "1.1.0"
}
