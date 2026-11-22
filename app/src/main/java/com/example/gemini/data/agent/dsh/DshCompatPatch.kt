package com.example.gemini.data.agent.dsh

/**
 * Android/PRoot compatibility rewrite for the DeepSeek Harness npm payload.
 *
 * DSH publishes session and attachment files atomically with a POSIX hard link
 * (`await link(tmp, finalPath)`). PRoot cannot create hard links between the
 * app's storage and the guest (`--link2symlink` turns them into dangling
 * symlinks), so the first session write fails and every later turn dies on the
 * same path. The fix is to publish with `copyFile(..., COPYFILE_EXCL)`, which
 * keeps DSH's no-clobber contract without a link syscall.
 *
 * The rewritten sites are release-specific: [importVariants] and
 * [callVariants] are matched by exact text, and a source that matches no known
 * release throws instead of silently shipping a runtime that will fail at
 * session time. Version marker: [DSH_COMPAT_VERSION].
 */
object DshCompatPatch {

    /** Bump when the rewrite changes, so installs re-patch after an update. */
    const val DSH_COMPAT_VERSION = "copyfile-excl-v2"

    /** Files inside `usr/local/lib/dsh/node_modules` that publish with a hard link. */
    val PATCHED_FILES: List<PatchSite> get() = listOf(
        PatchSite(
            relativePath = "@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
            importVariants = SESSION_IMPORT_VARIANTS,
            callVariants = SESSION_CALL_VARIANTS,
        ),
        PatchSite(
            relativePath = "@deepseek-ai/dsh-fs-local/lib/index.js",
            importVariants = FS_LOCAL_IMPORT_VARIANTS,
            callVariants = FS_LOCAL_CALL_VARIANTS,
        ),
        // 0.2.0 published attachments as its own package; 0.2.2 folded it away, so
        // the file is simply absent on newer installs and this site skips itself.
        PatchSite(
            relativePath = "@deepseek-ai/dsh-attachments/lib/index.js",
            importVariants = ATTACHMENT_IMPORT_VARIANTS,
            callVariants = ATTACHMENT_CALL_VARIANTS,
        ),
        // The free-pi plugin writes its install key with a sync hard link, which
        // PRoot cannot create either. It installs under the web profile's own
        // node_modules, so this path is relative to that root, not to the DSH
        // payload's node_modules.
        PatchSite(
            relativePath = "dsh-free-pi-official/index.js",
            importVariants = FREEPI_IMPORT_VARIANTS,
            callVariants = FREEPI_CALL_VARIANTS,
            patchedImport = COPY_FILE_SYNC_IMPORT,
            guestRoot = FREEPI_PLUGIN_ROOT,
        ),
    )

    /** Where the DSH payload's own packages live inside the guest. */
    const val DSH_PAYLOAD_ROOT = "/usr/local/lib/dsh/node_modules"

    /** Where the web profile keeps the free-pi plugin. */
    const val FREEPI_PLUGIN_ROOT = "/root/.dsh/profiles/web/node_modules"

    data class PatchSite(
        val relativePath: String,
        val importVariants: List<Pair<String, String>>,
        val callVariants: List<Pair<String, String>>,
        /** Import that must remain after the rewrite; defaults to the promises copyFile import. */
        val patchedImport: Regex? = null,
        /** Guest directory the relative path is resolved against. */
        val guestRoot: String = DSH_PAYLOAD_ROOT,
    )

    /** Outcome of rewriting one file. */
    enum class Result {
        /** Source had an unpatched link call and now uses copyFile. */
        PATCHED,

        /** Source already publishes with copyFile; nothing to do. */
        ALREADY_PATCHED,
    }

    class IncompatibleReleaseException(message: String) : IllegalStateException(message)

    /**
     * Rewrites [source] for [site]. Returns the patched source plus [Result].
     *
     * @throws IncompatibleReleaseException when the source has no known shape.
     */
    fun patch(site: PatchSite, source: String, version: String): Pair<String, Result> {
        var result = source

        if (site.callVariants.any { (before, _) -> before in result }) {
            val (importBefore, importAfter) = site.importVariants
                .firstOrNull { (before, _) -> before in result }
                ?: throw IncompatibleReleaseException(incompatible(site, version))
            result = result.replace(importBefore, importAfter)
            site.callVariants.forEach { (before, after) -> result = result.replace(before, after) }
            if (!(site.patchedImport ?: COPY_FILE_IMPORT).containsMatchIn(result)) throw IncompatibleReleaseException(incompatible(site, version))
            if (hasHardLinkCall(result)) throw IncompatibleReleaseException(incompatible(site, version))
            return result to Result.PATCHED
        }

        // No known publication call left. It is only "already patched" if it
        // carries our own rewrite; otherwise the release publishes some other way
        // and we cannot promise a working session, so fail loudly.
        if (hasHardLinkCall(result)) throw IncompatibleReleaseException(incompatible(site, version))
        if (!(site.patchedImport ?: COPY_FILE_IMPORT).containsMatchIn(result)) throw IncompatibleReleaseException(incompatible(site, version))
        return source to Result.ALREADY_PATCHED
    }

    private fun incompatible(site: PatchSite, version: String) =
        "DeepSeek Harness $version changed ${site.relativePath}: no known hard-link " +
            "publication site. Refusing to install a runtime that fails at session time."

    // --- per-release text forms -------------------------------------------

    private val SESSION_IMPORT_VARIANTS = listOf(
        "import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";" to
            "import { copyFile, link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
        "import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";" to
            "import { copyFile, link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from \"node:fs/promises\";",
    )

    private val SESSION_CALL_VARIANTS = listOf(
        "await link(tmp, finalPath);" to "await copyFile(tmp, finalPath, 1);",
        // 0.2.2 stages the next session file through the fs seam.
        "await internals.fs.link(staged, currentPath);" to "await copyFile(staged, currentPath, 1);",
    )

    private val FS_LOCAL_IMPORT_VARIANTS = listOf(
        "import { chmod, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";" to
            "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, realpath, rename, rm, stat } from \"node:fs/promises\";",
        // 0.2.0-rc.2 resolves paths with the sync realpath.native from node:fs;
        // a second realpath binding from node:fs/promises would be a SyntaxError,
        // so this variant keeps it out of the promises import.
        "import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";" to
            "import { chmod, copyFile, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from \"node:fs/promises\";",
    )

    private val FS_LOCAL_CALL_VARIANTS = listOf(
        "await linkFile(tempPath, absolutePath);" to "await copyFile(tempPath, absolutePath, 1);",
    )

    private val ATTACHMENT_IMPORT_VARIANTS = listOf(
        "import { chmod, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";" to
            "import { chmod, copyFile, link, mkdir, open, readFile, rename, rm, unlink, writeFile } from \"node:fs/promises\";",
    )

    private val ATTACHMENT_CALL_VARIANTS = listOf(
        "await link(temporary, target);" to "await copyFile(temporary, target, 1);",
        "await link(source, target);" to "await copyFile(source, target, 1);",
        "await link(staged.path, target);" to "await copyFile(staged.path, target, 1);",
    )

    // The plugin imports from node:fs with single quotes and has no `fs` namespace
    // in scope, so the rewrite keeps the same no-clobber numeric flag (1 ===
    // COPYFILE_EXCL) used by the promises-based sites.
    private val FREEPI_IMPORT_VARIANTS = listOf(
        "import { chmodSync, linkSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'" to
            "import { chmodSync, copyFileSync, linkSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'",
    )

    private val FREEPI_CALL_VARIANTS = listOf(
        "linkSync(temp, file)" to "copyFileSync(temp, file, 1)",
    )

    private val COPY_FILE_IMPORT =
        Regex("""import \{[^}]*\bcopyFile\b[^}]*\} from "node:fs/promises";""")

    private val COPY_FILE_SYNC_IMPORT =
        Regex("""import \{[^}]*\bcopyFileSync\b[^}]*\} from 'node:fs';?""")

    private val HARD_LINK_CALL_MARKERS = listOf("await link(", ".link(", "linkFile(")

    /** `linkSync(` must not match `unlinkSync(`, so it is matched with a boundary, not a substring. */
    private val HARD_LINK_CALL_PATTERNS = listOf(Regex("""(?<![\w.$])linkSync\("""))

    private fun hasHardLinkCall(source: String): Boolean =
        HARD_LINK_CALL_MARKERS.any { it in source } ||
            HARD_LINK_CALL_PATTERNS.any { it.containsMatchIn(source) }
}
