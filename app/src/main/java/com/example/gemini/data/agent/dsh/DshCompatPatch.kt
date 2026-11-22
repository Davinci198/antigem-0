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
    const val DSH_COMPAT_VERSION = "copyfile-excl-v1"

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
        PatchSite(
            relativePath = "@deepseek-ai/dsh-attachments/lib/index.js",
            importVariants = ATTACHMENT_IMPORT_VARIANTS,
            callVariants = ATTACHMENT_CALL_VARIANTS,
        ),
    )

    data class PatchSite(
        val relativePath: String,
        val importVariants: List<Pair<String, String>>,
        val callVariants: List<Pair<String, String>>,
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
            check(COPY_FILE_IMPORT.containsMatchIn(result)) { incompatible(site, version) }
            check(HARD_LINK_CALL_MARKERS.none { it in result }) { incompatible(site, version) }
            return result to Result.PATCHED
        }

        check(HARD_LINK_CALL_MARKERS.none { it in result }) { incompatible(site, version) }
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

    private val COPY_FILE_IMPORT =
        Regex("""import \{[^}]*\bcopyFile\b[^}]*\} from "node:fs/promises";""")

    private val HARD_LINK_CALL_MARKERS = listOf("await link(", ".link(", "linkFile(")
}
