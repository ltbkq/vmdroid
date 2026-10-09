package io.github.ltbkq.vmdroid.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostMetricsTest {

    @Test
    fun percent_clampsAndHandlesZeroTotal() {
        assertEquals(0f, HostMetrics.percent(100, 0))
        assertEquals(0.5f, HostMetrics.percent(512, 1024))
        assertEquals(1f, HostMetrics.percent(2000, 1000))
    }

    @Test
    fun formatGb_formatsSmallValuesWithDecimal() {
        assertEquals("1.5 GB", HostMetrics.formatGb(1.5))
        assertEquals("12 GB", HostMetrics.formatGb(12.4))
    }

    @Test
    fun processVmRssMb_returnsNullForMissingPid() {
        assertNull(HostMetrics.processVmRssMb(-1))
    }
}
