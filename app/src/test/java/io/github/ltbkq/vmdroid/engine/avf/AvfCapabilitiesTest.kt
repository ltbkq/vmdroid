package io.github.ltbkq.vmdroid.engine.avf

import io.github.ltbkq.vmdroid.engine.avf.AvfCapabilities.ProtectedVmChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvfCapabilitiesTest {
    @Test fun `non-protected supported chooses NonProtected`() {
        assertEquals(ProtectedVmChoice.NonProtected, AvfCapabilities.choose(2))
    }

    @Test fun `both bits set still chooses NonProtected`() {
        assertEquals(ProtectedVmChoice.NonProtected, AvfCapabilities.choose(3))
    }

    @Test fun `protected-only is Unsupported`() {
        assertTrue(AvfCapabilities.choose(1) is ProtectedVmChoice.Unsupported)
    }

    @Test fun `zero capabilities is Unknown`() {
        assertEquals(ProtectedVmChoice.Unknown, AvfCapabilities.choose(0))
    }

    @Test fun `decode formats present bits`() {
        assertEquals("PROTECTED+NON_PROTECTED", AvfCapabilities.decode(3))
        assertEquals("NON_PROTECTED", AvfCapabilities.decode(2))
        assertEquals("PROTECTED", AvfCapabilities.decode(1))
        assertEquals("none/unknown", AvfCapabilities.decode(0))
    }
}
