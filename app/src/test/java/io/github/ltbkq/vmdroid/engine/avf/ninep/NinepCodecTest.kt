package io.github.ltbkq.vmdroid.engine.avf.ninep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class NinepCodecTest {
    @Test fun u32_roundtrip_littleEndian() {
        val b = ByteArrayOutputStream(); val w = NinepCodec.Writer(b)
        w.writeU32(0x04030201)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), b.toByteArray())
        val r = NinepCodec.Reader(byteArrayOf(1, 2, 3, 4))
        assertEquals(0x04030201L, r.readU32())
    }

    @Test fun string_lengthPrefixed_utf8() {
        val b = ByteArrayOutputStream(); NinepCodec.Writer(b).writeString("hi")
        // s[2] = 0x0002 LE, then "hi"
        assertArrayEquals(byteArrayOf(2, 0, 'h'.code.toByte(), 'i'.code.toByte()), b.toByteArray())
    }

    @Test fun frame_roundtrip() {
        val body = byteArrayOf(9, 9, 9)
        val out = ByteArrayOutputStream()
        NinepCodec.writeFrame(out, type = Ninep.Rread, tag = 42, body = body)
        val f = NinepCodec.readFrame(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(Ninep.Rread, f.type); assertEquals(42, f.tag)
        assertArrayEquals(body, f.body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun readFrame_rejects_size_over_ceiling_before_allocating_body() {
        // A declared size just past the ceiling must be rejected by the bounds
        // check before any body array is allocated, not discovered by attempting
        // a huge (or, once truncated to Int, negative) readFully.
        val sizeBytes = ByteArrayOutputStream().also {
            NinepCodec.Writer(it).writeU32((NinepCodec.MAX_FRAME_SIZE + 1).toLong())
        }.toByteArray()
        NinepCodec.readFrame(ByteArrayInputStream(sizeBytes))
    }

    @Test(expected = IllegalArgumentException::class)
    fun readFrame_rejects_size_that_would_wrap_negative_when_truncated_to_int() {
        // 0xFFFFFFFF decoded as an unsigned 32-bit size; (size - 4).toInt() would
        // wrap to a negative Int if it were ever computed. The ceiling check must
        // reject this long before that arithmetic runs.
        val sizeBytes = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        NinepCodec.readFrame(ByteArrayInputStream(sizeBytes))
    }

    @Test fun readFrame_accepts_custom_maxSize_at_the_boundary() {
        val body = byteArrayOf(1, 2, 3)
        val out = ByteArrayOutputStream()
        NinepCodec.writeFrame(out, type = Ninep.Rread, tag = 1, body = body)
        val exactSize = out.toByteArray().size
        val f = NinepCodec.readFrame(ByteArrayInputStream(out.toByteArray()), maxSize = exactSize)!!
        assertArrayEquals(body, f.body)
    }
}
