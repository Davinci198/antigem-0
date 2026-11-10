package com.example.gemini.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File

/**
 * The APK ships xz-compressed binaries that the app decompresses on first launch (LocalServerManager for the IDE
 * bridge, LocalEnvironmentManager for the minimal Glibc runtime). These tests run the same reader the app uses over
 * the real asset files, so a container tukaani cannot parse, or a truncated asset, fails here instead of bricking the
 * first launch on a phone. Unit tests run with app/ as the working directory.
 */
class CompressedAssetsTest {

    /** Reads an .xz file the way the app does and returns (all bytes it decompressed to, first headSize of them). */
    private fun decompressSize(file: File, headSize: Int = 0): Pair<Long, ByteArray> {
        val head = ByteArray(headSize)
        var total = 0L
        XZInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { input ->
            if (headSize > 0) {
                var read = 0
                while (read < headSize) {
                    val n = input.read(head, read, headSize - read)
                    if (n < 0) break
                    read += n
                }
                total += read
            }
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
            }
        }
        return total to head
    }

    @Test
    fun glibcTarballDecompressesToTheExpectedTar() {
        val xz = File("src/main/assets/glibc-min-arm64.tar.xz")
        require(xz.exists()) { "glibc-min-arm64.tar.xz must be committed: it is the only Glibc runtime the app has" }

        val (total, head) = decompressSize(xz, headSize = 6)
        assertEquals("the tar must start with the glibc/ directory entry", "glibc/", String(head, Charsets.ISO_8859_1))
        // the uncompressed tar this was made from (17 entries); a silent truncation shows up here
        assertEquals("glibc-min-arm64.tar must decompress to its full length", 4_264_448L, total)
    }

    @Test
    fun bridgeBinaryDecompressesToAnElfExecutable() {
        val xz = File("src/main/assets/bin/agy_ide_bridge.xz")
        // written by compressAgyIdeBridge and gitignored, so it only exists after a Gradle build
        assumeTrue("assets/bin/agy_ide_bridge.xz not built yet", xz.exists())

        val (total, head) = decompressSize(xz, headSize = 4)
        assertEquals("must be an ELF binary", listOf<Byte>(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()), head.toList())
        // the arm64 Go bridge is ~13 MB uncompressed; a much smaller result means the container is incomplete
        assertTrue("bridge decompressed to only $total bytes", total > 5_000_000L)
    }
}
