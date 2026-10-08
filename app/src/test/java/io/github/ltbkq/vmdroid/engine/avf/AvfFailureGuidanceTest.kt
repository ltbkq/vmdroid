package io.github.ltbkq.vmdroid.engine.avf

import org.junit.Assert.assertEquals
import org.junit.Test

class AvfFailureGuidanceTest {
    @Test fun `multi core suggests trying one core`() {
        assertEquals(AvfFailureGuidance.Advice.TRY_ONE_CORE, AvfFailureGuidance.advise(cpus = 8))
        assertEquals(AvfFailureGuidance.Advice.TRY_ONE_CORE, AvfFailureGuidance.advise(cpus = 2))
    }
    @Test fun `single core suggests switching to qemu`() {
        assertEquals(AvfFailureGuidance.Advice.SWITCH_TO_QEMU, AvfFailureGuidance.advise(cpus = 1))
    }
}
