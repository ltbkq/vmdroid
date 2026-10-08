package io.github.ltbkq.vmdroid.engine.avf

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

class DatagramFramingTest {

    @Test fun encode_writes_big_endian_length_prefix() {
        val out = DatagramFraming.encode(byteArrayOf(1, 2, 3), 3)
        assertEquals(5, out.size)
        assertEquals(0, out[0].toInt())   // len hi
        assertEquals(3, out[1].toInt())   // len lo
        assertArrayEquals(byteArrayOf(1, 2, 3), out.copyOfRange(2, 5))
    }

    @Test fun encode_decode_round_trip() {
        val payload = ByteArray(300) { (it % 256).toByte() }
        val framed = DatagramFraming.encode(payload, payload.size)
        val got = DatagramFraming.readFrame(ByteArrayInputStream(framed))
        assertArrayEquals(payload, got)
    }

    @Test fun readFrame_reassembles_across_one_byte_reads() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val framed = DatagramFraming.encode(payload, payload.size)
        // Stream that hands out exactly one byte per read() call.
        val drip = object : InputStream() {
            private val src = ByteArrayInputStream(framed)
            override fun read(): Int = src.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                if (len == 0) 0 else { val c = src.read(); if (c < 0) -1 else { b[off] = c.toByte(); 1 } }
        }
        assertArrayEquals(payload, DatagramFraming.readFrame(drip))
    }

    @Test fun readFrame_handles_zero_length_datagram() {
        val framed = DatagramFraming.encode(ByteArray(0), 0)
        assertArrayEquals(ByteArray(0), DatagramFraming.readFrame(ByteArrayInputStream(framed)))
    }

    @Test fun readFrame_handles_max_size_datagram() {
        val payload = ByteArray(DatagramFraming.MAX_PAYLOAD) { 0x5a }
        val framed = DatagramFraming.encode(payload, payload.size)
        assertArrayEquals(payload, DatagramFraming.readFrame(ByteArrayInputStream(framed)))
    }

    @Test fun readFrame_returns_null_on_clean_eof() {
        assertNull(DatagramFraming.readFrame(ByteArrayInputStream(ByteArray(0))))
    }

    @Test(expected = IOException::class)
    fun readFrame_throws_on_truncated_payload() {
        // Claims 4 bytes but supplies only 2.
        val truncated = byteArrayOf(0, 4, 1, 2)
        DatagramFraming.readFrame(ByteArrayInputStream(truncated))
    }

    @Test(expected = IllegalArgumentException::class)
    fun encode_rejects_length_exceeding_payload() {
        DatagramFraming.encode(byteArrayOf(1, 2), 5)
    }
}
