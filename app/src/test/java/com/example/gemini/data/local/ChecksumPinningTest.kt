package com.example.gemini.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * P1 (checksum pinning): verifies the npm `dist.integrity` parser used by
 * AgentProvisioner.installOpencode before the tarball is unpacked in the guest.
 * A missing, malformed or non-sha512 scheme must fail closed (return null).
 */
class ChecksumPinningTest {

    @Test
    fun `sha512 integrity converts base64 to lowercase hex`() {
        // [0xab, 0xcd, 0xef] encodes to base64 "q83v".
        assertEquals("abcdef", AgentProvisioner.parseNpmIntegrity("sha512-q83v"))
    }

    @Test
    fun `sha512 integrity with full base64 alphabet decodes`() {
        // Realistic shape: 64 raw bytes -> 86 base64 chars (with padding).
        val raw = ByteArray(64) { it.toByte() }
        val b64 = java.util.Base64.getEncoder().encodeToString(raw)
        val hex = raw.joinToString("") { "%02x".format(it) }
        assertEquals(hex, AgentProvisioner.parseNpmIntegrity("sha512-$b64"))
    }

    @Test
    fun `unsupported scheme returns null`() {
        assertNull(AgentProvisioner.parseNpmIntegrity("sha256-q83v"))
        assertNull(AgentProvisioner.parseNpmIntegrity("md5-q83v"))
    }

    @Test
    fun `missing or malformed integrity returns null`() {
        assertNull(AgentProvisioner.parseNpmIntegrity(""))
        assertNull(AgentProvisioner.parseNpmIntegrity("sha512-"))
        assertNull(AgentProvisioner.parseNpmIntegrity("sha512-%%%not-base64%%%"))
    }
}
