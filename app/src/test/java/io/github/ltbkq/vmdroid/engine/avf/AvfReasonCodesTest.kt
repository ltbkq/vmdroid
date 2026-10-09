package io.github.ltbkq.vmdroid.engine.avf

import org.junit.Assert.assertEquals
import org.junit.Test

class AvfReasonCodesTest {
    @Test fun `reboot decodes to STOP_REASON_REBOOT`() {
        assertEquals("5 STOP_REASON_REBOOT", AvfReasonCodes.stopReason(5))
    }

    @Test fun `start-failed decodes`() {
        assertEquals("4 STOP_REASON_START_FAILED", AvfReasonCodes.stopReason(4))
    }

    @Test fun `service-died negative code decodes`() {
        assertEquals("-1 STOP_REASON_VIRTUALIZATION_SERVICE_DIED", AvfReasonCodes.stopReason(-1))
    }

    @Test fun `unmapped stop reason is labelled`() {
        assertEquals("99 (unmapped)", AvfReasonCodes.stopReason(99))
    }

    @Test fun `error code decodes`() {
        assertEquals("3 ERROR_PAYLOAD_INVALID_CONFIG", AvfReasonCodes.errorCode(3))
    }
}
