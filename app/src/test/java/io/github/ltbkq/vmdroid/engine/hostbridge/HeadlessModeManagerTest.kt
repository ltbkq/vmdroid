package io.github.ltbkq.vmdroid.engine.hostbridge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HeadlessModeManagerTest {
    @Test fun defaultsToInactive() {
        assertFalse(HeadlessModeManager().active.value)
    }

    @Test fun setActiveTogglesState() {
        val m = HeadlessModeManager()
        m.setActive(true)
        assertTrue(m.active.value)
        m.setActive(false)
        assertFalse(m.active.value)
    }
}
