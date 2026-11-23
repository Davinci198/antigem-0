package com.example.gemini.data.agent.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the proot compatibility rewrite: an unpatched hard-link publication is
 * rewritten to a no-clobber copy, an already-patched file is left untouched, and
 * a release whose source shape is unknown fails loudly instead of installing a
 * runtime that breaks on the first session write.
 */
class DshCompatPatchTest {

    private val sessionSite = DshCompatPatch.PATCHED_FILES
        .first { it.relativePath.contains("session-persistence") }

    @Test
    fun `rewrites link publication to copyFile with COPYFILE_EXCL`() {
        val source = """
            import { link, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
            export async function publish(tmp, finalPath) {
              await mkdir("/tmp", { recursive: true });
              await link(tmp, finalPath);
            }
        """.trimIndent()

        val (patched, result) = DshCompatPatch.patch(sessionSite, source, VERSION)

        assertEquals(DshCompatPatch.Result.PATCHED, result)
        assertTrue(patched.contains("copyFile(tmp, finalPath, 1);"))
        assertTrue(patched.contains("import { copyFile, link, mkdir"))
        assertTrue(!patched.contains("await link("))
    }

    @Test
    fun `rewrites the internals call shape from a later release`() {
        val source = """
            import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
            async function publish(internals, staged, currentPath) {
              await internals.fs.link(staged, currentPath);
            }
        """.trimIndent()

        val (patched, result) = DshCompatPatch.patch(sessionSite, source, VERSION)

        assertEquals(DshCompatPatch.Result.PATCHED, result)
        assertTrue(patched.contains("await copyFile(staged, currentPath, 1);"))
    }

    @Test
    fun `leaves an already patched file untouched`() {
        val source = """
            import { copyFile, link, mkdir } from "node:fs/promises";
            await copyFile(tmp, finalPath, 1);
        """.trimIndent()

        val (patched, result) = DshCompatPatch.patch(sessionSite, source, VERSION)

        assertEquals(DshCompatPatch.Result.ALREADY_PATCHED, result)
        assertEquals(source, patched)
    }

    @Test(expected = DshCompatPatch.IncompatibleReleaseException::class)
    fun `fails loudly when the release shape is unknown`() {
        val source = """
            import { publishAtomically } from "@deepseek-ai/fs-publish";
            await publishAtomically(tmp, finalPath);
        """.trimIndent()

        DshCompatPatch.patch(sessionSite, source, VERSION)
    }

    @Test(expected = DshCompatPatch.IncompatibleReleaseException::class)
    fun `fails when the file still links after the rewrite`() {
        val source = """
            import { link, mkdir } from "node:fs/promises";
            await link(tmp, finalPath);
            await link(other, otherFinal);
        """.trimIndent()

        // The second, unknown call site survives the rewrite, so the guard trips
        // instead of shipping a half-patched file.
        DshCompatPatch.patch(sessionSite, source, VERSION)
    }

    @Test
    fun `patches the shapes shipped in 0_2_2_rc_5`() {
        // Verbatim heads from the published tarballs, so a new release that moves
        // either publication fails here instead of on the user's first session.
        val session = """
            import { link, lstat, mkdir, mkdtemp, open, readFile, readdir, realpath, rm, stat, truncate } from "node:fs/promises";
            export async function writeStaged(internals, staged, currentPath, tmp, finalPath) {
              await internals.fs.link(staged, currentPath);
              await link(tmp, finalPath);
            }
        """.trimIndent()
        val fsLocal = """
            import { chmod, link, lstat, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";
            const linkFile = internals.linkFile ?? link;
            export async function write(tempPath, absolutePath) {
              await linkFile(tempPath, absolutePath);
            }
        """.trimIndent()

        val (patchedSession, sessionResult) = DshCompatPatch.patch(sessionSite, session, VERSION)
        val fsLocalSite = DshCompatPatch.PATCHED_FILES.first { it.relativePath.contains("fs-local") }
        val (patchedFsLocal, fsLocalResult) = DshCompatPatch.patch(fsLocalSite, fsLocal, VERSION)

        assertEquals(DshCompatPatch.Result.PATCHED, sessionResult)
        assertTrue(patchedSession.contains("await copyFile(staged, currentPath, 1);"))
        assertTrue(patchedSession.contains("await copyFile(tmp, finalPath, 1);"))
        assertEquals(DshCompatPatch.Result.PATCHED, fsLocalResult)
        assertTrue(patchedFsLocal.contains("await copyFile(tempPath, absolutePath, 1);"))
        assertTrue(patchedFsLocal.contains("import { chmod, copyFile, link, lstat"))
    }

    @Test
    fun `patches the free-pi plugin linkSync publication`() {
        // Verbatim head and call from the published dsh-free-pi-official tarball.
        val source = """
            import { chmodSync, linkSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
            export function loadInstallSecret(home) {
              const file = join(home, 'freepi-install.json');
              linkSync(temp, file);
            }
        """.trimIndent()

        val (patched, result) = DshCompatPatch.patch(freepiSite, source, VERSION)

        assertEquals(DshCompatPatch.Result.PATCHED, result)
        assertTrue(patched.contains("copyFileSync(temp, file, 1);"))
        assertTrue(patched.contains("import { chmodSync, copyFileSync, linkSync"))
        assertTrue(!patched.contains("linkSync(temp, file)"))
    }

    @Test
    fun `leaves an already patched free-pi plugin untouched`() {
        val source = """
            import { chmodSync, copyFileSync, linkSync, mkdirSync } from 'node:fs'
            copyFileSync(temp, file, 1);
        """.trimIndent()

        val (patched, result) = DshCompatPatch.patch(freepiSite, source, VERSION)

        assertEquals(DshCompatPatch.Result.ALREADY_PATCHED, result)
        assertEquals(source, patched)
    }

    @Test(expected = DshCompatPatch.IncompatibleReleaseException::class)
    fun `fails loudly when the free-pi plugin shape is unknown`() {
        val source = """
            import { publishSync } from "@deepseek-ai/fs-publish";
            publishSync(temp, file);
        """.trimIndent()

        DshCompatPatch.patch(freepiSite, source, VERSION)
    }

    @Test(expected = DshCompatPatch.IncompatibleReleaseException::class)
    fun `fails when the free-pi plugin still links after the rewrite`() {
        val source = """
            import { chmodSync, linkSync, mkdirSync, readFileSync, rmSync, unlinkSync, writeFileSync } from 'node:fs'
            linkSync(temp, file);
            linkSync(other, otherFile);
        """.trimIndent()

        // The second, unknown call site survives the rewrite, so the guard trips
        // instead of shipping a half-patched file.
        DshCompatPatch.patch(freepiSite, source, VERSION)
    }

    @Test
    fun `every patched site names a release file under the dsh package scope`() {
        assertTrue(DshCompatPatch.PATCHED_FILES.isNotEmpty())
        DshCompatPatch.PATCHED_FILES.forEach { site ->
            if (site.relativePath == "dsh-free-pi-official/index.js") {
                // The plugin installs under the web profile's own node_modules.
                return@forEach
            }
            assertTrue(site.relativePath.startsWith("@deepseek-ai/"))
            assertTrue(site.relativePath.endsWith("/lib/index.js"))
        }
    }

    private companion object {
        const val VERSION = "0.2.2-rc.5"

        val freepiSite = DshCompatPatch.PATCHED_FILES
            .first { it.relativePath.contains("free-pi") }
    }
}
