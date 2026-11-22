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
    fun `every patched site names a release file under the dsh package scope`() {
        assertTrue(DshCompatPatch.PATCHED_FILES.isNotEmpty())
        DshCompatPatch.PATCHED_FILES.forEach { site ->
            assertTrue(site.relativePath.startsWith("@deepseek-ai/"))
            assertTrue(site.relativePath.endsWith("/lib/index.js"))
        }
    }

    private companion object {
        const val VERSION = "0.2.0-rc.2"
    }
}
